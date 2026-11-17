package com.openclaw.android.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 一坨用来在代码里拼界面的小工具。 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static int screenW(Context c) {
        return c.getResources().getDisplayMetrics().widthPixels;
    }

    public static GradientDrawable shape(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable shape(Context c, int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = shape(c, color, radiusDp);
        d.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return d;
    }

    public static RippleDrawable ripple(GradientDrawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    public static TextView tv(Context c, CharSequence text, float sizeSp, int color) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        v.setTextColor(color);
        return v;
    }

    public static TextView bold(Context c, CharSequence text, float sizeSp, int color) {
        TextView v = tv(c, text, sizeSp, color);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpw(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams match(int heightPx) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
    }

    public static void margin(View v, int l, int tp, int r, int b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) p).setMargins(l, tp, r, b);
            v.setLayoutParams(p);
        }
    }

    public static void marginDp(Context c, View v, float l, float tp, float r, float b) {
        margin(v, dp(c, l), dp(c, tp), dp(c, r), dp(c, b));
    }

    public static void padDp(Context c, View v, float l, float tp, float r, float b) {
        v.setPadding(dp(c, l), dp(c, tp), dp(c, r), dp(c, b));
    }

    /** 白底圆角卡片。 */
    public static LinearLayout card(Context c, Theme t) {
        LinearLayout card = col(c);
        card.setBackground(shape(c, t.surface, 18, t.line, 1));
        padDp(c, card, 16, 14, 16, 14);
        return card;
    }

    /** 文字按钮（胶囊形）。 */
    public static TextView btn(Context c, Theme t, String label, int bg, int fg, boolean bold) {
        TextView b = bold ? bold(c, label, 13.5f, fg) : tv(c, label, 13.5f, fg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9));
        b.setBackground(ripple(shape(c, bg, 999), 0x22000000));
        b.setClickable(true);
        return b;
    }

    /** 次要按钮：浅底描边。 */
    public static TextView btnGhost(Context c, Theme t, String label) {
        TextView b = tv(c, label, 13, t.text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        b.setBackground(ripple(shape(c, t.surfaceAlt, 999, t.line, 1), 0x18000000));
        b.setClickable(true);
        return b;
    }

    /** 小标签。 */
    public static TextView chip(Context c, Theme t, String label, int bg, int fg) {
        TextView v = tv(c, label, 11f, fg);
        v.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4));
        v.setBackground(shape(c, bg, 999));
        return v;
    }

    public static View spacer(Context c, float heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return v;
    }

    public static View hspacer(Context c, float widthDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, widthDp), ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    public static View flex(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    public static View divider(Context c, Theme t) {
        View v = new View(c);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        p.topMargin = dp(c, 12);
        p.bottomMargin = dp(c, 12);
        v.setLayoutParams(p);
        v.setBackgroundColor(t.line);
        return v;
    }

    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 24);
    }

    public static int navBarHeight(Context c) {
        int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 48);
    }

    public static String thousand(long v) {
        String s = Long.toString(Math.abs(v));
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            out.append(s.charAt(i));
            count++;
            if (count % 3 == 0 && i > 0) out.append(',');
        }
        if (v < 0) out.append('-');
        return out.reverse().toString();
    }
}
