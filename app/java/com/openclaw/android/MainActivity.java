package com.openclaw.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.openclaw.android.core.Store;
import com.openclaw.android.core.UsageStats;
import com.openclaw.android.ui.ChatView;
import com.openclaw.android.ui.Host;
import com.openclaw.android.ui.ModelsView;
import com.openclaw.android.ui.SettingsView;
import com.openclaw.android.ui.Theme;
import com.openclaw.android.ui.Ui;
import com.openclaw.android.ui.UsageView;
import com.openclaw.android.ui.WeChatView;

public class MainActivity extends Activity implements Host {

    private Store store;
    private Theme t;

    private FrameLayout content;
    private TextView tokenChip;
    private TextView subtitle;

    private final NavIcon[] icons = new NavIcon[5];
    private final TextView[] labels = new TextView[5];

    private ChatView chat;
    private SettingsView settings;
    private WeChatView weChatView;
    private int tab = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        t = Theme.of(this);

        getWindow().setStatusBarColor(t.bg);
        getWindow().setNavigationBarColor(t.bg);
        applySystemBars();

        buildUi();
        goTab(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 扫完码用户还在微信里，此时 App 处于后台，Android 12+ 不允许后台启动前台服务。
        // 所以每次回到前台都补一次启动，这是最可靠的时机。
        if (store == null) return;
        String token = store.clawBotToken();
        if (token != null && !token.isEmpty()
                && !com.openclaw.android.core.ClawBotService.isRunning()) {
            try {
                Intent i = new Intent(this, com.openclaw.android.core.ClawBotService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(i);
                } else {
                    startService(i);
                }
                store.log("回到前台，已拉起回复服务");
            } catch (Throwable e) {
                store.log("拉起服务失败：" + e.getClass().getSimpleName());
            }
        }
    }

    private void applySystemBars() {
        int flags = t.night ? 0
                : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bg);
        setContentView(root);

        // ---------------------------------------------------------- 顶部
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = Ui.bold(this, "OpenClaw", 23, t.text);
        title.setLetterSpacing(-0.02f);
        top.addView(title);
        top.addView(Ui.flex(this));

        tokenChip = new TextView(this);
        tokenChip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tokenChip.setTextColor(t.accent);
        tokenChip.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        tokenChip.setBackground(Ui.shape(this, t.chip, 999));
        tokenChip.setClickable(true);
        tokenChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goTab(2);
            }
        });
        top.addView(tokenChip);
        header.addView(top, Ui.match());

        subtitle = Ui.tv(this, "", 11.5f, t.textDim);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, 3);
        header.addView(subtitle, sp);
        root.addView(header, Ui.match());

        // ---------------------------------------------------------- 内容
        content = new FrameLayout(this);
        root.addView(content, Ui.lpw(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------------------------------------------------------- 底部导航
        View line = new View(this);
        line.setBackgroundColor(t.line);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setBackgroundColor(t.bg);
        navRow.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));

        String[] names = {"对话", "模型", "用量", "微信", "设置"};
        for (int i = 0; i < names.length; i++) {
            navRow.addView(navItem(i, names[i]));
        }
        root.addView(navRow, Ui.match());
    }

    private View navItem(final int index, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 8));
        item.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        item.setClickable(true);

        NavIcon icon = new NavIcon(this, index, t.textDim);
        item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));

        TextView lab = Ui.tv(this, label, 10.5f, t.textDim);
        lab.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 4);
        item.addView(lab, lp);

        icons[index] = icon;
        labels[index] = lab;

        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goTab(index);
            }
        });
        return item;
    }

    private void updateNav() {
        for (int i = 0; i < icons.length; i++) {
            boolean on = i == tab;
            if (icons[i] != null) icons[i].setColor(on ? t.accent : t.textDim);
            if (labels[i] != null) {
                labels[i].setTextColor(on ? t.accent : t.textDim);
                labels[i].setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            }
        }
    }

    // ---------------------------------------------------------- Host

    @Override
    public Store store() {
        return store;
    }

    @Override
    public void shareToWeChat(String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            i.setPackage("com.tencent.mm");
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "分享到"));
            } catch (Exception e2) {
                toast("没有可用的分享应用");
            }
        }
    }

    @Override
    public void goTab(int index) {
        if (index < 0 || index > 4) return;
        tab = index;
        content.removeAllViews();
        View v;
        switch (index) {
            case 0:
                if (chat == null) chat = new ChatView(this, this, t);
                chat.refresh();
                v = chat;
                break;
            case 1:
                v = new ModelsView(this, this, t);
                break;
            case 2:
                v = new UsageView(this, this, t);
                break;
            case 3:
                weChatView = new WeChatView(this, this, t);
                v = weChatView;
                break;
            default:
                settings = new SettingsView(this, this, t);
                v = settings;
                break;
        }
        if (v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
        content.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateNav();
        refreshHeader();
    }

    @Override
    public void refreshHeader() {
        if (tokenChip == null) return;
        UsageStats st = store.stats();
        tokenChip.setText(Ui.thousand(st.total) + " tokens");
        int ready = store.chain().size();
        subtitle.setText("主模型：" + store.primaryModelName() + "  ·  " + ready + " 个可用");
    }

    @Override
    public void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (chat != null) chat.onPickResult(requestCode, resultCode, data);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Theme nt = Theme.of(this);
        if (nt.night != t.night) {
            recreate();
        }
    }

    // ---------------------------------------------------------- 图标

    static class NavIcon extends View {

        private final int kind;
        private final float d;
        private int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        NavIcon(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            this.color = color;
            this.d = c.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.9f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
        }

        void setColor(int c) {
            if (this.color != c) {
                this.color = c;
                p.setColor(c);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float l = w * 0.15f;
            float r = w * 0.85f;
            float tp = h * 0.17f;
            float bt = h * 0.83f;

            if (kind == 0) {
                RectF rect = new RectF(l, tp, r, bt - h * 0.12f);
                canvas.drawRoundRect(rect, w * 0.22f, w * 0.22f, p);
                Path path = new Path();
                path.moveTo(l + w * 0.18f, rect.bottom - 1f);
                path.lineTo(l + w * 0.18f, bt + h * 0.05f);
                path.lineTo(l + w * 0.40f, rect.bottom - 1f);
                canvas.drawPath(path, p);
            } else if (kind == 1) {
                for (int i = 0; i < 3; i++) {
                    float inset = w * 0.055f * i;
                    float top = tp + h * 0.19f * i;
                    RectF rr = new RectF(l + inset, top, r - inset, top + h * 0.17f);
                    canvas.drawRoundRect(rr, w * 0.07f, w * 0.07f, p);
                }
            } else if (kind == 2) {
                float bw = (r - l) / 4.2f;
                float gap = (r - l - bw * 3f) / 2f;
                float[] hs = {0.42f, 0.66f, 1f};
                for (int i = 0; i < 3; i++) {
                    float x = l + i * (bw + gap);
                    float barH = (bt - tp) * hs[i] * 0.92f;
                    RectF rr = new RectF(x, bt - barH, x + bw, bt);
                    canvas.drawRoundRect(rr, bw * 0.34f, bw * 0.34f, p);
                }
            } else if (kind == 3) {
                float s = (r - l) * 0.36f;
                canvas.drawRoundRect(new RectF(l, tp, l + s, tp + s), s * 0.3f, s * 0.3f, p);
                canvas.drawRoundRect(new RectF(r - s, tp, r, tp + s), s * 0.3f, s * 0.3f, p);
                canvas.drawRoundRect(new RectF(l, bt - s, l + s, bt), s * 0.3f, s * 0.3f, p);
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(color);
                canvas.drawRect(r - s * 0.62f, bt - s * 0.62f, r, bt, fill);
            } else {
                float[] ys = {tp + h * 0.12f, h * 0.5f, bt - h * 0.12f};
                float[] xs = {l + (r - l) * 0.66f, l + (r - l) * 0.28f, l + (r - l) * 0.54f};
                for (int i = 0; i < 3; i++) {
                    canvas.drawLine(l, ys[i], r, ys[i], p);
                }
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(color);
                for (int i = 0; i < 3; i++) {
                    canvas.drawCircle(xs[i], ys[i], 2.5f * d, fill);
                }
            }
        }
    }
}
