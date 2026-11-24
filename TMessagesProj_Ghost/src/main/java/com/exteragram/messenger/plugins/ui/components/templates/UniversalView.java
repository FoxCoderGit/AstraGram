package com.exteragram.messenger.plugins.ui.components.templates;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import org.telegram.messenger.Utilities;

/** View callbacks exposed to Python plugin renderers. */
public final class UniversalView extends View {
    private UniversalViewDelegate delegate;

    public UniversalView(Context context) { this(context, null); }

    public UniversalView(Context context, UniversalViewDelegate delegate) {
        super(context);
        this.delegate = delegate;
    }

    public UniversalViewDelegate getDelegate() { return delegate; }
    public void setDelegate(UniversalViewDelegate delegate) { this.delegate = delegate; }

    @Override protected void onDraw(Canvas canvas) {
        if (delegate == null) super.onDraw(canvas);
        else delegate.onDraw(canvas, super::onDraw);
    }

    @Override protected void onMeasure(int width, int height) {
        if (delegate == null) super.onMeasure(width, height);
        else delegate.onMeasure(width, height, super::onMeasure);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        return delegate == null ? super.onTouchEvent(event) : delegate.onTouchEvent(event, super::onTouchEvent);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        if (delegate == null) super.onInitializeAccessibilityNodeInfo(info);
        else delegate.onInitializeAccessibilityNodeInfo(info, super::onInitializeAccessibilityNodeInfo);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (delegate != null) delegate.onAttachedToWindow();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (delegate != null) delegate.onDetachedFromWindow();
    }

    public interface UniversalViewDelegate {
        default void onDraw(Canvas canvas, Utilities.Callback<Canvas> original) { original.run(canvas); }
        default void onMeasure(int width, int height, Utilities.Callback2<Integer, Integer> original) { original.run(width, height); }
        default boolean onTouchEvent(MotionEvent event, Utilities.CallbackReturn<MotionEvent, Boolean> original) { return original.run(event); }
        default void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info, Utilities.Callback<AccessibilityNodeInfo> original) { original.run(info); }
        default void onAttachedToWindow() {}
        default void onDetachedFromWindow() {}
    }
}
