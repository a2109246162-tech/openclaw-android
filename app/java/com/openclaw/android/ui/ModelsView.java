package com.openclaw.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.openclaw.android.core.ModelConfig;
import com.openclaw.android.core.Store;

import java.util.List;

/** 模型页：优先级顺序、启用状态、增删改。 */
public class ModelsView extends ScrollView {

    private final Activity act;
    private final Host host;
    private final Store store;
    private final Theme t;
    private LinearLayout root;

    public ModelsView(Activity act, Host host, Theme t) {
        super(act);
        this.act = act;
        this.host = host;
        this.store = host.store();
        this.t = t;
        setBackgroundColor(t.bg);
        setVerticalScrollBarEnabled(false);
        rebuild();
    }

    public void rebuild() {
        removeAllViews();
        root = Ui.col(act);
        Ui.padDp(act, root, 16, 8, 16, 24);

        LinearLayout head = Ui.card(act, t);
        head.addView(Ui.bold(act, "调用优先级", 15, t.text));
        TextView hint = Ui.tv(act,
                "列表从上到下就是调用顺序：第一个模型成功就返回；报错、超时、限流会自动切到下一个。用 ↑ ↓ 调整顺序。",
                12, t.textDim);
        hint.setLineSpacing(Ui.dp(act, 4), 1f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.dp(act, 6);
        head.addView(hint, hp);
        root.addView(head);

        TextView add = Ui.btn(act, t, "＋  添加模型", t.accent, t.onAccent, true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.dp(act, 12);
        ap.bottomMargin = Ui.dp(act, 4);
        add.setLayoutParams(ap);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(null);
            }
        });
        root.addView(add);

        List<ModelConfig> models = store.models();
        if (models.isEmpty()) {
            TextView empty = Ui.tv(act, "还没有模型，点上面的按钮添加一个。", 13, t.textDim);
            empty.setGravity(Gravity.CENTER);
            Ui.padDp(act, empty, 0, 30, 0, 30);
            root.addView(empty);
        }
        for (int i = 0; i < models.size(); i++) {
            root.addView(modelCard(models.get(i), i, models.size()));
        }

        TextView tip = Ui.tv(act, "支持 OpenAI 兼容接口（DeepSeek / 智谱 GLM / Kimi / 火山引擎豆包 / 通义千问 / 硅基流动 / OpenAI）、Anthropic Claude、Google Gemini。火山方舟的模型 ID 也可以填推理接入点 ep-xxxx。",
                11.5f, t.textDim);
        tip.setLineSpacing(Ui.dp(act, 3), 1f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.dp(act, 14);
        root.addView(tip, tp);

        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View modelCard(final ModelConfig m, final int index, final int total) {
        LinearLayout card = Ui.card(act, t);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(act, 10);
        card.setLayoutParams(clp);

        // 第一行：序号 + 名称 + 开关
        LinearLayout row1 = Ui.row(act);
        TextView badge = Ui.bold(act, String.valueOf(index + 1), 12, t.accent);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.shape(act, t.chip, 999));
        badge.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 22), Ui.dp(act, 22)));
        row1.addView(badge);
        row1.addView(Ui.hspacer(act, 9));

        TextView name = Ui.bold(act, m.name, 15f, t.text);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row1.addView(name, Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final SegmentedRail sw = new SegmentedRail(act, t, new String[]{"关", "开"}, m.enabled ? 1 : 0);
        sw.setOnSelectListener(new SegmentedRail.OnSelect() {
            @Override
            public void onSelect(int idx) {
                boolean checked = idx == 1;
                m.enabled = checked;
                List<ModelConfig> list = store.models();
                for (ModelConfig x : list) {
                    if (x.id.equals(m.id)) x.enabled = checked;
                }
                store.saveModels(list);
                host.refreshHeader();
            }
        });
        LinearLayout.LayoutParams swp = new LinearLayout.LayoutParams(Ui.dp(act, 84), Ui.dp(act, 32));
        swp.leftMargin = Ui.dp(act, 8);
        row1.addView(sw, swp);
        card.addView(row1);

        // 第二行：协议 · 模型 ID
        TextView sub = Ui.tv(act, m.protocolLabel() + "  ·  " + m.modelId, 11.5f, t.textDim);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(act, 5);
        sp.leftMargin = Ui.dp(act, 31);
        card.addView(sub, sp);

        // 第三行：密钥
        boolean hasKey = m.apiKey != null && m.apiKey.trim().length() > 0;
        TextView key = Ui.tv(act, hasKey ? m.maskedKey() : "未填写 API Key（点击编辑）", 11.5f,
                hasKey ? t.textDim : t.accent);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.topMargin = Ui.dp(act, 3);
        kp.leftMargin = Ui.dp(act, 31);
        card.addView(key, kp);

        // 操作行
        LinearLayout actions = Ui.row(act);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(act, 11);
        actions.setLayoutParams(alp);

        TextView up = Ui.btnGhost(act, t, "↑");
        TextView down = Ui.btnGhost(act, t, "↓");
        TextView top = Ui.btnGhost(act, t, "置顶");
        TextView edit = Ui.btnGhost(act, t, "编辑");
        TextView del = Ui.btnGhost(act, t, "删除");
        del.setTextColor(t.danger);

        up.setEnabled(index > 0);
        up.setAlpha(index > 0 ? 1f : 0.35f);
        down.setEnabled(index < total - 1);
        down.setAlpha(index < total - 1 ? 1f : 0.35f);

        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(index, index - 1);
            }
        });
        down.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(index, index + 1);
            }
        });
        top.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(index, 0);
            }
        });
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(m);
            }
        });
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete(m);
            }
        });

        actions.addView(up);
        actions.addView(Ui.hspacer(act, 6));
        actions.addView(down);
        if (index > 0) {
            actions.addView(Ui.hspacer(act, 6));
            actions.addView(top);
        }
        actions.addView(Ui.flex(act));
        actions.addView(edit);
        actions.addView(Ui.hspacer(act, 6));
        actions.addView(del);
        card.addView(actions);

        if (index == 0) {
            TextView primary = Ui.chip(act, t, "主模型", t.chip, t.accent);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pp.topMargin = Ui.dp(act, 9);
            pp.leftMargin = Ui.dp(act, 31);
            primary.setLayoutParams(pp);
            card.addView(primary);
        }
        return card;
    }

    private void move(int from, int to) {
        List<ModelConfig> list = store.models();
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size()) return;
        ModelConfig a = list.remove(from);
        list.add(to, a);
        store.saveModels(list);
        host.refreshHeader();
        rebuild();
    }

    private void confirmDelete(final ModelConfig m) {
        new AlertDialog.Builder(act)
                .setTitle("删除模型")
                .setMessage("确定删除「" + m.name + "」吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        List<ModelConfig> list = store.models();
                        for (int i = 0; i < list.size(); i++) {
                            if (list.get(i).id.equals(m.id)) {
                                list.remove(i);
                                break;
                            }
                        }
                        store.saveModels(list);
                        host.refreshHeader();
                        rebuild();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ 编辑器

    private void openEditor(final ModelConfig existing) {
        final Theme th = Theme.light();
        final ModelConfig m = existing == null ? new ModelConfig() : existing.copy();

        ScrollView sv = new ScrollView(act);
        LinearLayout box = Ui.col(act);
        Ui.padDp(act, box, 20, 14, 20, 4);

        box.addView(tag("快速填充预设", th));
        final Spinner preset = spinner(names());
        box.addView(preset, Ui.match());

        box.addView(tag("显示名称", th));
        final EditText etName = field("例如 DeepSeek", m.name, InputType.TYPE_CLASS_TEXT, th);
        box.addView(etName, Ui.match());

        box.addView(tag("协议", th));
        final Spinner proto = spinner(new String[]{"OpenAI 兼容", "Anthropic", "Gemini"});
        if (ModelConfig.P_ANTHROPIC.equals(m.protocol)) proto.setSelection(1);
        else if (ModelConfig.P_GEMINI.equals(m.protocol)) proto.setSelection(2);
        box.addView(proto, Ui.match());

        box.addView(tag("Base URL", th));
        final EditText etUrl = field("https://api.deepseek.com/v1", m.baseUrl, InputType.TYPE_TEXT_VARIATION_URI, th);
        box.addView(etUrl, Ui.match());

        box.addView(tag("模型 ID", th));
        final EditText etModel = field("deepseek-chat", m.modelId, InputType.TYPE_CLASS_TEXT, th);
        box.addView(etModel, Ui.match());

        box.addView(tag("API Key", th));
        final EditText etKey = field("sk-…", m.apiKey, InputType.TYPE_CLASS_TEXT, th);
        box.addView(etKey, Ui.match());

        LinearLayout swRow = Ui.row(act);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.topMargin = Ui.dp(act, 14);
        swRow.setLayoutParams(srp);
        swRow.addView(Ui.tv(act, "启用（参与调用链）", 13, th.text), Ui.lpw(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final SegmentedRail sw = new SegmentedRail(act, th, new String[]{"关", "开"}, m.enabled ? 1 : 0);
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(Ui.dp(act, 84), Ui.dp(act, 32));
        swRow.addView(sw, swlp);
        box.addView(swRow);

        preset.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position <= 0) return;
                String[] p = Store.Defaults.PRESETS[position];
                etName.setText(p[0]);
                if (ModelConfig.P_ANTHROPIC.equals(p[1])) proto.setSelection(1);
                else if (ModelConfig.P_GEMINI.equals(p[1])) proto.setSelection(2);
                else proto.setSelection(0);
                etUrl.setText(p[2]);
                etModel.setText(p[3]);
                etKey.requestFocus();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        sv.addView(box);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(existing == null ? "添加模型" : "编辑模型")
                .setView(sv)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = etName.getText().toString().trim();
                String url = etUrl.getText().toString().trim();
                String mid = etModel.getText().toString().trim();
                String key = etKey.getText().toString().trim();
                if (name.isEmpty()) {
                    host.toast("请填写显示名称");
                    return;
                }
                if (url.isEmpty() || mid.isEmpty()) {
                    host.toast("Base URL 和模型 ID 都要填");
                    return;
                }
                m.name = name;
                m.baseUrl = url;
                m.modelId = mid;
                m.apiKey = key;
                m.enabled = sw.getSelected() == 1;
                int p = proto.getSelectedItemPosition();
                m.protocol = p == 1 ? ModelConfig.P_ANTHROPIC : p == 2 ? ModelConfig.P_GEMINI : ModelConfig.P_OPENAI;

                List<ModelConfig> list = store.models();
                boolean replaced = false;
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).id.equals(m.id)) {
                        list.set(i, m);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    // 新加的模型放到最前面，立刻成为当前使用的模型
                    list.add(0, m);
                }
                store.saveModels(list);
                host.refreshHeader();
                rebuild();
                dlg.dismiss();
                if (!replaced) host.toast("已添加，并设为当前使用");
            }
        });
    }

    private String[] names() {
        String[] out = new String[Store.Defaults.PRESETS.length];
        for (int i = 0; i < out.length; i++) out[i] = Store.Defaults.PRESETS[i][0];
        return out;
    }

    private Spinner spinner(String[] items) {
        Spinner sp = new Spinner(act);
        ArrayAdapter<String> ad = new ArrayAdapter<>(act, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }

    private TextView tag(String text, Theme th) {
        TextView v = Ui.tv(act, text, 11.5f, th.textDim);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 12);
        lp.bottomMargin = Ui.dp(act, 5);
        v.setLayoutParams(lp);
        return v;
    }

    private EditText field(String hint, String value, int inputType, Theme th) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setText(value);
        e.setHintTextColor(th.textDim);
        e.setTextColor(th.text);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setBackground(Ui.shape(act, th.surfaceAlt, 12, th.line, 1));
        e.setPadding(Ui.dp(act, 12), Ui.dp(act, 9), Ui.dp(act, 12), Ui.dp(act, 9));
        return e;
    }
}
