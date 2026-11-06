package org.telegram.messenger;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import org.telegram.ui.ActionBar.AlertDialog;
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
            case 5: return LocaleController.getString(R.string.SendingAudio).replace("...", "");
            case 6: return LocaleController.getString(R.string.SendingPhoto).replace("...", "");
            case 7: return LocaleController.getString(R.string.SendingVideoStatus).replace("...", "");
            case 8: return LocaleController.getString(R.string.SendingFile).replace("...", "");
            default: return null;
        }
    }
    public static void show(BaseFragment fragment, long user, Runnable refresh) {
        int account = fragment.getCurrentAccount();
        String[] options = {"Настоящий статус", "В сети", "Указать время последнего посещения", "Печатает", "Записывает голосовое", "Отправляет голосовое", "Отправляет фото", "Отправляет видео", "Отправляет файл"};
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
            .setTitle("Локальный статус")
            .setMessage("Изменяется только отображение на вашем устройстве. Собеседник этого не увидит.")
            .setItems(options, (dialog, selected) -> {
                if (selected == 2) {
                    Calendar date = Calendar.getInstance();
                    long old = MessagesController.getMainSettings(account).getLong("ghost_status_date_" + user, 0);
                    if (old > 0) date.setTimeInMillis(old * 1000);
                    DatePickerDialog picker = new DatePickerDialog(fragment.getParentActivity(), (view, year, month, day) -> {
                        date.set(year, month, day);
                        new TimePickerDialog(fragment.getParentActivity(), (time, hour, minute) -> {
                            date.set(Calendar.HOUR_OF_DAY, hour); date.set(Calendar.MINUTE, minute); date.set(Calendar.SECOND, 0);
                            date.set(Calendar.MILLISECOND, 0);
                            long seconds = Math.min(date.getTimeInMillis(), System.currentTimeMillis()) / 1000;
                            save(account, user, selected, seconds); refresh.run();
                        }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), true).show();
                    }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH));
                    picker.getDatePicker().setMaxDate(System.currentTimeMillis()); picker.show();
                } else { save(account, user, selected, 0); refresh.run(); }
            }).create());
    }
    private static void save(int account, long user, int type, long seconds) {
        SharedPreferences.Editor edit = MessagesController.getMainSettings(account).edit();
        if (type == 0) edit.remove("ghost_status_" + user).remove("ghost_status_date_" + user);
        else edit.putInt("ghost_status_" + user, type).putLong("ghost_status_date_" + user, seconds);
        edit.apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
    }
}
