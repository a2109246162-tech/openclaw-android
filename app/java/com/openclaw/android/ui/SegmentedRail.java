package com.openclaw.android.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * 滑轨式分段选择器（类似 Codex 里选推理强度那种）。
 * 一条轨道 + 一个会滑动的胶囊，用于开关和档位选择。
 */
public class SegmentedRail extends View {

    public interface OnSelect {
        void onSelect(int index);
    }

    private final Theme t;
    private final String[] labels;
    private final float d;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int selected;
    private float pos;
    private OnSelect listener;
    private ValueAnimator anim;
    private float downX;
    private boolean dragged;

    public SegmentedRail(Context c, Theme theme, String[] labels, int selected) {
        super(c);
        this.t = theme;
        this.labels = labels;
        this.d = c.getResources().getDisplayMetrics().density;
        this.selected = Math.max(0, Math.min(labels.length - 1, selected));
        this.pos = this.selected;

        textPaint.setTextSize(11.5f * c.getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        setClickable(true);
        setFocusable(true);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public int getSelected() {
        return selected;
    }

    public void setSelected(int index, boolean notify) {
        index = Math.max(0, Math.min(labels.length - 1, index));
        if (index == selected) return;
        selected = index;
        animateTo(index);
        if (notify && listener != null) listener.onSelect(index);
    }

    public void setOnSelectListener(OnSelect l) {
        listener = l;
    }

    private void animateTo(int index) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(pos, index);
        anim.setDuration(160);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int wMode = MeasureSpec.getMode(widthSpec);
        int wSize = MeasureSpec.getSize(widthSpec);
        int w = wMode == MeasureSpec.UNSPECIFIED ? Math.round(240 * d) : wSize;
        setMeasuredDimension(w, Math.round(34 * d));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;
        int n = labels.length;
        float segW = w / n;

        // 轨道
        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(t.surfaceAlt);
        canvas.drawRoundRect(new RectF(0, 0, w, h), r, r, trackPaint);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(Math.max(1f, d));
        trackPaint.setColor(t.line);
        canvas.drawRoundRect(new RectF(d / 2, d / 2, w - d / 2, h - d / 2), r, r, trackPaint);

        // 滑动胶囊
        float pad = 3 * d;
        float pillLeft = pos * segW + pad;
        float pillRight = pos * segW + segW - pad;
        float pr = (h - pad * 2) / 2f;
        pillPaint.setStyle(Paint.Style.FILL);
        pillPaint.setColor(t.accent);
        pillPaint.setShadowLayer(1.6f * d, 0, 0.8f * d, 0x33000000);
        canvas.drawRoundRect(new RectF(pillLeft, pad, pillRight, h - pad), pr, pr, pillPaint);

        // 文案
        float baseline = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f;
        for (int i = 0; i < n; i++) {
            textPaint.setColor(Math.abs(i - pos) < 0.5f ? t.onAccent : t.textDim);
            canvas.drawText(labels[i], (i + 0.5f) * segW, baseline, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setPressed(true);
                downX = e.getX();
                dragged = false;
                // 关键：告诉外层 ScrollView 别抢这次手势，否则轻微滑动就会被判定为滚动，
                // 开关表现为"点了没反应"
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > 20 * d) dragged = true;
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (!dragged) {
                    int n = labels.length;
                    float segW = getWidth() / (float) n;
                    int idx = (int) (e.getX() / segW);
                    setSelected(Math.max(0, Math.min(n - 1, idx)), true);
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
