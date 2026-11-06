package org.telegram.messenger;

/** Display-only overrides: never mutate a TL message or send an edit request. */
public final class LocalMessageEdits {
    private LocalMessageEdits() { }
    private static String key(long dialog, int id) { return "ghost_local_edit_" + dialog + "_" + id; }
    public static String get(MessageObject message) {
        if (message == null || message.messageOwner == null || message.isOutOwner()) return null;
        return MessagesController.getMainSettings(message.currentAccount).getString(key(message.getDialogId(), message.getId()), null);
    }
    public static void set(MessageObject message, String value) {
        android.content.SharedPreferences.Editor edit = MessagesController.getMainSettings(message.currentAccount).edit();
        if (value == null) edit.remove(key(message.getDialogId(), message.getId()));
        else edit.putString(key(message.getDialogId(), message.getId()), value);
        edit.apply();
        message.caption = null; message.applyNewText(); message.generateCaption();
        NotificationCenter.getInstance(message.currentAccount).postNotificationName(NotificationCenter.messageTranslated, message, false, false, true);
    }
}
