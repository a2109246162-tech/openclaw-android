package com.openclaw.android.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信 ClawBot 回复服务：前台服务常驻，长轮询收消息 → 调模型 → 回微信。
 * 用户在「微信」页扫码连接成功后启动；解锁屏幕、切后台都能继续回复。
 */
public class ClawBotService extends Service {

    private static final String CH_ID = "openclaw_clawbot_v2";
    private static final int NOTI_ID = 1001;
    private static final int STALE_TOKEN = -14;

    private static volatile boolean sRunning = false;

    /** 给 UI 查询服务是否在跑。 */
    public static boolean isRunning() {
        return sRunning;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile Thread worker;
    private volatile boolean running;
    private Store store;

    // 每个会话一个模型上下文（不做持久化，进程被杀就重新来）
    private final ConcurrentHashMap<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();
    private static final int MAX_SESSIONS = 20;
    private static final int CTX_MAX = 20;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        acquireWake();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, buildNotification("微信 ClawBot 回复中…"),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTI_ID, buildNotification("微信 ClawBot 回复中…"));
            }
            store.log("服务启动，前台通知已挂");
        } catch (Throwable e) {
            // 某些机型/权限下前台通知会抛异常，降级为普通后台线程，别让服务直接死
            store.log("前台通知失败：" + e.getClass().getSimpleName() + "，降级运行");
        }
        startLoop();
    }

    /** 长轮询期间保持 CPU 唤醒，避免息屏后被冻结。 */
    private void acquireWake() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "openclaw:clawbot");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(12 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) {
        }
    }

    private android.os.PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // 重新扫码后 token 会变，必须换掉旧循环——否则服务还在用失效的旧 token 轮询，
        // 表现就是"已连接但永远不回消息"，只能靠重装/杀进程恢复。
        String token = store.clawBotToken();
        if (token != null && !token.isEmpty() && runningToken != null
                && !runningToken.equals(token)) {
            store.log("检测到新凭据，重启监听");
            stopLoop();
        }
        if (!running) startLoop();
        return START_STICKY;
    }

    private String runningToken = null;

    private void stopLoop() {
        running = false;
        sRunning = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        sRunning = false;
        if (worker != null) worker.interrupt();
        if (wakeLock != null) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        store.log("服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ 主循环

    private void startLoop() {
        final String token = store.clawBotToken();
        if (token == null || token.isEmpty()) {
            store.log("无 token，服务退出");
            stopSelf();
            return;
        }
        running = true;
        sRunning = true;
        runningToken = token;
        store.log("开始监听，base=" + (store.clawBotBaseUrl().isEmpty() ? "默认" : store.clawBotBaseUrl()));
        final String baseUrl = store.clawBotBaseUrl();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                String buf = store.updatesBuf();
                int failures = 0;
                boolean loggedOk = false;
                while (running) {
                    ClawBot.Updates u = ClawBot.getUpdates(token, baseUrl, buf);
                    if (!running) return;

                    if (!u.ok) {
                        failures++;
                        store.log("拉取失败(" + failures + ")：" + u.errmsg
                                + (u.errcode != 0 ? " code=" + u.errcode : ""));
                        if (u.errcode == STALE_TOKEN) {
                            // 官方插件对 -14 是"暂停"，不是作废凭据；这里也暂停 2 分钟后重试
                            store.log("会话超时(-14)，暂停 2 分钟后重试");
                            sleepQuietly(120000);
                            failures = 0;
                            continue;
                        }
                        sleepQuietly(failures >= 3 ? 30000 : 3000);
                        continue;
                    }
                    if (!loggedOk) {
                        store.log("长轮询已连通");
                        loggedOk = true;
                    }
                    failures = 0;
                    if (u.buf != null && !u.buf.isEmpty() && !u.buf.equals(buf)) {
                        buf = u.buf;
                        store.saveUpdatesBuf(buf);
                    }

                    for (final ClawBot.Inbound in : u.msgs) {
                        if (!running) return;
                        store.log("收到消息 from=" + shortId(in.fromUserId));
                        try {
                            handleInbound(token, in);
                        } catch (Throwable e) {
                            // 单条消息出错不能把整个监听循环打死
                            store.log("处理消息出错：" + e.getClass().getSimpleName());
                        }
                    }
                }
            }
        }, "openclaw-clawbot-loop");
        worker.start();
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.length() > 10 ? id.substring(0, 6) + "…" : id;
    }

    // ------------------------------------------------------------------ 处理一条消息

    private void handleInbound(final String token, final ClawBot.Inbound in) {
        String userText = in.text == null ? "" : in.text.trim();

        // 图片消息：下载 + 解密后当作多模态输入
        String imagePath = null;
        if (in.hasImage) {
            try {
                byte[] data = ClawBot.fetchImage(in);
                imagePath = ImageStore.saveBytes(this, data);
                store.log("收到图片 " + (data.length / 1024) + " KB");
            } catch (Exception e) {
                store.log("图片处理失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }

        if (userText.isEmpty() && imagePath == null) {
            replySafe(token, in, in.hasImage ? "这张图我没取下来，麻烦再发一次。" : "暂时只支持文字和图片消息。");
            return;
        }
        if (isCommand(userText)) {
            replySafe(token, in, handleCommand(userText));
            return;
        }

        String key = in.fromUserId;
        List<ChatMessage> ctx = sessions.get(key);
        if (ctx == null) {
            ctx = new ArrayList<>();
            sessions.put(key, ctx);
            trimSessions();
        }

        Store.Settings st = store.settings();
        ChatMessage userMsg = new ChatMessage(ChatMessage.ROLE_USER,
                userText.isEmpty() ? "请看这张图片" : userText);
        userMsg.imagePath = imagePath;
        ctx.add(userMsg);
        while (ctx.size() > Math.max(2, st.contextLimit)) ctx.remove(0);

        // 调模型（同步，在服务线程里没问题）
        final List<ChatMessage> snapshot = new ArrayList<>(ctx);
        ApiClient api = new ApiClient();
        final StringBuilder out = new StringBuilder();
        final String[] usedModel = {""};
        Usage total = new Usage();

        List<ModelConfig> chain = store.chain();
        String lastErr = null;
        boolean ok = false;
        for (ModelConfig m : chain) {
            try {
                out.setLength(0);
                Usage u = new Usage();
                String full = api.call(m, snapshot, st, u, new ApiClient.Listener() {
                    @Override
                    public void onStart(ModelConfig model) {
                        usedModel[0] = model.name;
                    }

                    @Override
                    public void onDelta(String chunk) {
                        out.append(chunk);
                    }

                    @Override
                    public void onReasoning(int chars) {
                        // 忽略推理进度
                    }

                    @Override
                    public void onDone(ModelConfig model, Usage usage, String fullText) {
                    }

                    @Override
                    public void onFailover(ModelConfig failed, String reason) {
                    }

                    @Override
                    public void onAllFailed(String reason) {
                    }

                    @Override
                    public void onCancelled() {
                    }
                });
                if (full == null || full.trim().isEmpty()) {
                    lastErr = m.name + " 返回空内容";
                    continue;
                }
                u.normalize();
                if (u.total <= 0) {
                    Usage est = Usage.estimate(userText, full);
                    u.prompt = est.prompt;
                    u.completion = est.completion;
                    u.total = est.total;
                    u.estimated = true;
                }
                total = u;
                ok = true;
                break;
            } catch (Exception e) {
                lastErr = m.name + "：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }

        if (!ok) {
            store.log("模型全失败：" + (lastErr == null ? "无可用模型" : lastErr));
            replySafe(token, in, "调用模型失败了：\n" + (lastErr == null ? "没有可用模型" : lastErr));
            return;
        }

        String answer = out.toString();
        store.log("模型 " + usedModel[0] + " 回复 " + answer.length() + " 字");
        ChatMessage assistant = new ChatMessage(ChatMessage.ROLE_ASSISTANT, answer);
        assistant.model = usedModel[0];
        assistant.promptTokens = total.prompt;
        assistant.completionTokens = total.completion;
        assistant.totalTokens = total.total;
        assistant.estimated = total.estimated;
        ctx.add(assistant);

        // 记入本机用量统计与聊天记录
        store.addUsage(usedModel[0], total, true);
        List<ChatMessage> hist = store.history();
        ChatMessage hu = new ChatMessage(ChatMessage.ROLE_USER, userMsg.content);
        hu.imagePath = userMsg.imagePath;
        hu.model = "微信";
        hist.add(hu);
        hist.add(assistant);
        store.saveHistory(hist);

        String err = ClawBot.sendMessage(token, store.clawBotBaseUrl(), in.fromUserId,
                answer, in.contextToken);
        if (err != null) {
            store.log("发送失败：" + err);
        } else {
            store.log("已回复到微信");
        }
    }

    private void replySafe(String token, ClawBot.Inbound in, String text) {
        String err = ClawBot.sendMessage(token, store.clawBotBaseUrl(), in.fromUserId, text, in.contextToken);
        if (err != null) store.log("发送失败：" + err);
    }

    // ------------------------------------------------------------------ 简单指令

    private boolean isCommand(String t) {
        String low = t.toLowerCase();
        return low.equals("/status") || low.equals("/help") || low.equals("/reset")
                || low.equals("状态") || low.equals("帮助") || low.equals("重置");
    }

    private String handleCommand(String t) {
        String low = t.toLowerCase();
        if (low.equals("/help") || low.equals("帮助")) {
            return "OpenClaw 微信助手\n"
                    + "· 直接发消息 = 和你的模型对话\n"
                    + "· /status 或「状态」：查看用量\n"
                    + "· /reset 或「重置」：清空本会话上下文";
        }
        if (low.equals("/status") || low.equals("状态")) {
            UsageStats s = store.stats();
            return "累计 tokens：" + Ui2.thousand(s.total)
                    + "\n请求次数：" + s.requests
                    + "\n主模型：" + store.primaryModelName();
        }
        sessions.clear();
        return "已清空会话上下文";
    }

    private void trimSessions() {
        while (sessions.size() > MAX_SESSIONS) {
            String oldest = sessions.keys().nextElement();
            sessions.remove(oldest);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------------ 前台通知

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            // LOW 而不是 MIN：MIN 级通知在华为/鸿蒙上会被折叠，系统更容易把服务当低优先级干掉
            NotificationChannel ch = new NotificationChannel(CH_ID, "微信消息通道",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        android.app.PendingIntent pi = null;
        try {
            Intent open = new Intent(this, com.openclaw.android.MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = android.app.PendingIntent.getActivity(this, 0, open,
                    Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
        } catch (Exception ignored) {
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setContentTitle("OpenClaw 正在接收微信消息")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (pi != null) b.setContentIntent(pi);
        return b.build();
    }

    /** 避免在 Service 里引 UI 类，复制一个千分位。 */
    private static final class Ui2 {
        static String thousand(long v) {
            String s = Long.toString(Math.abs(v));
            StringBuilder out = new StringBuilder();
            int c = 0;
            for (int i = s.length() - 1; i >= 0; i--) {
                out.append(s.charAt(i));
                c++;
                if (c % 3 == 0 && i > 0) out.append(',');
            }
            if (v < 0) out.append('-');
            return out.reverse().toString();
        }
    }
}
