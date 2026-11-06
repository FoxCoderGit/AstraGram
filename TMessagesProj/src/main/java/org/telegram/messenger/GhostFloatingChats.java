package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import java.util.concurrent.ConcurrentHashMap;

/** Native Android conversation bubbles: the OS owns dragging, stacking and dismissal. */
public final class GhostFloatingChats {
    private static final ConcurrentHashMap<String, Record> retained = new ConcurrentHashMap<>();
    private static class Record {
        final int id; final Notification notification;
        Record(int id, Notification notification) { this.id = id; this.notification = notification; }
    }
    private GhostFloatingChats() {}
    public static SharedPreferences settings(int account) { return MessagesController.getMainSettings(account); }
    public static int mode(int account) { return settings(account).getInt("ghost_bubble_mode", 0); }
    public static boolean selected(int account, long user) { return settings(account).getBoolean("ghost_bubble_user_" + user, false); }
    public static boolean matches(int mode, Boolean selection) {
        return mode == 1 ? !Boolean.FALSE.equals(selection) : mode == 2 && Boolean.TRUE.equals(selection);
    }
    public static boolean enabled(int account, long user) {
        if (!GhostLicenseBridge.allowed()) return false;
        return Build.VERSION.SDK_INT >= 30 && user > 0 && user != UserConfig.getInstance(account).getClientUserId()
            && !SharedConfig.appLocked && !SharedConfig.isWaitingForPasscodeEnter
            && matches(mode(account), settings(account).contains("ghost_bubble_user_" + user) ? selected(account, user) : null);
    }
    public static void setMode(int account, int value) {
        settings(account).edit().putInt("ghost_bubble_mode", value).apply();
        if (value == 0) close(account);
    }
    public static void toggle(int account, long user) {
        boolean value = mode(account) == 1 ? !settings(account).getBoolean("ghost_bubble_user_" + user, true) : !selected(account, user);
        SharedPreferences.Editor edit = settings(account).edit().putBoolean("ghost_bubble_user_" + user, value);
        if (value && mode(account) == 0) edit.putInt("ghost_bubble_mode", 2);
        edit.apply();
        if (!value) dismiss(account, user);
    }
    public static boolean onlyBubble(int account) { return settings(account).getBoolean("ghost_only_bubbles", false); }
    public static void setOnlyBubble(int account, boolean value) { settings(account).edit().putBoolean("ghost_only_bubbles", value).apply(); }
    private static String key(int account, long dialog) { return account + ":" + dialog; }
    private static NotificationManager manager() { return (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE); }
    public static void remember(int account, long dialog, int id, Notification notification) {
        if (enabled(account, dialog) && NotificationCompat.getBubbleMetadata(notification) != null)
            retained.put(key(account, dialog), new Record(id, notification));
    }
    /** Keep only an actually displayed OS bubble, never an ordinary stale notification. */
    public static boolean retainAfterRead(int account, long dialog, int id) {
        Record record = retained.get(key(account, dialog));
        if (!enabled(account, dialog)) return false;
        try {
            boolean shown = false;
            for (StatusBarNotification item : manager().getActiveNotifications())
                if (item.getId() == id && (item.getNotification().flags & Notification.FLAG_BUBBLE) != 0) {
                    if (!shortcut(account, dialog).equals(item.getNotification().getShortcutId())) continue;
                    record = new Record(id, item.getNotification());
                    retained.put(key(account, dialog), record);
                    shown = true; break;
                }
            if (!shown) return false;
            NotificationCompat.BubbleMetadata old = NotificationCompat.getBubbleMetadata(record.notification);
            if (old == null || old.getIntent() == null || old.getIcon() == null) return false;
            NotificationCompat.BubbleMetadata metadata = new NotificationCompat.BubbleMetadata.Builder(old.getIntent(), old.getIcon())
                .setDesiredHeight(old.getDesiredHeight()).setDeleteIntent(old.getDeleteIntent())
                .setSuppressNotification(true).setAutoExpandBubble(false).build();
            Notification quiet = new NotificationCompat.Builder(ApplicationLoader.applicationContext, record.notification)
                .setBubbleMetadata(metadata).setOnlyAlertOnce(true).setSilent(true).setNumber(0).build();
            manager().notify(id, quiet); return true;
        } catch (Exception error) { FileLog.e(error); return false; }
    }
    public static void dismiss(int account, long dialog) {
        Record record = retained.remove(key(account, dialog));
        if (record != null) manager().cancel(record.id);
        cancelMatching(shortcut(account, dialog), false);
    }
    public static void close(int account) {
        for (String key : retained.keySet()) if (key.startsWith(account + ":")) {
            Record record = retained.remove(key); if (record != null) manager().cancel(record.id);
        }
        cancelMatching("ndid_" + account + "_", true);
    }
    private static String shortcut(int account, long dialog) { return "ndid_" + account + "_" + dialog; }
    private static void cancelMatching(String shortcut, boolean prefix) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            for (StatusBarNotification item : manager().getActiveNotifications()) {
                String value = item.getNotification().getShortcutId();
                if ((item.getNotification().flags & Notification.FLAG_BUBBLE) != 0 && value != null
                    && (prefix ? value.startsWith(shortcut) : value.equals(shortcut))) manager().cancel(item.getId());
            }
        } catch (Exception error) { FileLog.e(error); }
    }
}
