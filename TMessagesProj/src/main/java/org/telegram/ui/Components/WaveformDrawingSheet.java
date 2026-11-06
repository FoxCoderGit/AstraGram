package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GhostMediaTools;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.WaveformPresets;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

/** A voice waveform is editable independently of its silent Opus payload. */
public class WaveformDrawingSheet extends BottomSheet {
    public interface Delegate { void send(int seconds, int[] levels, Consumer<Boolean> completion); }
    private final Theme.ResourcesProvider provider;
    private final WaveformView waveform;
    private final ActualWaveformView actualWaveform;
    private final EditTextBoldCursor duration;
    private final TextView preview;
    private final TextView send;
    private final LinearLayout tools;
    private final TextView templates;
    private boolean preparing;

    public WaveformDrawingSheet(Context context, Theme.ResourcesProvider provider, Delegate delegate) {
        super(context, true, provider);
        this.provider = provider;
        setTitle(LocaleController.getString(R.string.GhostWaveformTitle), true);
        setCanDismissWithSwipe(false);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(16));
        content.setFocusableInTouchMode(true);
        TextView help = label(context, LocaleController.getString(R.string.GhostWaveformHelp), 14, Theme.key_dialogTextGray);
        content.addView(help, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(10));
        panel.setBackground(Theme.createRoundRectDrawable(dp(12), color(Theme.key_dialogSearchBackground)));
        waveform = new WaveformView(context);
        TextView actualLabel = label(context, LocaleController.getString(R.string.GhostWaveformActualPreview), 13, Theme.key_dialogTextGray);
        actualLabel.setGravity(Gravity.CENTER);
        panel.addView(actualLabel, new LinearLayout.LayoutParams(-1, dp(24)));
        actualWaveform = new ActualWaveformView(context);
        panel.addView(actualWaveform, new LinearLayout.LayoutParams(-1, dp(36)));
        TextView editorHint = label(context, LocaleController.getString(R.string.GhostWaveformEditorHint), 12, Theme.key_dialogTextGray);
        editorHint.setGravity(Gravity.CENTER);
        panel.addView(editorHint, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(waveform, new LinearLayout.LayoutParams(-1, dp(88)));
        preview = label(context, "", 13, Theme.key_dialogTextGray);
        preview.setGravity(Gravity.CENTER);
        panel.addView(preview, new LinearLayout.LayoutParams(-1, dp(24)));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, -2);
        panelParams.topMargin = dp(18);
        content.addView(panel, panelParams);

        tools = new LinearLayout(context);
        addTool(context, R.string.GhostWaveformClear, () -> waveform.preset(false));
        addTool(context, R.string.GhostWaveformRandom, () -> waveform.preset(true));
        addTool(context, R.string.GhostWaveformUndo, waveform::undo);
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(-1, dp(44));
        toolsParams.topMargin = dp(6);
        content.addView(tools, toolsParams);

        templates = label(context, LocaleController.getString(R.string.GhostWaveformTemplates), 15, Theme.key_dialogTextBlue);
        templates.setGravity(Gravity.CENTER_VERTICAL);
        templates.setPadding(dp(12), 0, dp(12), 0);
        templates.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), color(Theme.key_dialogSearchBackground), color(Theme.key_listSelector)));
        templates.setOnClickListener(v -> showTemplates(context));
        LinearLayout.LayoutParams templateParams = new LinearLayout.LayoutParams(-1, dp(44));
        templateParams.topMargin = dp(8);
        content.addView(templates, templateParams);

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView durationLabel = label(context, LocaleController.getString(R.string.GhostWaveformDuration), 16, Theme.key_dialogTextBlack);
        row.addView(durationLabel, new LinearLayout.LayoutParams(0, dp(48), 1));
        durationLabel.setGravity(Gravity.CENTER_VERTICAL);
        duration = new EditTextBoldCursor(context);
        duration.setTextSize(16);
        duration.setTextColor(color(Theme.key_dialogTextBlack));
        duration.setCursorColor(color(Theme.key_dialogTextBlue));
        duration.setHintTextColor(color(Theme.key_dialogTextGray));
        duration.setSingleLine(true);
        duration.setInputType(InputType.TYPE_CLASS_NUMBER);
        duration.setGravity(Gravity.CENTER);
        duration.setPadding(dp(8), 0, dp(8), 0);
        duration.setBackground(Theme.createRoundRectDrawable(dp(8), color(Theme.key_dialogSearchBackground)));
        duration.setContentDescription(LocaleController.getString(R.string.GhostWaveformDuration));
        duration.setHint("1\u2013600");
        duration.setText("5");
        row.addView(duration, new LinearLayout.LayoutParams(dp(92), dp(44)));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(56));
        rowParams.topMargin = dp(4);
        content.addView(row, rowParams);
        duration.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { updatePreview(); }
            public void afterTextChanged(Editable s) { }
        });
        updatePreview();

        send = label(context, LocaleController.getString(R.string.Send), 16, Theme.key_featuredStickers_buttonText);
        send.setTypeface(AndroidUtilities.bold());
        send.setGravity(Gravity.CENTER);
        send.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), color(Theme.key_featuredStickers_addButton), color(Theme.key_featuredStickers_addButtonPressed)));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(-1, dp(48));
        sendParams.topMargin = dp(10);
        content.addView(send, sendParams);
        send.setOnClickListener(v -> {
            int seconds = seconds();
            if (seconds < 1 || seconds > 600) {
                duration.setError(LocaleController.getString(R.string.GhostWaveformDurationError));
                duration.requestFocus(); return;
            }
            if (preparing) return;
            AndroidUtilities.hideKeyboard(duration);
            setPreparing(true);
            delegate.send(seconds, waveform.levels.clone(), success -> AndroidUtilities.runOnUIThread(() -> {
                setPreparing(false);
                if (success) dismiss();
            }));
        });
        content.requestFocus();
        setCustomView(content);
    }
    private int dp(float value) { return AndroidUtilities.dp(value); }
    private int color(int key) { return Theme.getColor(key, provider); }
    private TextView label(Context context, String text, int size, int key) {
        TextView view = new TextView(context);
        view.setText(text); view.setTextSize(size); view.setTextColor(color(key));
        return view;
    }
    private void addTool(Context context, int text, Runnable action) {
        TextView button = label(context, LocaleController.getString(text), 14, Theme.key_dialogTextBlue);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector), 2));
        button.setOnClickListener(v -> action.run());
        tools.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
    }
    private void showTemplates(Context context) {
        AndroidUtilities.hideKeyboard(duration);
        CharSequence[] names = {
                LocaleController.getString(R.string.GhostWaveformDiamond), LocaleController.getString(R.string.GhostWaveformButterfly),
                LocaleController.getString(R.string.GhostWaveformBow), LocaleController.getString(R.string.GhostWaveformArrow),
                LocaleController.getString(R.string.GhostWaveformFish), LocaleController.getString(R.string.GhostWaveformHourglass)
        };
        new AlertDialog.Builder(context, provider)
                .setTitle(LocaleController.getString(R.string.GhostWaveformTemplates))
                .setItems(names, (dialog, index) -> {
                    waveform.apply(WaveformPresets.create(WaveformPresets.Template.values()[index]));
                    templates.setText(LocaleController.getString(R.string.GhostWaveformTemplates) + ": " + names[index]);
                }).setNegativeButton(LocaleController.getString(R.string.Cancel), null).show();
    }
    private int seconds() {
        try { return Integer.parseInt(duration.getText().toString()); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private void updatePreview() {
        int seconds = Math.max(0, Math.min(600, seconds()));
        preview.setText(LocaleController.getString(R.string.GhostWaveformSilent) + " \u00b7 " + String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
    }
    private void setPreparing(boolean value) {
        preparing = value;
        send.setEnabled(!value); duration.setEnabled(!value); waveform.setEnabled(!value); templates.setEnabled(!value);
        for (int i = 0; i < tools.getChildCount(); i++) tools.getChildAt(i).setEnabled(!value);
        send.setText(LocaleController.getString(value ? R.string.GhostWaveformPreparing : R.string.Send));
        setCanDismissWithTouchOutside(!value);
    }

    /** Use Telegram's own renderer, packing and sampling, without stretching Y. */
    private final class ActualWaveformView extends View {
        final SeekBarWaveform seekBar;
        int waveWidth;
        ActualWaveformView(Context context) {
            super(context);
            seekBar = new SeekBarWaveform(context);
            seekBar.setDelegate(new SeekBar.SeekBarDelegate() {
                @Override public void onSeekBarDrag(float progress) { }
                @Override public boolean isSeekBarDragAllowed() { return false; }
            });
            seekBar.setParentView(this);
            setContentDescription(LocaleController.getString(R.string.GhostWaveformActualPreview));
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            waveWidth = Math.min(dp(180), w);
            seekBar.setSize(waveWidth, h);
            update();
        }
        void update() {
            if (waveWidth > 0) seekBar.setWaveform(GhostMediaTools.packWaveform(waveform.levels));
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            int blue = color(Theme.key_dialogTextBlue);
            seekBar.setColors(blue, blue, blue);
            canvas.save();
            canvas.translate((getWidth() - waveWidth) / 2f, 0);
            seekBar.draw(canvas, this);
            canvas.restore();
        }
    }

    private final class WaveformView extends View {
        final int[] levels = new int[100];
        final ArrayDeque<int[]> history = new ArrayDeque<>();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int lastIndex, lastValue;
        boolean drawing;
        WaveformView(Context context) {
            super(context);
            for (int i = 0; i < levels.length; i++) levels[i] = 6 + (int) (10 * Math.abs(Math.sin(i * .17)));
            setContentDescription(LocaleController.getString(R.string.GhostWaveformHelp));
        }
        void snapshot() { if (history.size() == 32) history.removeFirst(); history.addLast(levels.clone()); }
        void changed() { invalidate(); actualWaveform.update(); }
        void preset(boolean random) {
            snapshot();
            Random rng = new Random();
            for (int i = 0; i < levels.length; i++) levels[i] = random ? rng.nextInt(32) : 0;
            changed();
        }
        void apply(int[] preset) {
            snapshot();
            System.arraycopy(preset, 0, levels, 0, levels.length);
            changed();
        }
        void undo() {
            if (!history.isEmpty()) System.arraycopy(history.removeLast(), 0, levels, 0, levels.length);
            changed();
        }
        @Override protected void onDraw(Canvas canvas) {
            float step = getWidth() / (float) levels.length;
            float center = getHeight() / 2f;
            paint.setColor(color(Theme.key_dialogTextBlue));
            for (int i = 0; i < levels.length; i++) {
                float height = dp(3) + levels[i] / 31f * (getHeight() - dp(19));
                float x = i * step + step * .2f;
                canvas.drawRoundRect(x, center - height / 2, x + step * .6f, center + height / 2, dp(2), dp(2), paint);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                snapshot(); drawing = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                lastIndex = index(event.getX()); lastValue = amplitude(event.getY());
            }
            if (drawing && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
                int index = index(event.getX()), value = amplitude(event.getY());
                int count = Math.abs(index - lastIndex);
                for (int i = 0; i <= count; i++) {
                    int at = count == 0 ? index : lastIndex + Integer.signum(index - lastIndex) * i;
                    levels[at] = count == 0 ? value : Math.round(lastValue + (value - lastValue) * (i / (float) count));
                }
                lastIndex = index; lastValue = value; changed();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (action == MotionEvent.ACTION_CANCEL && drawing) undo();
                drawing = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (action == MotionEvent.ACTION_UP) performClick();
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        private int index(float x) { return Math.max(0, Math.min(99, (int) (x * 100 / Math.max(1, getWidth())))); }
        private int amplitude(float y) { return Math.max(0, Math.min(31, Math.round((Math.abs(y - getHeight() / 2f) * 2 - dp(3)) * 31 / Math.max(1, getHeight() - dp(19))))); }
    }
}
