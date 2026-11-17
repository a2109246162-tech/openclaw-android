package com.openclaw.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.openclaw.android.core.ApiClient;
import com.openclaw.android.core.ChatMessage;
import com.openclaw.android.core.ImageStore;
import com.openclaw.android.core.ModelConfig;
import com.openclaw.android.core.Store;
import com.openclaw.android.core.Usage;
import com.openclaw.android.core.UsageStats;
import com.openclaw.android.core.WeChat;

import java.util.ArrayList;
import java.util.List;

/** 对话页。 */
public class ChatView extends LinearLayout {

    private final Activity act;
    private final Host host;
    private final Store store;
    private final Theme t;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<ChatMessage> msgs = new ArrayList<>();
    private final StringBuilder buffer = new StringBuilder();

    private ScrollView scroll;
    private LinearLayout list;
    private EditText input;
    private TextView sendBtn;
    private TextView stopBtn;
    private TextView modelChip;

    private boolean busy = false;
    private boolean emptyShowing = false;
    private boolean flushScheduled = false;
    private long lastReasoningAt = 0;

    public static final int REQ_IMG = 2001;
    private String pendingImage = null;
    private LinearLayout previewRow;
    private ImageView previewImage;

    private ApiClient client;
    private ChatMessage curAssistant;
    private Bubble curBubble;

    private static class Bubble {
        View row;
        TextView body;
        TextView footer;
        TextView share;
    }

    private final Runnable flush = new Runnable() {
        @Override
        public void run() {
            flushScheduled = false;
            if (curBubble != null) {
                curBubble.body.setText(buffer.toString() + "▍");
                scrollDown();
            }
        }
    };

    public ChatView(Activity act, Host host, Theme t) {
        super(act);
        this.act = act;
        this.host = host;
        this.store = host.store();
        this.t = t;
        setOrientation(VERTICAL);
        setBackgroundColor(t.bg);
        msgs.addAll(store.history());
        build();
        render();
    }

    // ------------------------------------------------------------------ 结构

    private void build() {
        LinearLayout bar = Ui.row(act);
        Ui.padDp(act, bar, 16, 2, 12, 2);

        modelChip = Ui.chip(act, t, store.primaryModelName(), t.chip, t.accent);
        modelChip.setClickable(true);
        modelChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.goTab(1);
            }
        });
        bar.addView(modelChip);
        bar.addView(Ui.flex(act));

        stopBtn = Ui.btnGhost(act, t, "停止");
        stopBtn.setVisibility(GONE);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stop();
            }
        });
        bar.addView(stopBtn);
        bar.addView(Ui.hspacer(act, 8));

        TextView clear = Ui.btnGhost(act, t, "清空");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        bar.addView(clear);
        addView(bar, Ui.match());

        scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        list = Ui.col(act);
        Ui.padDp(act, list, 16, 4, 16, 8);
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, Ui.lpw(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout inputWrap = Ui.row(act);
        Ui.padDp(act, inputWrap, 12, 4, 12, 10);

        LinearLayout box = Ui.row(act);
        box.setBackground(Ui.shape(act, t.surface, 24, t.line, 1));
        Ui.padDp(act, box, 16, 4, 5, 4);

        input = new EditText(act);
        input.setHint("发消息…");
        input.setHintTextColor(t.textDim);
        input.setTextColor(t.text);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setBackground(null);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(1);
        input.setMaxLines(5);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
        box.addView(input, Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendBtn = new TextView(act);
        sendBtn.setText("↑");
        sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        sendBtn.setTypeface(Typeface.DEFAULT_BOLD);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setTextColor(t.onAccent);
        sendBtn.setBackground(Ui.ripple(Ui.shape(act, t.accent, 999), 0x33FFFFFF));
        sendBtn.setClickable(true);
        sendBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40)));
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    stop();
                } else {
                    send();
                }
            }
        });
        box.addView(sendBtn);

        inputWrap.addView(box, Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 加图按钮
        TextView attach = Ui.tv(act, "＋", 20, t.textDim);
        attach.setGravity(Gravity.CENTER);
        attach.setBackground(Ui.ripple(Ui.shape(act, t.surfaceAlt, 999, t.line, 1), 0x18000000));
        attach.setClickable(true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(act, 36), Ui.dp(act, 36));
        ap.rightMargin = Ui.dp(act, 8);
        attach.setLayoutParams(ap);
        attach.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        inputWrap.addView(attach, 0);

        // 待发送图片预览
        previewRow = Ui.row(act);
        previewRow.setVisibility(GONE);
        Ui.padDp(act, previewRow, 16, 6, 16, 0);
        previewImage = new ImageView(act);
        previewImage.setAdjustViewBounds(true);
        previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImage.setBackground(Ui.shape(act, t.surfaceAlt, 12));
        previewImage.setClipToOutline(true);
        LinearLayout.LayoutParams pip = new LinearLayout.LayoutParams(Ui.dp(act, 64), Ui.dp(act, 64));
        previewRow.addView(previewImage, pip);

        TextView remove = Ui.tv(act, "×", 15, t.textDim);
        remove.setGravity(Gravity.CENTER);
        remove.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 6), 0);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearPendingImage();
            }
        });
        previewRow.addView(remove);

        LinearLayout inputCol = Ui.col(act);
        inputCol.addView(previewRow, Ui.match());
        inputCol.addView(inputWrap, Ui.match());
        addView(inputCol, Ui.match());
    }

    private void pickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            i.setType("image/*");
            act.startActivityForResult(i, REQ_IMG);
        } catch (Exception e) {
            host.toast("打不开相册");
        }
    }

    /** 由 MainActivity 转发相册结果。 */
    public void onPickResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMG || resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            String path = ImageStore.save(act, uri);
            pendingImage = path;
            Bitmap b = ImageStore.thumb(path, 240);
            if (b != null) previewImage.setImageBitmap(b);
            previewRow.setVisibility(VISIBLE);
        } catch (Exception e) {
            host.toast("读取图片失败：" + e.getMessage());
        }
    }

    private void clearPendingImage() {
        if (pendingImage != null) ImageStore.delete(pendingImage);
        pendingImage = null;
        previewImage.setImageDrawable(null);
        previewRow.setVisibility(GONE);
    }

    // ------------------------------------------------------------------ 渲染

    public void refresh() {
        modelChip.setText(store.primaryModelName());
        updateSendState();
    }

    private void render() {
        list.removeAllViews();
        emptyShowing = false;
        if (msgs.isEmpty()) {
            list.addView(emptyState());
            emptyShowing = true;
        } else {
            for (ChatMessage m : msgs) {
                list.addView(makeBubble(m, false).row);
            }
        }
        scrollDown();
    }

    private void appendMessage(ChatMessage m) {
        if (emptyShowing) {
            list.removeAllViews();
            emptyShowing = false;
        }
        list.addView(makeBubble(m, false).row);
        scrollDown();
    }

    private View emptyState() {
        LinearLayout box = Ui.col(act);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.padDp(act, box, 4, 46, 4, 12);

        TextView logo = Ui.bold(act, "OpenClaw", 30, t.accent);
        logo.setGravity(Gravity.CENTER);
        box.addView(logo);

        TextView desc = Ui.tv(act, "安卓上的私人 AI 助理\n填好 API Key 就能对话，回复可推到微信", 13, t.textDim);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(Ui.dp(act, 5), 1f);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.topMargin = Ui.dp(act, 10);
        dp.bottomMargin = Ui.dp(act, 22);
        box.addView(desc, dp);

        box.addView(quickCard("①  添加模型 API", "OpenAI 兼容 / Claude / Gemini，国内各家都能填", 1));
        box.addView(quickCard("②  调整模型顺序", "顺序即优先级，失败自动切换到下一个", 1));
        box.addView(quickCard("③  发到微信", "一条消息直接分享进微信会话，或接机器人自动推", 3));
        return box;
    }

    private View quickCard(String title, String sub, final int tab) {
        LinearLayout card = Ui.card(act, t);
        card.addView(Ui.bold(act, title, 14.5f, t.text));
        TextView s = Ui.tv(act, sub, 12, t.textDim);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(act, 3);
        card.addView(s, sp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 9);
        card.setLayoutParams(lp);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.goTab(tab);
            }
        });
        return card;
    }

    private Bubble makeBubble(ChatMessage msg, boolean streaming) {
        Bubble b = new Bubble();

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(HORIZONTAL);
        row.setGravity(msg.role == ChatMessage.ROLE_USER ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(act, 4);
        rlp.bottomMargin = Ui.dp(act, 4);
        row.setLayoutParams(rlp);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 图片（如果有）
        if (msg.imagePath != null) {
            Bitmap bmp = ImageStore.thumb(msg.imagePath, 640);
            if (bmp != null) {
                ImageView iv = new ImageView(act);
                iv.setImageBitmap(bmp);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setMaxWidth((int) (Ui.screenW(act) * 0.62f));
                iv.setBackground(Ui.shape(act, t.surfaceAlt, 14));
                iv.setClipToOutline(true);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ip.bottomMargin = Ui.dp(act, 6);
                col.addView(iv, ip);
            }
        }

        TextView body = new TextView(act);
        body.setText(msg.content);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setLineSpacing(Ui.dp(act, 4), 1f);
        body.setTextIsSelectable(!streaming);
        body.setPadding(Ui.dp(act, 14), Ui.dp(act, 10), Ui.dp(act, 14), Ui.dp(act, 10));
        body.setMaxWidth((int) (Ui.screenW(act) * 0.84f));

        if (msg.role == ChatMessage.ROLE_USER) {
            GradientDrawable bg = Ui.shape(act, t.accent, 18);
            applyRadii(bg, 18, 6, 18, 18);
            body.setBackground(bg);
            body.setTextColor(t.onAccent);
        } else if (msg.error) {
            body.setBackground(Ui.shape(act, t.surface, 18, t.danger, 1));
            body.setTextColor(t.danger);
        } else {
            GradientDrawable bg = Ui.shape(act, t.surface, 18, t.line, 1);
            applyRadii(bg, 6, 18, 18, 18);
            body.setBackground(bg);
            body.setTextColor(t.text);
        }
        col.addView(body);

        TextView footer = Ui.tv(act, "", 10.5f, t.textDim);
        footer.setPadding(Ui.dp(act, 4), Ui.dp(act, 5), Ui.dp(act, 4), 0);
        footer.setVisibility(GONE);
        col.addView(footer);

        if (msg.error) {
            TextView retry = Ui.btnGhost(act, t, "重试");
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = Ui.dp(act, 6);
            retry.setLayoutParams(rp);
            retry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    retryLast();
                }
            });
            col.addView(retry);
        }

        TextView share = null;
        if (msg.role == ChatMessage.ROLE_ASSISTANT && !msg.error) {
            share = Ui.tv(act, "分享到微信", 10.5f, t.accent);
            share.setPadding(Ui.dp(act, 4), Ui.dp(act, 5), Ui.dp(act, 4), 0);
            share.setVisibility(msg.content != null && !msg.content.isEmpty() ? VISIBLE : GONE);
            final ChatMessage ref = msg;
            share.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.shareToWeChat(ref.content);
                }
            });
            col.addView(share);
        }

        row.addView(col);
        b.row = row;
        b.body = body;
        b.footer = footer;
        b.share = share;

        if (msg.role == ChatMessage.ROLE_ASSISTANT && msg.model != null) {
            footer.setText(footText(msg));
            footer.setVisibility(VISIBLE);
        }
        return b;
    }

    private void applyRadii(GradientDrawable d, float tl, float tr, float br, float bl) {
        float a = Ui.dp(act, tl);
        float b = Ui.dp(act, tr);
        float c = Ui.dp(act, br);
        float e = Ui.dp(act, bl);
        d.setCornerRadii(new float[]{a, a, b, b, c, c, e, e});
    }

    private String footText(ChatMessage m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.model == null ? "模型" : m.model);
        int tk = m.totalTokens > 0 ? m.totalTokens : m.promptTokens + m.completionTokens;
        if (tk > 0) {
            sb.append("   ·   ");
            if (m.estimated) sb.append("≈ ");
            sb.append("↑").append(m.promptTokens)
                    .append("  ↓").append(m.completionTokens)
                    .append("  = ").append(tk).append(" tokens");
        }
        return sb.toString();
    }

    private void addNote(String text) {
        TextView note = Ui.tv(act, text, 11.5f, t.textDim);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 6);
        lp.bottomMargin = Ui.dp(act, 6);
        note.setLayoutParams(lp);
        note.setPadding(Ui.dp(act, 12), Ui.dp(act, 7), Ui.dp(act, 12), Ui.dp(act, 7));
        list.addView(note);
        scrollDown();
    }

    private void scrollDown() {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // ------------------------------------------------------------------ 交互

    private void updateSendState() {
        if (busy) {
            sendBtn.setText("■");
            sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            sendBtn.setBackground(Ui.ripple(Ui.shape(act, t.danger, 999), 0x33FFFFFF));
        } else {
            sendBtn.setText("↑");
            sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            sendBtn.setBackground(Ui.ripple(Ui.shape(act, t.accent, 999), 0x33FFFFFF));
        }
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (busy) return;
        if (text.isEmpty() && pendingImage == null) return;
        if (store.chain().isEmpty()) {
            host.toast("请先在「模型」页填写 API Key");
            host.goTab(1);
            return;
        }
        input.setText("");
        ChatMessage user = new ChatMessage(ChatMessage.ROLE_USER,
                text.isEmpty() ? "请看这张图片" : text);
        user.imagePath = pendingImage;
        pendingImage = null;
        if (previewRow != null) {
            previewRow.setVisibility(GONE);
            previewImage.setImageDrawable(null);
        }
        msgs.add(user);
        store.saveHistory(msgs);
        appendMessage(user);
        startRequest();
    }

    private void startRequest() {
        final List<ModelConfig> chain = store.chain();
        if (chain.isEmpty()) {
            host.toast("没有可用模型");
            return;
        }
        final Store.Settings s = store.settings();
        busy = true;
        updateSendState();
        stopBtn.setVisibility(VISIBLE);

        final ApiClient api = new ApiClient();
        client = api;
        curAssistant = null;
        curBubble = null;
        buffer.setLength(0);
        lastReasoningAt = 0;

        final List<ChatMessage> ctx = new ArrayList<>(msgs);
        final ApiClient.Listener listener = new ApiClient.Listener() {
            @Override
            public void onStart(final ModelConfig m) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        ChatMessage a = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "");
                        a.model = m.name;
                        curAssistant = a;
                        buffer.setLength(0);
                        if (emptyShowing) {
                            list.removeAllViews();
                            emptyShowing = false;
                        }
                        curBubble = makeBubble(a, true);
                        list.addView(curBubble.row);
                        curBubble.footer.setText(m.name + " · 正在生成…");
                        curBubble.footer.setVisibility(VISIBLE);
                        scrollDown();
                    }
                });
            }

            @Override
            public void onDelta(final String chunk) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        buffer.append(chunk);
                        if (!flushScheduled) {
                            flushScheduled = true;
                            ui.postDelayed(flush, 40);
                        }
                    }
                });
            }

            @Override
            public void onReasoning(final int chars) {
                long now = System.currentTimeMillis();
                if (now - lastReasoningAt < 150) return;
                lastReasoningAt = now;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (curBubble != null && buffer.length() == 0 && curAssistant != null) {
                            curBubble.footer.setText(curAssistant.model + " · 思考中… 已推理 " + chars + " 字");
                            curBubble.footer.setVisibility(VISIBLE);
                        }
                    }
                });
            }

            @Override
            public void onDone(final ModelConfig m, final Usage u, final String full) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (curAssistant == null) return;
                        ChatMessage a = curAssistant;
                        a.content = full;
                        a.model = m.name;
                        a.promptTokens = u.prompt;
                        a.completionTokens = u.completion;
                        a.totalTokens = u.total > 0 ? u.total : u.prompt + u.completion;
                        a.estimated = u.estimated;
                        if (curBubble != null) {
                            curBubble.body.setText(full.isEmpty() ? "（模型返回了空内容）" : full);
                            curBubble.body.setTextIsSelectable(true);
                            curBubble.footer.setText(footText(a));
                            curBubble.footer.setVisibility(VISIBLE);
                            if (curBubble.share != null) {
                                curBubble.share.setVisibility(full.isEmpty() ? GONE : VISIBLE);
                            }
                        }
                        msgs.add(a);
                        store.saveHistory(msgs);

                        store.addUsage(m.name, u, true);
                        host.refreshHeader();

                        finishBusy();
                        if (store.settings().wcAuto) pushWeChat(full);
                        scrollDown();
                    }
                });
            }

            @Override
            public void onFailover(final ModelConfig m, final String reason) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (curBubble != null) {
                            list.removeView(curBubble.row);
                            curBubble = null;
                        }
                        curAssistant = null;
                        addNote("「" + m.name + "」调用失败：" + reason);
                    }
                });
            }

            @Override
            public void onAllFailed(final String reason) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (curBubble != null) {
                            list.removeView(curBubble.row);
                            curBubble = null;
                        }
                        curAssistant = null;
                        ChatMessage err = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "调用失败\n" + reason);
                        err.error = true;
                        err.model = "系统";
                        msgs.add(err);
                        store.saveHistory(msgs);
                        appendMessage(err);
                        finishBusy();
                    }
                });
            }

            @Override
            public void onCancelled() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (curBubble != null) {
                            if (curAssistant != null && buffer.length() > 0) {
                                curAssistant.content = buffer.toString();
                                curAssistant.model = curAssistant.model + "（已停止）";
                                curBubble.body.setText(curAssistant.content);
                                curBubble.body.setTextIsSelectable(true);
                                msgs.add(curAssistant);
                                store.saveHistory(msgs);
                            } else {
                                list.removeView(curBubble.row);
                            }
                        }
                        finishBusy();
                    }
                });
            }
        };

        new Thread(new Runnable() {
            @Override
            public void run() {
                api.run(chain, ctx, s, listener);
            }
        }, "openclaw-request").start();
    }

    private void finishBusy() {
        busy = false;
        curBubble = null;
        curAssistant = null;
        stopBtn.setVisibility(GONE);
        updateSendState();
    }

    private void stop() {
        if (client != null) client.cancel();
        stopBtn.setVisibility(GONE);
    }

    private void retryLast() {
        if (busy) return;
        int idx = -1;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).role == ChatMessage.ROLE_USER) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        while (msgs.size() > idx + 1) msgs.remove(msgs.size() - 1);
        store.saveHistory(msgs);
        render();
        startRequest();
    }

    private void confirmClear() {
        new AlertDialog.Builder(act)
                .setTitle("清空对话")
                .setMessage("会删除本机保存的全部聊天记录（不会影响 Token 统计）。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        msgs.clear();
                        store.clearHistory();
                        render();
                    }
                })
                .show();
    }

    private void pushWeChat(final String text) {
        final Store.Settings s = store.settings();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String err = WeChat.push(s, text);
                if (err != null) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            addNote("微信推送失败：" + err);
                        }
                    });
                }
            }
        }, "openclaw-wechat").start();
    }
}
