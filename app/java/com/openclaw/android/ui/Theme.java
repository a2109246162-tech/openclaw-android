package com.openclaw.android.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/** 应用配色，跟随系统深浅色。 */
public final class Theme {

    public final boolean night;
    public final int bg;
    public final int surface;
    public final int surfaceAlt;
    public final int line;
    public final int text;
    public final int textDim;
    public final int accent;
    public final int onAccent;
    public final int chip;
    public final int ok;
    public final int danger;
    public final int warn;

    private Theme(boolean night) {
        this.night = night;
        if (night) {
            bg = Color.parseColor("#0C0F13");
            surface = Color.parseColor("#151A20");
            surfaceAlt = Color.parseColor("#1A2028");
            line = Color.parseColor("#232B34");
            text = Color.parseColor("#E8EEF5");
            textDim = Color.parseColor("#8A95A4");
            accent = Color.parseColor("#FF6B47");
            onAccent = Color.parseColor("#150A06");
            chip = Color.parseColor("#2B1B16");
            ok = Color.parseColor("#34D399");
            danger = Color.parseColor("#F87171");
            warn = Color.parseColor("#FBBF24");
        } else {
            bg = Color.parseColor("#F4F5F7");
            surface = Color.parseColor("#FFFFFF");
            surfaceAlt = Color.parseColor("#FAFAFB");
            line = Color.parseColor("#E4E7EC");
            text = Color.parseColor("#11151A");
            textDim = Color.parseColor("#6B7280");
            accent = Color.parseColor("#E8502E");
            onAccent = Color.parseColor("#FFFFFF");
            chip = Color.parseColor("#FCEAE4");
            ok = Color.parseColor("#16A34A");
            danger = Color.parseColor("#DC2626");
            warn = Color.parseColor("#D97706");
        }
    }

    /** 强制浅色（对话框内部使用，保证文字始终可读）。 */
    public static Theme light() {
        return new Theme(false);
    }

    public static Theme of(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return new Theme(mode == Configuration.UI_MODE_NIGHT_YES);
    }
}
