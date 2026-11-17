package com.openclaw.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.openclaw.android.core.Store;
import com.openclaw.android.core.UsageStats;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** 用量页：Token 总览、按模型分布、最近调用。 */
public class UsageView extends ScrollView {

    private final Activity act;
    private final Host host;
    private final Store store;
    private final Theme t;

    public UsageView(Activity act, Host host, Theme t) {
        super(act);
        this.act = act;
        this.host = host;
        this.store = host.store();
        this.t = t;
        setBackgroundColor(t.bg);
        setVerticalScrollBarEnabled(false);
        build();
    }

    private void build() {
        removeAllViews();
        UsageStats st = store.stats();

        LinearLayout root = Ui.col(act);
        Ui.padDp(act, root, 16, 8, 16, 24);

        // 总览
        LinearLayout hero = Ui.card(act, t);
        hero.addView(Ui.tv(act, "累计 Token 消耗", 12, t.textDim));
        TextView big = Ui.bold(act, Ui.thousand(st.total), 34, t.text);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(act, 4);
        hero.addView(big, bp);

        LinearLayout cols = Ui.row(act);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(act, 14);
        cols.setLayoutParams(cp);
        cols.addView(stat("输入", Ui.thousand(st.prompt), t.text), Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cols.addView(stat("输出", Ui.thousand(st.completion), t.text), Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cols.addView(stat("请求次数", Ui.thousand(st.requests), t.text), Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(cols);

        TextView rule = Ui.tv(act,
                "合计 = 输入 + 输出。输入里包含每轮重发的历史上下文，所以聊得越久，输入涨得越快——这是计费口径，不是重复计数。",
                10.5f, t.textDim);
        rule.setLineSpacing(Ui.dp(act, 3), 1f);
        LinearLayout.LayoutParams rulep = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rulep.topMargin = Ui.dp(act, 10);
        hero.addView(rule, rulep);

        if (st.anyEstimated) {
            TextView est = Ui.tv(act, "含估算值：部分服务商未返回 usage，按字符数折算（带 ≈ 标记）。", 10.5f, t.textDim);
            est.setLineSpacing(Ui.dp(act, 3), 1f);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ep.topMargin = Ui.dp(act, 10);
            hero.addView(est, ep);
        }
        root.addView(hero);

        // 按模型
        LinearLayout byModel = Ui.card(act, t);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = Ui.dp(act, 12);
        byModel.setLayoutParams(mp);
        byModel.addView(Ui.bold(act, "按模型分布", 14, t.text));

        if (st.byModel.isEmpty()) {
            TextView empty = Ui.tv(act, "还没有调用记录", 12, t.textDim);
            Ui.padDp(act, empty, 0, 12, 0, 2);
            byModel.addView(empty);
        } else {
            int max = 1;
            for (int[] v : st.byModel.values()) max = Math.max(max, v[0] + v[1]);
            for (Map.Entry<String, int[]> e : st.byModel.entrySet()) {
                byModel.addView(modelRow(e.getKey(), e.getValue(), max));
            }
        }
        root.addView(byModel);

        // 最近调用
        LinearLayout logs = Ui.card(act, t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 12);
        logs.setLayoutParams(lp);
        logs.addView(Ui.bold(act, "最近调用", 14, t.text));

        if (st.log.isEmpty()) {
            TextView empty = Ui.tv(act, "还没有调用记录", 12, t.textDim);
            Ui.padDp(act, empty, 0, 12, 0, 2);
            logs.addView(empty);
        } else {
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            int shown = Math.min(st.log.size(), 20);
            for (int i = 0; i < shown; i++) {
                UsageStats.LogItem it = st.log.get(i);
                logs.addView(logRow(it, f.format(new Date(it.time))));
            }
        }
        root.addView(logs);

        // 重置
        TextView reset = Ui.btn(act, t, "重置统计", t.surface, t.danger, true);
        reset.setBackground(Ui.ripple(Ui.shape(act, t.surface, 999, t.line, 1), 0x18000000));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(act, 14);
        reset.setLayoutParams(rp);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        root.addView(reset);

        TextView foot = Ui.tv(act, "Token 数据来自接口返回的 usage 字段，仅统计本机发起的调用。", 10.5f, t.textDim);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = Ui.dp(act, 12);
        root.addView(foot, fp);

        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View stat(String label, String value, int color) {
        LinearLayout box = Ui.col(act);
        box.addView(Ui.bold(act, value, 17, color));
        TextView l = Ui.tv(act, label, 11, t.textDim);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 2);
        box.addView(l, lp);
        return box;
    }

    private View modelRow(String name, int[] v, int max) {
        LinearLayout box = Ui.col(act);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(act, 12);
        box.setLayoutParams(bp);

        LinearLayout head = Ui.row(act);
        TextView n = Ui.tv(act, name, 12.5f, t.text);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(n, Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.tv(act, Ui.thousand(v[0] + v[1]) + " tokens · " + v[2] + " 次", 11, t.textDim));
        box.addView(head);

        LinearLayout track = new LinearLayout(act);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(Ui.shape(act, t.line, 999));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 7));
        tp.topMargin = Ui.dp(act, 6);
        track.setLayoutParams(tp);

        float ratio = (float) (v[0] + v[1]) / (float) max;
        ratio = Math.max(0.03f, Math.min(1f, ratio));
        View fill = new View(act);
        fill.setBackground(Ui.shape(act, t.accent, 999));
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ratio));
        View rest = new View(act);
        track.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0f, 1f - ratio)));
        box.addView(track);
        return box;
    }

    private View logRow(UsageStats.LogItem it, String time) {
        LinearLayout row = Ui.row(act);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(act, 11);
        row.setLayoutParams(rp);

        View dot = new View(act);
        dot.setBackground(Ui.shape(act, it.ok ? t.ok : t.danger, 999));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(act, 7), Ui.dp(act, 7));
        dp.rightMargin = Ui.dp(act, 9);
        dot.setLayoutParams(dp);
        row.addView(dot);

        LinearLayout mid = Ui.col(act);
        TextView m = Ui.tv(act, it.model, 12.5f, t.text);
        m.setMaxLines(1);
        m.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(m);
        mid.addView(Ui.tv(act, time, 10.5f, t.textDim));
        row.addView(mid, Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(Ui.tv(act, "↑" + it.prompt + "  ↓" + it.completion, 11, t.textDim));
        return row;
    }

    private void confirmReset() {
        new AlertDialog.Builder(act)
                .setTitle("重置统计")
                .setMessage("会清零累计 Token 与调用记录，聊天记录不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        UsageStats st = store.stats();
                        st.reset();
                        store.saveStats(st);
                        host.refreshHeader();
                        build();
                    }
                })
                .show();
    }
}
