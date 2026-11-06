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
    private static final ConcurrentHashMap<String, Reference> first = new ConcurrentHashMap<>();
    private static final HashSet<String> recorded = new HashSet<>(); // queue only
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
                try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT media,dialog,mid,date FROM refs", null)) {
                    while (c.moveToNext()) {
                        String key = c.getString(0);
                        Reference value = new Reference(c.getLong(1), c.getInt(2), c.getInt(3));
                        recorded.add(key + ":" + value.dialog + ":" + value.id);
                        add(key, value);
                    }
                }
                refresh();
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
        if (!submitted.add(identity)) return;
        queue.postRunnable(() -> {
            if (helper == null || !recorded.add(key + ":" + value.dialog + ":" + value.id)) return;
            try {
                ContentValues values = new ContentValues();
                values.put("media", key); values.put("dialog", value.dialog); values.put("mid", value.id); values.put("date", value.date);
                helper.getWritableDatabase().insertWithOnConflict("refs", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                Reference previous = first.get(key);
                add(key, value);
                if (previous != null && !previous.same(value)) refresh();
            } catch (Exception e) { FileLog.e(e); }
        });
    }

    private static void add(String key, Reference value) {
        first.compute(key, (ignored, previous) -> previous == null || value.before(previous) ? value : previous);
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
