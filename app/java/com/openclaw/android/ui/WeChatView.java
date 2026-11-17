package com.openclaw.android.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.openclaw.android.core.ClawBot;
import com.openclaw.android.core.QrCode;
import com.openclaw.android.core.Store;

/**
 * 微信页 —— 一件事：一个按钮，点出微信 ClawBot 官方二维码。
 * 扫码 → 微信里确认 → 微信列表出现「微信 ClawBot」，之后直接在微信里和你的模型聊。
 */
public class WeChatView extends ScrollView {

    private final Activity act;
    private final Host host;
    private final Store store;
    private final Theme t;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageView qrImage;
    private TextView stateView;
    private TextView tipView;
    private TextView actionBtn;
    private FrameLayout qrWrap;

    private ClawBot.Session session;
    private Thread pollThread;
    private volatile boolean polling;
    private Bitmap qrBitmap;

    public WeChatView(Activity act, Host host, Theme t) {
        super(act);
        this.act = act;
        this.host = host;
        this.store = host.store();
        this.t = t;
        setBackgroundColor(t.bg);
        setVerticalScrollBarEnabled(false);
        build();
        if (store.clawBotToken() != null && !store.clawBotToken().isEmpty()) {
            applyConnectedUi(false);
        }
    }
    private void build() {
        removeAllViews();
        LinearLayout root = Ui.col(act);
        Ui.padDp(act, root, 16, 8, 16, 28);

        // ---------------- 主卡片：按钮 + 二维码 + 状态
        LinearLayout card = Ui.card(act, t);
        card.addView(Ui.bold(act, "连接微信", 16f, t.text));

        TextView desc = Ui.tv(act,
                "点按钮生成微信官方 ClawBot 二维码，用微信扫一扫并确认，"
                        + "微信聊天列表会出现「微信 ClawBot」。之后在微信里发消息，就是跟你的模型对话。",
                12, t.textDim);
        desc.setLineSpacing(Ui.dp(act, 4), 1f);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.topMargin = Ui.dp(act, 6);
        card.addView(desc, dp);

        actionBtn = Ui.btn(act, t, "生成二维码", t.accent, t.onAccent, true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(act, 14);
        actionBtn.setLayoutParams(bp);
        actionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startLogin();
            }
        });
        card.addView(actionBtn);

        stateView = Ui.tv(act, "", 12, t.textDim);
        stateView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(act, 10);
        stateView.setLayoutParams(sp);
        card.addView(stateView);

        qrWrap = new FrameLayout(act);
        qrWrap.setBackground(Ui.shape(act, 0xFFFFFFFF, 16));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = Ui.dp(act, 12);
        qrWrap.setLayoutParams(wp);
        Ui.padDp(act, qrWrap, 14, 14, 14, 14);
        qrWrap.setVisibility(GONE);

        qrImage = new ImageView(act);
        qrImage.setAdjustViewBounds(true);
        qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrWrap.addView(qrImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(qrWrap);

        tipView = Ui.tv(act, "", 11, t.textDim);
        tipView.setLineSpacing(Ui.dp(act, 3), 1f);
        tipView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.dp(act, 8);
        card.addView(tipView, tp);

        root.addView(card);

        // ---------------- 前置条件
        LinearLayout pre = Ui.card(act, t);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = Ui.dp(act, 12);
        pre.setLayoutParams(pp);
        pre.addView(Ui.bold(act, "扫码前确认两件事", 13, t.text));
        TextView preTip = Ui.tv(act,
                "① 微信已更新到最新版，且 我 → 设置 → 插件 里能看到「微信ClawBot」（官方在逐步放量，没有入口说明还没覆盖到你，过几天再试）；\n"
                        + "② 「模型」页里至少有一个可用的模型，否则连上了它也无话可说。",
                11, t.textDim);
        preTip.setLineSpacing(Ui.dp(act, 4), 1f);
        LinearLayout.LayoutParams ptp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ptp.topMargin = Ui.dp(act, 6);
        pre.addView(preTip, ptp);
        root.addView(pre);

        // ---------------- 运行状态（排查用）
        LinearLayout diag = Ui.card(act, t);
        LinearLayout.LayoutParams dgp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dgp.topMargin = Ui.dp(act, 12);
        diag.setLayoutParams(dgp);
        diag.addView(Ui.bold(act, "运行状态", 13, t.text));

        String logText = store.logText();
        TextView logView = Ui.tv(act, logText.isEmpty() ? "还没有日志，连接后会自动记录" : logText,
                10.5f, t.textDim);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setLineSpacing(Ui.dp(act, 2), 1f);
        LinearLayout.LayoutParams lgp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lgp.topMargin = Ui.dp(act, 8);
        diag.addView(logView, lgp);

        LinearLayout btns = Ui.row(act);
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btp.topMargin = Ui.dp(act, 10);
        btns.setLayoutParams(btp);

        TextView refresh = Ui.btnGhost(act, t, "刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                build();
            }
        });
        TextView restart = Ui.btnGhost(act, t, "重启服务");
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.log("手动重启服务");
                startReplyService();
                host.toast("已发重启指令，回到本页看日志");
            }
        });
        btns.addView(refresh, Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(Ui.hspacer(act, 8));
        btns.addView(restart, Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        diag.addView(btns);

        TextView battery = Ui.btnGhost(act, t, "允许后台运行（关闭电池限制）");
        LinearLayout.LayoutParams bgp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bgp.topMargin = Ui.dp(act, 8);
        battery.setLayoutParams(bgp);
        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBattery();
            }
        });
        diag.addView(battery);

        TextView probe = Ui.btn(act, t, "立即自检（微信发完消息点这里）", t.chip, t.accent, true);
        LinearLayout.LayoutParams pgp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pgp.topMargin = Ui.dp(act, 8);
        probe.setLayoutParams(pgp);
        probe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSelfCheck();
            }
        });
        diag.addView(probe);

        String token = store.clawBotToken();
        if (token != null && !token.isEmpty()) {
            TextView tid = Ui.tv(act, "凭据：" + token.substring(0, Math.min(6, token.length()))
                            + "…   base：" + (store.clawBotBaseUrl().isEmpty() ? "默认" : store.clawBotBaseUrl()),
                    10, t.textDim);
            LinearLayout.LayoutParams tgp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tgp.topMargin = Ui.dp(act, 6);
            diag.addView(tid, tgp);
        }
        root.addView(diag);

        // ---------------- 解绑
        LinearLayout misc = Ui.card(act, t);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = Ui.dp(act, 12);
        misc.setLayoutParams(mp);
        TextView unlink = Ui.btnGhost(act, t, "解除绑定（清除本机连接凭据）");
        unlink.setLayoutParams(Ui.match());
        unlink.setTextColor(t.danger);
        unlink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.saveClawBot(null, null, null);
                stopReplyService();
                build();
                host.toast("已清除本机凭据");
            }
        });
        misc.addView(unlink);

        boolean svcOn = com.openclaw.android.core.ClawBotService.isRunning();
        TextView svc = Ui.tv(act, svcOn ? "● 回复服务运行中（收到微信消息自动回复）"
                        : "○ 回复服务未运行（连接成功后自动启动）",
                11, svcOn ? t.ok : t.textDim);
        svc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svp.topMargin = Ui.dp(act, 10);
        svc.setLayoutParams(svp);
        misc.addView(svc);
        root.addView(misc);

        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------------ 登录流程

    private void startLogin() {
        stopPolling();
        actionBtn.setEnabled(false);
        actionBtn.setText("正在向微信获取二维码…");
        stateView.setText("");
        stateView.setTextColor(t.textDim);
        tipView.setText("");
        qrWrap.setVisibility(GONE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 带上已有 token：重复扫码时微信会复用同一个 bot，而不是再造一个
                final ClawBot.Session s = ClawBot.createQr(store.clawBotToken());
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        actionBtn.setEnabled(true);
                        actionBtn.setText("重新生成二维码");
                        if (s.error != null) {
                            stateView.setText("获取失败：" + s.error);
                            stateView.setTextColor(t.danger);
                            return;
                        }
                        session = s;
                        qrWrap.setVisibility(VISIBLE);
                        renderQr(s.qrUrl);
                        stateView.setText("等待扫码…");
                        stateView.setTextColor(t.textDim);
                        tipView.setText("二维码几分钟会过期，过期了点上面按钮换一张。");
                        startPolling();
                    }
                });
            }
        }, "openclaw-qr").start();
    }

    private void renderQr(String content) {
        if (qrBitmap != null && !qrBitmap.isRecycled()) qrBitmap.recycle();
        qrBitmap = QrCode.render(content, 560, 0xFF0C0F13, 0xFFFFFFFF);
        if (qrBitmap != null) qrImage.setImageBitmap(qrBitmap);
    }

    private void startPolling() {
        polling = true;
        pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (polling && session != null) {
                    ClawBot.poll(session);
                    final String st = session.status;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!polling) return;
                            switch (st) {
                                case "scaned":
                                    stateView.setText("已扫码，正在验证…");
                                    stateView.setTextColor(t.ok);
                                    break;
                                case "need_verifycode":
                                    stateView.setText("微信里显示了配对数字，在微信里输入它继续");
                                    stateView.setTextColor(t.warn);
                                    break;
                                case "expired":
                                    stateView.setText("二维码已过期，点上面按钮重新生成");
                                    stateView.setTextColor(t.warn);
                                    polling = false;
                                    break;
                                case "failed":
                                    stateView.setText("连接失败：" + (session.error == null ? "未知错误" : session.error));
                                    stateView.setTextColor(t.danger);
                                    polling = false;
                                    break;
                                case "confirmed":
                                    polling = false;
                                    applyConnectedUi(true);
                                    break;
                                case "binded_redirect":
                                    polling = false;
                                    store.log("微信提示已绑定过，复用现有凭据");
                                    qrWrap.setVisibility(GONE);
                                    actionBtn.setText("已连接 · 重新扫码可换绑");
                                    stateView.setText("✓ 已绑定（复用原有连接）");
                                    stateView.setTextColor(t.ok);
                                    tipView.setText("微信侧已经连着这个 App 了，直接给「微信ClawBot」发消息即可。");
                                    startReplyService();
                                    break;
                                default:
                                    if (!"已扫码，正在验证…".equals(stateView.getText().toString())) {
                                        stateView.setText("等待扫码…");
                                    }
                                    break;
                            }
                        }
                    });
                    if ("confirmed".equals(st) || "expired".equals(st) || "failed".equals(st)
                            || "binded_redirect".equals(st)) break;
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "openclaw-qr-poll");
        pollThread.start();
    }

    private void applyConnectedUi(boolean isNew) {
        stopPolling();
        // 只有刚扫码成功才有 session；重进页面时 session 为 null，别拿它取字段
        if (session != null) {
            if (session.botToken == null || session.botToken.isEmpty()) {
                // 服务端没给 token，说明这次连接没真正完成，不要假装成功
                stateView.setText("连接异常：微信没返回凭据\n" + (session.rawConfirmed == null ? "" : session.rawConfirmed));
                stateView.setTextColor(t.danger);
                actionBtn.setText("重新生成二维码");
                actionBtn.setEnabled(true);
                return;
            }
            store.setClawBotPendingBaseUrl(session.baseUrl);
            store.saveClawBot(session.botToken, session.botId, session.userId);
            if (session.rawConfirmed != null) store.log("扫码确认：" + session.rawConfirmed);
        }
        startReplyService();
        qrWrap.setVisibility(GONE);
        actionBtn.setText("已连接 · 重新扫码可换绑");
        stateView.setText("✓ 微信 ClawBot 已连接，正在监听消息");
        stateView.setTextColor(t.ok);
        tipView.setText(isNew
                ? "连接成功！去微信给「微信 ClawBot」发条消息试试，它会用你的模型回答。"
                : "已连接，微信里的「微信 ClawBot」正在使用你的模型。");
    }

    /** 前台直接查一次 getupdates，把原始返回给用户看，不依赖后台服务是否活着。 */
    private void runSelfCheck() {
        final String token = store.clawBotToken();
        if (token == null || token.isEmpty()) {
            host.toast("还没有连接凭据，先扫码");
            return;
        }
        stopReplyService();
        host.toast("正在向微信查询，最多 12 秒…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final com.openclaw.android.core.ClawBot.Probe p =
                        com.openclaw.android.core.ClawBot.probe(token, store.clawBotBaseUrl(), 12000);
                store.log("自检 errcode=" + p.errcode + " msgs=" + p.msgCount
                        + (p.error == null ? "" : " " + p.error));
                final String summary = "HTTP：" + (p.httpOk ? "OK" : "FAIL")
                        + "\nerrcode：" + p.errcode
                        + "\n本次拉到消息：" + p.msgCount + " 条"
                        + "\n耗时：" + p.costMs + "ms"
                        + (p.error == null ? "" : "\n错误：" + p.error)
                        + "\n\n原始返回：\n" + p.raw;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        new android.app.AlertDialog.Builder(act)
                                .setTitle("自检结果")
                                .setMessage(summary)
                                .setPositiveButton("好，继续监听", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        startReplyService();
                                        build();
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        }, "openclaw-selfcheck").start();
    }

    private void startReplyService() {
        try {
            android.content.Intent i = new android.content.Intent(act, com.openclaw.android.core.ClawBotService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                act.startForegroundService(i);
            } else {
                act.startService(i);
            }
            store.log("已请求启动回复服务");
            // Android 13+ 通知权限
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        } catch (Throwable e) {
            // 典型的 ForegroundServiceStartNotAllowedException：App 在后台时被系统拒绝
            store.log("启动服务被拒：" + e.getClass().getSimpleName() + "（回到 App 前台会自动重试）");
            host.toast("系统暂不允许后台启动，回到 App 会自动重试");
        }
    }

    private void requestIgnoreBattery() {
        // 华为/鸿蒙优先跳"应用启动管理"，否则光关电池优化还是会冻结
        String[][] huawei = {
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"},
        };
        for (String[] c : huawei) {
            try {
                android.content.Intent i = new android.content.Intent();
                i.setComponent(new android.content.ComponentName(c[0], c[1]));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
                host.toast("把 OpenClaw 改成「手动管理」，三个开关全打开");
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
        } catch (Exception e) {
            try {
                act.startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                host.toast("请到 设置 → 应用 → OpenClaw → 耗电管理 里允许后台运行");
            }
        }
    }

    private void stopReplyService() {
        try {
            android.content.Intent i = new android.content.Intent(act, com.openclaw.android.core.ClawBotService.class);
            i.setAction("stop");
            act.startService(i);
        } catch (Exception ignored) {
        }
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null) pollThread.interrupt();
        pollThread = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPolling();
        super.onDetachedFromWindow();
    }
}
