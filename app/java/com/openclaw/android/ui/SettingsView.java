package com.openclaw.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.openclaw.android.core.Store;
import com.openclaw.android.core.WeChat;

/** 设置页。参数用滑轨档位，开关也是两段滑轨。 */
public class SettingsView extends ScrollView {

    // 温度档位（模型跑云端，往大了给）
    static final double[] TEMP_VALUES = {0.3, 0.7, 1.1, 1.5, 2.0};
    static final String[] TEMP_LABELS = {"0.3", "0.7", "1.1", "1.5", "2.0"};

    // 最大输出 tokens
    static final int[] MAX_VALUES = {2048, 8192, 32768, 65536, 131072};
    static final String[] MAX_LABELS = {"2K", "8K", "32K", "64K", "128K"};

    // 上下文条数
    static final int[] CTX_VALUES = {10, 20, 40, 60, 100};
    static final String[] CTX_LABELS = {"10", "20", "40", "60", "100"};

    private final Activity act;
    private final Host host;
    private final Store store;
    private final Theme t;
    private Store.Settings s;

    private EditText etSystem;

    private boolean loading = true;

    public SettingsView(Activity act, Host host, Theme t) {
        super(act);
        this.act = act;
        this.host = host;
        this.store = host.store();
        this.t = t;
        this.s = store.settings();
        // 把已有数值对齐到档位，避免显示与实际不一致
        s.temperature = TEMP_VALUES[nearestTemp(s.temperature)];
        s.maxTokens = MAX_VALUES[nearestInt(MAX_VALUES, s.maxTokens)];
        s.contextLimit = CTX_VALUES[nearestInt(CTX_VALUES, s.contextLimit)];
        store.saveSettings(s);
        setBackgroundColor(t.bg);
        setVerticalScrollBarEnabled(false);
        build();
        loading = false;
    }

    private void build() {
        removeAllViews();
        LinearLayout root = Ui.col(act);
        Ui.padDp(act, root, 16, 8, 16, 28);

        // ---------------- 生成参数
        LinearLayout gen = Ui.card(act, t);
        gen.addView(Ui.bold(act, "生成参数", 14, t.text));

        gen.addView(tag("系统提示词"));
        etSystem = multi(s.systemPrompt);
        gen.addView(etSystem, Ui.match());

        gen.addView(railBlock("温度", TEMP_LABELS, nearestTemp(s.temperature), new Pick() {
            @Override
            public void onPick(int i) {
                s.temperature = TEMP_VALUES[i];
                save();
            }
        }));
        gen.addView(railBlock("最大输出 tokens", MAX_LABELS, nearestInt(MAX_VALUES, s.maxTokens), new Pick() {
            @Override
            public void onPick(int i) {
                s.maxTokens = MAX_VALUES[i];
                save();
            }
        }));
        gen.addView(railBlock("上下文条数", CTX_LABELS, nearestInt(CTX_VALUES, s.contextLimit), new Pick() {
            @Override
            public void onPick(int i) {
                s.contextLimit = CTX_VALUES[i];
                save();
            }
        }));

        TextView note = Ui.tv(act,
                "模型跑在云端，这几个值不占本机资源，可以放心拉大。"
                        + "如果服务商不支持这么大的 max_tokens 会返回 400，往下调一档即可。",
                11, t.textDim);
        note.setLineSpacing(Ui.dp(act, 3), 1f);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = Ui.dp(act, 10);
        gen.addView(note, np);

        gen.addView(switchRow("流式输出", "边生成边显示", s.stream, new OnToggle() {
            @Override
            public void onToggle(boolean v) {
                s.stream = v;
                save();
            }
        }));
        gen.addView(switchRow("失败自动切换", "报错就换下一个模型", s.autoFailover, new OnToggle() {
            @Override
            public void onToggle(boolean v) {
                s.autoFailover = v;
                save();
            }
        }));
        root.addView(gen);

        // ---------------- 数据
        LinearLayout data = Ui.card(act, t);
        LinearLayout.LayoutParams dap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dap.topMargin = Ui.dp(act, 12);
        data.setLayoutParams(dap);
        data.addView(Ui.bold(act, "数据", 14, t.text));
        TextView tip = Ui.tv(act, "所有 API Key 只保存在本机，不会上传到任何服务器。", 11.5f, t.textDim);
        LinearLayout.LayoutParams tipP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipP.topMargin = Ui.dp(act, 5);
        data.addView(tip, tipP);

        TextView clearStats = Ui.btnGhost(act, t, "清空 Token 统计");
        LinearLayout.LayoutParams csp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        csp.topMargin = Ui.dp(act, 12);
        clearStats.setLayoutParams(csp);
        clearStats.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(act)
                        .setTitle("清空统计")
                        .setMessage("确定清零累计 Token 与调用记录？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                com.openclaw.android.core.UsageStats st = store.stats();
                                st.reset();
                                store.saveStats(st);
                                host.refreshHeader();
                                host.toast("已清零");
                            }
                        })
                        .show();
            }
        });
        data.addView(clearStats);
        root.addView(data);

        // ---------------- 关于
        LinearLayout about = Ui.card(act, t);
        LinearLayout.LayoutParams abp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abp.topMargin = Ui.dp(act, 12);
        about.setLayoutParams(abp);
        about.addView(Ui.bold(act, "关于 OpenClaw", 14, t.text));
        TextView info = Ui.tv(act,
                "版本 1.0.0（Android 10+ / 鸿蒙 4.2 及以下）\n"
                        + "本应用是开源项目 OpenClaw 的安卓客户端实现，只做两件事：把你的 API 接到手机上，把回复送到微信。\n"
                        + "不注入微信、不读取通知、不申请无障碍权限。",
                11.5f, t.textDim);
        info.setLineSpacing(Ui.dp(act, 4), 1f);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = Ui.dp(act, 6);
        about.addView(info, ip);
        root.addView(about);

        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------------ 组件

    private interface OnToggle {
        void onToggle(boolean v);
    }

    private interface Pick {
        void onPick(int index);
    }

    /** 标题 + 当前值 + 滑轨档位。 */
    private View railBlock(String title, final String[] labels, int index, final Pick pick) {
        LinearLayout col = Ui.col(act);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(act, 14);
        col.setLayoutParams(cp);

        LinearLayout head = Ui.row(act);
        head.addView(Ui.tv(act, title, 13.5f, t.text),
                Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = Ui.bold(act, labels[index], 12.5f, t.accent);
        head.addView(value);
        col.addView(head, Ui.match());

        SegmentedRail rail = new SegmentedRail(act, t, labels, index);
        rail.setOnSelectListener(new SegmentedRail.OnSelect() {
            @Override
            public void onSelect(int i) {
                value.setText(labels[i]);
                pick.onPick(i);
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 34));
        rp.topMargin = Ui.dp(act, 8);
        col.addView(rail, rp);
        return col;
    }

    /** 开关也做成两段滑轨。 */
    private View switchRow(String title, String sub, boolean checked, final OnToggle cb) {
        LinearLayout row = Ui.row(act);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(act, 14);
        row.setLayoutParams(rp);

        LinearLayout col = Ui.col(act);
        col.addView(Ui.tv(act, title, 13.5f, t.text));
        TextView s2 = Ui.tv(act, sub, 11, t.textDim);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(act, 1);
        col.addView(s2, sp);
        row.addView(col, Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SegmentedRail rail = new SegmentedRail(act, t, new String[]{"关", "开"}, checked ? 1 : 0);
        rail.setOnSelectListener(new SegmentedRail.OnSelect() {
            @Override
            public void onSelect(int index) {
                cb.onToggle(index == 1);
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(Ui.dp(act, 84), Ui.dp(act, 32));
        rlp.leftMargin = Ui.dp(act, 10);
        row.addView(rail, rlp);
        return row;
    }

    private TextView tag(String text) {
        TextView v = Ui.tv(act, text, 11.5f, t.textDim);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 12);
        lp.bottomMargin = Ui.dp(act, 4);
        v.setLayoutParams(lp);
        return v;
    }

    private EditText multi(String value) {
        EditText e = new EditText(act);
        e.setText(value);
        e.setTextColor(t.text);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setMinLines(3);
        e.setMaxLines(6);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setBackground(Ui.shape(act, t.surfaceAlt, 12, t.line, 1));
        e.setPadding(Ui.dp(act, 12), Ui.dp(act, 9), Ui.dp(act, 12), Ui.dp(act, 9));
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s1, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s1, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable ed) {
                if (loading) return;
                s.systemPrompt = ed.toString();
                save();
            }
        });
        return e;
    }

    private static int nearestTemp(double v) {
        int best = 0;
        double diff = Double.MAX_VALUE;
        for (int i = 0; i < TEMP_VALUES.length; i++) {
            double d = Math.abs(TEMP_VALUES[i] - v);
            if (d < diff) {
                diff = d;
                best = i;
            }
        }
        return best;
    }

    private static int nearestInt(int[] values, int v) {
        int best = 0;
        int diff = Integer.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            int d = Math.abs(values[i] - v);
            if (d < diff) {
                diff = d;
                best = i;
            }
        }
        return best;
    }

    private void save() {
        if (loading) return;
        store.saveSettings(s);
    }
}
