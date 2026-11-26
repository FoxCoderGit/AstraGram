package org.telegram.messenger;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.view.View;
import android.graphics.drawable.GradientDrawable;
import androidx.core.graphics.ColorUtils;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import static org.telegram.messenger.AndroidUtilities.dp;
import org.telegram.ui.ActionBar.BaseFragment;
import java.util.Calendar;

/** Presentation-only override; never changes cached Telegram users or printing state. */
public final class GhostLocalStatus {
    private GhostLocalStatus() { }
    public static int type(int account, long user) { return MessagesController.getMainSettings(account).getInt("ghost_status_" + user, 0); }
    public static int animation(int account, long user) {
        if (GhostLocalBlock.enabled(account, user)) return -1;
        switch (type(account, user)) {
            case 3: return 0;
            case 4: return 1;
            case 5: return 2;
            case 6: case 7: case 8: return 2;
            default: return -1;
        }
    }
    public static String text(int account, long user) {
        if (GhostLocalBlock.enabled(account, user)) return LocaleController.getString(R.string.ALongTimeAgo);
        switch (type(account, user)) {
            case 1: return LocaleController.getString(R.string.Online);
            case 2: return LocaleController.formatDateOnline(MessagesController.getMainSettings(account).getLong("ghost_status_date_" + user, 0), null);
            case 3: return LocaleController.getString(R.string.Typing).replace("...", "");
            case 4: return LocaleController.getString(R.string.RecordingAudio).replace("...", "");
            case 5: return LocaleController.getString(R.string.AstraLocalStatusSendingVoice);
            case 6: return LocaleController.getString(R.string.SendingPhoto).replace("...", "");
            case 7: return LocaleController.getString(R.string.SendingVideoStatus).replace("...", "");
            case 8: return LocaleController.getString(R.string.SendingFile).replace("...", "");
            default: return null;
        }
    }
    public static void show(BaseFragment fragment, long user, Runnable refresh) {
        if (fragment.getParentActivity() == null) return;
        int account = fragment.getCurrentAccount();
        int selected = type(account, user);
        android.content.Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resources = fragment.getResourceProvider();
        int foreground = Theme.getColor(Theme.key_dialogTextBlack, resources);
        int secondary = ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resources), foreground, 0.30f);
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resources);
        int panel = Theme.getColor(Theme.key_dialogBackground, resources);
        int groupColor = ColorUtils.blendARGB(panel, foreground, 0.055f);
        int selectedColor = ColorUtils.blendARGB(groupColor, accent, 0.13f);
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        android.widget.LinearLayout content = new android.widget.LinearLayout(context);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(18));
        scroll.addView(content);
        View handle = new View(context);
        GradientDrawable handleShape = new GradientDrawable();
        handleShape.setColor(ColorUtils.blendARGB(panel, foreground, 0.28f));
        handleShape.setCornerRadius(dp(3));
        handle.setBackground(handleShape);
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        android.widget.LinearLayout.LayoutParams handleParams = new android.widget.LinearLayout.LayoutParams(dp(34), dp(4));
        handleParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(14);
        content.addView(handle, handleParams);
        android.widget.TextView title = new android.widget.TextView(context);
        title.setText(LocaleController.getString(R.string.AstraLocalStatusTitle));
        title.setTextSize(24);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(foreground);
        content.addView(title);
        android.widget.TextView hint = new android.widget.TextView(context);
        hint.setText(LocaleController.getString(R.string.AstraLocalStatusHint));
        hint.setTextSize(14);
        hint.setTextColor(secondary);
        hint.setPadding(0, dp(5), 0, dp(16));
        content.addView(hint);
        BottomSheet sheet = new BottomSheet.Builder(context, false, resources)
                .setApplyTopPadding(false).setApplyBottomPadding(false).setCustomView(scroll).create();

        sheet.setDimBehindAlpha(153);
        sheet.setCanDismissWithSwipe(true);
        int[] labels = {R.string.AstraLocalStatusReal, R.string.AstraLocalStatusOnline, R.string.AstraLocalStatusTime,
                R.string.AstraLocalStatusTyping, R.string.AstraLocalStatusRecordingVoice, R.string.AstraLocalStatusSendingVoice,
                R.string.AstraLocalStatusSendingPhoto, R.string.AstraLocalStatusSendingVideo, R.string.AstraLocalStatusSendingFile};
        int[] icons = {R.drawable.msg_contacts, R.drawable.astra_status_online, R.drawable.astra_status_clock,
                R.drawable.astra_status_typing, R.drawable.astra_status_mic, R.drawable.msg_send,
                R.drawable.msg_gallery, R.drawable.msg_videocall, R.drawable.astra_status_file};
        android.widget.LinearLayout group = null;
        for (int index = 0; index < labels.length; index++) {
            final int option = index;
            if (index == 0 || index == 3) {
                group = new android.widget.LinearLayout(context);
                group.setOrientation(android.widget.LinearLayout.VERTICAL);
                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setColor(groupColor);
                shape.setCornerRadius(dp(16));
                group.setBackground(shape);
                group.setClipToOutline(true);
                android.widget.LinearLayout.LayoutParams groupParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
                if (index != 0) groupParams.topMargin = dp(12);
                content.addView(group, groupParams);
            } else {
                View line = new View(context);
                line.setBackgroundColor(Theme.getColor(Theme.key_divider, resources));
                android.widget.LinearLayout.LayoutParams lineParams = new android.widget.LinearLayout.LayoutParams(-1, 1);
                lineParams.setMarginStart(dp(16));
                group.addView(line, lineParams);
            }
            android.widget.LinearLayout row = new android.widget.LinearLayout(context);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(49));
            row.setPaddingRelative(dp(14), dp(7), dp(14), dp(7));
            row.setBackground(Theme.createSelectorWithBackgroundDrawable(index == selected ? selectedColor : groupColor,
                    ColorUtils.setAlphaComponent(accent, 36)));
            android.widget.ImageView icon = new android.widget.ImageView(context);
            icon.setImageResource(icons[index]);
            icon.setColorFilter(index == selected ? accent : foreground);
            row.addView(icon, new android.widget.LinearLayout.LayoutParams(dp(23), dp(23)));
            android.widget.TextView label = new android.widget.TextView(context);
            label.setText(LocaleController.getString(labels[index]).replace("...", "").replace("…", ""));
            label.setTextColor(foreground);
            label.setTextSize(16);
            android.widget.LinearLayout.LayoutParams labelParams = new android.widget.LinearLayout.LayoutParams(0, -2, 1);
            labelParams.setMarginStart(dp(16));
            row.addView(label, labelParams);
            if (index == selected || index == 2) {
                android.widget.ImageView marker = new android.widget.ImageView(context);
                marker.setImageResource(index == 2 ? R.drawable.astra_status_arrow : R.drawable.astra_status_check);
                marker.setColorFilter(index == selected ? accent : secondary);
                android.widget.LinearLayout.LayoutParams markerParams = new android.widget.LinearLayout.LayoutParams(dp(22), dp(22));
                markerParams.setMarginStart(dp(8));
                row.addView(marker, markerParams);
                if (index == 2 && index == selected) {
                    android.widget.ImageView check = new android.widget.ImageView(context);
                    check.setImageResource(R.drawable.astra_status_check);
                    check.setColorFilter(accent);
                    row.addView(check, row.getChildCount() - 1, new android.widget.LinearLayout.LayoutParams(dp(22), dp(22)));
                }
            }
            row.setSelected(index == selected);
            row.setOnClickListener(view -> {
                sheet.dismiss();
                if (option == 2) pickDate(fragment, account, user, refresh);
                else {
                    save(account, user, option, 0);
                    refresh.run();
                }
            });
            group.addView(row);
        }
        fragment.showDialog(sheet);
    }

    private static void pickDate(BaseFragment fragment, int account, long user, Runnable refresh) {
        if (fragment.getParentActivity() == null) return;
        Calendar date = Calendar.getInstance();
        long old = MessagesController.getMainSettings(account).getLong("ghost_status_date_" + user, 0);
        if (old > 0) date.setTimeInMillis(old * 1000);
        DatePickerDialog picker = new DatePickerDialog(fragment.getParentActivity(), (view, year, month, day) -> {
            date.set(year, month, day);
            new TimePickerDialog(fragment.getParentActivity(), (time, hour, minute) -> {
                date.set(Calendar.HOUR_OF_DAY, hour);
                date.set(Calendar.MINUTE, minute);
                date.set(Calendar.SECOND, 0);
                date.set(Calendar.MILLISECOND, 0);
                long seconds = Math.min(date.getTimeInMillis(), System.currentTimeMillis()) / 1000;
                save(account, user, 2, seconds);
                refresh.run();
            }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), true).show();
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        picker.show();
    }
    private static void save(int account, long user, int type, long seconds) {
        SharedPreferences.Editor edit = MessagesController.getMainSettings(account).edit();
        if (type == 0) edit.remove("ghost_status_" + user).remove("ghost_status_date_" + user);
        else edit.putInt("ghost_status_" + user, type).putLong("ghost_status_date_" + user, seconds);
        edit.apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
    }
}
