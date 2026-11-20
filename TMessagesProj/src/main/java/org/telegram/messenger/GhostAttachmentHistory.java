package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import org.telegram.tgnet.TLRPC;

/** Remembers references seen by this installation, not Telegram's global upload history. */
public final class GhostAttachmentHistory {
    private static final DispatchQueue queue = new DispatchQueue("ghostAttachmentHistory");
    private static final android.util.LruCache<String, Reference> first = new android.util.LruCache<>(4096);
    private static final android.util.LruCache<String, Boolean> recorded = new android.util.LruCache<>(4096); // queue only
    private static final java.util.Set<String> submitted = ConcurrentHashMap.newKeySet();
    private static final Runnable notifyChanged = () -> {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.ghostAttachmentHistoryUpdated);
        }
    };
    private static boolean started;
    private static SQLiteOpenHelper helper;

    private static synchronized void start() {
        if (started) return;
        started = true;
        queue.postRunnable(() -> {
            try {
                helper = new SQLiteOpenHelper(ApplicationLoader.applicationContext, "ghost_attachment_history.db", null, 1) {
                    public void onCreate(SQLiteDatabase db) {
                        db.execSQL("CREATE TABLE refs (media TEXT NOT NULL, dialog INTEGER NOT NULL, mid INTEGER NOT NULL, date INTEGER NOT NULL, PRIMARY KEY(media,dialog,mid))");
                    }
                    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
                };
                helper.getWritableDatabase().execSQL("CREATE INDEX IF NOT EXISTS refs_first ON refs(media,date,mid,dialog)");
            } catch (Exception e) { FileLog.e(e); }
        });
    }

    private static String key(MessageObject message) {
        if (message == null || message.messageOwner == null || message.getId() <= 0
                || GhostSimulationPolicy.virtual(message.getDialogId()) || message.isSticker() || message.isAnimatedSticker()) return null;
        TLRPC.Document document = message.getDocument();
        TLRPC.Photo photo = MessageObject.getPhoto(message.messageOwner);
        long self = UserConfig.getInstance(message.currentAccount).getClientUserId();
        if (self == 0) return null;
        if (document != null && document.id != 0) return self + ":d:" + document.dc_id + ":" + document.id;
        if (photo != null && photo.id != 0) return self + ":p:" + photo.dc_id + ":" + photo.id;
        return null;
    }

    public static void observe(MessageObject message) {
        String key = key(message);
        if (key == null) return;
        start();
        Reference value = new Reference(message.getDialogId(), message.getId(), message.messageOwner.date);
        String identity = key + ":" + value.dialog + ":" + value.id;
        synchronized (submitted) {
            if (submitted.size() >= 2048 || !submitted.add(identity)) return;
        }
        queue.postRunnable(() -> {
            try {
                if (helper == null || recorded.get(identity) != null && first.get(key) != null) return;
                ContentValues values = new ContentValues();
                values.put("media", key); values.put("dialog", value.dialog); values.put("mid", value.id); values.put("date", value.date);
                helper.getWritableDatabase().insertWithOnConflict("refs", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                Reference previous = first.get(key);
                try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT dialog,mid,date FROM refs WHERE media=? ORDER BY date,mid,dialog LIMIT 1", new String[]{key})) {
                    if (c.moveToFirst()) {
                        Reference earliest = new Reference(c.getLong(0), c.getInt(1), c.getInt(2));
                        first.put(key, earliest);
                        if (previous == null || !previous.same(earliest)) refresh();
                    }
                }
                recorded.put(identity, Boolean.TRUE);
            } catch (Exception e) { FileLog.e(e); }
            finally { synchronized (submitted) { submitted.remove(identity); } }
        });
    }

    public static boolean isRepeated(MessageObject message) {
        String key = key(message);
        if (key == null) return false;
        Reference previous = first.get(key);
        Reference value = new Reference(message.getDialogId(), message.getId(), message.messageOwner.date);
        return previous != null && !previous.same(value) && previous.before(value);
    }

    private static void refresh() {
        AndroidUtilities.runOnUIThread(() -> {
            AndroidUtilities.cancelRunOnUIThread(notifyChanged);
            AndroidUtilities.runOnUIThread(notifyChanged, 150);
        });
    }

    static final class Reference {
        final long dialog;
        final int id, date;
        Reference(long dialog, int id, int date) { this.dialog = dialog; this.id = id; this.date = date; }
        boolean same(Reference other) { return dialog == other.dialog && id == other.id; }
        boolean before(Reference other) {
            if (date != other.date) return date < other.date;
            if (id != other.id) return id < other.id;
            return dialog < other.dialog;
        }
    }
}
