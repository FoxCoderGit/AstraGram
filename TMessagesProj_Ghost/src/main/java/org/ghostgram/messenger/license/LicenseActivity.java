package org.ghostgram.messenger.license;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.*;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

public class LicenseActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditTextBoldCursor key;
    private TextView title, explanation, status;
    private ButtonWithCounterView button;
    private boolean checked;
    private final Runnable waitReady = () -> render();
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTheme(R.style.Theme_TMessages);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        int background = Theme.getColor(Theme.key_windowBackgroundWhite);
        getWindow().setStatusBarColor(background); getWindow().setNavigationBarColor(background);
        AndroidUtilities.setLightStatusBar(getWindow(), !Theme.isCurrentThemeDark());
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(background);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        content.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(36), AndroidUtilities.dp(28), AndroidUtilities.dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -1));
        ImageView logo = new ImageView(this); logo.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        content.addView(logo, LayoutHelper.createLinear(76, 76));
        TextView name = text("AstraGram", 18, true); content.addView(name, LayoutHelper.createLinear(-1, -2, 0, 14, 0, 28));
        title = text("", 24, true); content.addView(title, LayoutHelper.createLinear(-1, -2));
        explanation = text("", 15, false); explanation.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(explanation, LayoutHelper.createLinear(-1, -2, 0, 12, 0, 24));
        key = new EditTextBoldCursor(this); key.setTextSize(18); key.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        key.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText)); key.setHint(LocaleController.getString(R.string.GhostLicenseKeyHint));
        key.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)); key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSaveEnabled(false); key.setId(0x470401); key.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        key.setBackgroundDrawable(Theme.createEditTextDrawable(this, false));
        content.addView(key, LayoutHelper.createLinear(-1, 52, 0, 0, 0, 20));
        key.setOnEditorActionListener((v, action, event) -> { if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); return true; } return false; });
        button = new ButtonWithCounterView(this, null); button.setId(0x470402);
        content.addView(button, LayoutHelper.createLinear(-1, 50)); button.setOnClickListener(v -> submit());
        status = text("", 14, false); status.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        content.addView(status, LayoutHelper.createLinear(-1, -2, 0, 14, 0, 0));
        TextView note = text(LocaleController.getString(R.string.GhostLicensePrivacy), 13, false);
        note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(note, LayoutHelper.createLinear(-1, -2, 0, 26, 0, 0));
        TextView control = text("Обновления и статистика AstraGram", 14, false);
        control.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        control.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(12));
        control.setOnClickListener(v -> OwnerControl.show(this));
        content.addView(control, LayoutHelper.createLinear(-1, -2));
        setContentView(scroll); render();
    }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setGravity(Gravity.CENTER); view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (bold) view.setTypeface(AndroidUtilities.bold()); return view;
    }
    private void render() {
        if (isFinishing()) return;
        if (LicenseManager.allowed()) { open(); return; }
        boolean entered = !LicenseManager.licenseId.isEmpty();
        title.setText(LocaleController.getString(entered ? R.string.GhostLicenseVerifyTitle : R.string.GhostLicenseActivateTitle));
        explanation.setText(LocaleController.getString(entered ? R.string.GhostLicenseVerifyInfo : R.string.GhostLicenseActivateInfo));
        key.setVisibility(LicenseManager.ready && !entered ? View.VISIBLE : View.GONE);
        button.setText(LocaleController.getString(entered ? R.string.GhostLicenseRetry : R.string.GhostLicenseActivate), false);
        button.setLoading(!LicenseManager.ready || LicenseManager.busy);
        button.setEnabled(LicenseManager.ready && !LicenseManager.busy);
        if (!LicenseManager.ready || LicenseManager.busy) { handler.postDelayed(waitReady, 200); return; }
        if (entered && !checked) { checked = true; submit(); }
        handler.removeCallbacks(waitReady);
        handler.postDelayed(waitReady, 1000);
    }
    private void submit() {
        if (!LicenseManager.ready || LicenseManager.busy) return;
        String value = LicenseManager.licenseId.isEmpty() ? key.getText().toString().trim() : null;
        if (value != null && value.isEmpty()) { key.requestFocus(); AndroidUtilities.showKeyboard(key); return; }
        status.setText(""); button.setLoading(true); button.setEnabled(false); AndroidUtilities.hideKeyboard(key);
        LicenseManager.refresh(value, (ok, error) -> {
            if (isFinishing()) return;
            if (ok) { key.setText(""); open(); }
            else { status.setText(message(error)); render(); }
        });
    }
    private String message(String error) {
        int string;
        switch (error) {
            case "invalid_key": case "key_required": string = R.string.GhostLicenseInvalid; break;
            case "revoked": case "expired": case "binding_reset": string = R.string.GhostLicenseRevoked; break;
            case "device_bound": string = R.string.GhostLicenseBound; break;
            case "device_error": string = R.string.GhostLicenseDeviceError; break;
            default: string = R.string.GhostLicenseNetwork;
        }
        return LocaleController.getString(string);
    }
    private void open() {
        startActivity(new Intent(this, org.telegram.ui.LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish();
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
