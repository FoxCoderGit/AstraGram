package org.telegram.ui.Components;

import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GhostCalculator;
import org.telegram.ui.ActionBar.Theme;

/** Non-focusable suggestion above the composer: tap to append, never auto-send. */
public final class GhostCalculatorHint implements TextWatcher, View.OnAttachStateChangeListener {
    private final EditText editor;
    private final TextView label;
    private final PopupWindow window;
    private String source, answer;
    private ViewTreeObserver observed;
    private final ViewTreeObserver.OnWindowFocusChangeListener windowFocus = focused -> update();
    public static void attach(EditText editor, Theme.ResourcesProvider provider) { new GhostCalculatorHint(editor, provider); }
    private GhostCalculatorHint(EditText editor, Theme.ResourcesProvider provider) {
        this.editor = editor;
        label = new TextView(editor.getContext());
        label.setTextSize(16); label.setGravity(Gravity.CENTER);
        label.setPadding(dp(16), dp(8), dp(16), dp(8));
        label.setTextColor(Theme.getColor(Theme.key_dialogTextBlue, provider));
        label.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), Theme.getColor(Theme.key_dialogBackground, provider), Theme.getColor(Theme.key_listSelector, provider)));
        window = new PopupWindow(label, -2, -2, false);
        window.setElevation(dp(4)); window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        label.setOnClickListener(v -> {
            if (answer == null || !editor.getText().toString().equals(source)) return;
            String completed = source.trim() + " = " + answer;
            editor.setText(completed); editor.setSelection(completed.length()); window.dismiss();
        });
        editor.addTextChangedListener(this);
        editor.addOnAttachStateChangeListener(this);
        editor.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> update());
        View.OnFocusChangeListener previousFocus = editor.getOnFocusChangeListener();
        editor.setOnFocusChangeListener((v, focused) -> {
            if (previousFocus != null) previousFocus.onFocusChange(v, focused);
            update();
        });
        if (editor.isAttachedToWindow()) onViewAttachedToWindow(editor);
    }
    private int dp(int value) { return AndroidUtilities.dp(value); }
    private void update() {
        source = editor.getText().toString(); answer = GhostCalculator.calculateDraft(source);
        if (answer == null || !editor.hasFocus() || !editor.hasWindowFocus() || !editor.isAttachedToWindow() || !editor.isShown()) { window.dismiss(); return; }
        label.setText("= " + answer);
        label.setContentDescription("Добавить результат: " + answer);
        label.measure(View.MeasureSpec.makeMeasureSpec(dp(260), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] location = new int[2]; editor.getLocationOnScreen(location);
        Rect visible = new Rect(); editor.getWindowVisibleDisplayFrame(visible);
        int x = Math.max(visible.left + dp(8), Math.min(location[0], visible.right - label.getMeasuredWidth() - dp(8)));
        int y = Math.max(visible.top, location[1] - label.getMeasuredHeight() - dp(6));
        if (window.isShowing()) window.update(x, y, -1, -1);
        else window.showAtLocation(editor, Gravity.TOP | Gravity.LEFT, x, y);
    }
    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
    @Override public void afterTextChanged(Editable text) { editor.post(this::update); }
    @Override public void onViewAttachedToWindow(View view) {
        observed = editor.getViewTreeObserver();
        observed.addOnWindowFocusChangeListener(windowFocus);
        update();
    }
    @Override public void onViewDetachedFromWindow(View view) {
        if (observed != null && observed.isAlive()) observed.removeOnWindowFocusChangeListener(windowFocus);
        observed = null;
        window.dismiss();
    }
}
