package org.telegram.messenger;

import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONArray;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;

/** Local collections and extra pins. Server limits and subscription flags stay intact. */
public final class GhostLocalLimits {
    private GhostLocalLimits() { }
    static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("ghost_collections_" + account + "_" + UserConfig.getInstance(account).getClientUserId(), 0);
    }
    public static int stickerLimit(int account) {
        MessagesController c = MessagesController.getInstance(account);
        return Math.max(c.stickersFavedLimitDefault * 2, Math.max(200, c.stickersFavedLimitPremium) * (UserConfig.getInstance(account).isPremium() ? 2 : 1));
    }
    public static int gifLimit(int account) {
        MessagesController c = MessagesController.getInstance(account);
        return Math.max(c.savedGifsLimitDefault * 2, c.savedGifsLimitPremium * (UserConfig.getInstance(account).isPremium() ? 2 : 1));
    }
    public static int recentLimit(int account) { return MessagesController.getInstance(account).maxRecentStickersCount * 2; }
    public static int pinLimit(int account, int folder) {
        MessagesController c = MessagesController.getInstance(account);
        int multiplier = UserConfig.getInstance(account).isPremium() ? 2 : 1;
        return folder == 0 ? Math.max(c.maxPinnedDialogsCountDefault * 2, c.maxPinnedDialogsCountPremium * multiplier)
            : Math.max(c.maxFolderPinnedDialogsCountDefault * 2, c.maxFolderPinnedDialogsCountPremium * multiplier);
    }
    private static int limit(int account, String kind) { return kind.endsWith("gifs") ? gifLimit(account) : kind.endsWith("recent") ? recentLimit(account) : stickerLimit(account); }
    private static ArrayList<TLRPC.Document> read(int account, String kind) {
        ArrayList<TLRPC.Document> result = new ArrayList<>();
        try {
            JSONArray items = new JSONArray(preferences(account).getString(kind, "[]"));
            for (int i = 0; i < items.length(); i++) {
                SerializedData data = new SerializedData(Base64.decode(items.getString(i), Base64.NO_WRAP));
                try { TLRPC.Document doc = TLRPC.Document.TLdeserialize(data, data.readInt32(true), true); if (doc != null) result.add(doc); }
                finally { data.cleanup(); }
            }
        } catch (Exception e) { FileLog.e(e); }
        return result;
    }
    private static void save(int account, String kind, ArrayList<TLRPC.Document> documents) {
        JSONArray items = new JSONArray();
        for (TLRPC.Document document : documents) {
            SerializedData data = new SerializedData();
            try { document.serializeToStream(data); items.put(Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)); }
            finally { data.cleanup(); }
        }
        preferences(account).edit().putString(kind, items.toString()).apply();
    }
    public static synchronized ArrayList<TLRPC.Document> merge(int account, String kind, ArrayList<TLRPC.Document> incoming) {
        HashSet<String> removed = new HashSet<>(preferences(account).getStringSet(kind + "_removed", new HashSet<>()));
        LinkedHashMap<Long, TLRPC.Document> fresh = new LinkedHashMap<>(), merged = new LinkedHashMap<>();
        for (TLRPC.Document doc : incoming) if (doc != null) fresh.put(doc.id, doc);
        for (TLRPC.Document doc : read(account, kind)) {
            if (!removed.contains(Long.toString(doc.id))) merged.put(doc.id, fresh.containsKey(doc.id) ? fresh.get(doc.id) : doc);
        }
        for (TLRPC.Document doc : incoming) if (doc != null && !removed.contains(Long.toString(doc.id))) merged.put(doc.id, doc);
        ArrayList<TLRPC.Document> result = new ArrayList<>(merged.values());
        while (result.size() > limit(account, kind)) result.remove(result.size() - 1);
        save(account, kind, result);
        return result;
    }
    public static synchronized ArrayList<TLRPC.Document> change(int account, String kind, ArrayList<TLRPC.Document> current, TLRPC.Document document, boolean remove) {
        ArrayList<TLRPC.Document> result = merge(account, kind, current);
        result.removeIf(doc -> doc.id == document.id);
        HashSet<String> removed = new HashSet<>(preferences(account).getStringSet(kind + "_removed", new HashSet<>()));
        if (remove) removed.add(Long.toString(document.id)); else { removed.remove(Long.toString(document.id)); result.add(0, document); }
        while (result.size() > limit(account, kind)) {
            TLRPC.Document dropped = result.remove(result.size() - 1);
            removed.add(Long.toString(dropped.id));
        }
        preferences(account).edit().putStringSet(kind + "_removed", removed).apply();
        save(account, kind, result);
        return result;
    }
    public static synchronized void clearRecent(int account) {
        HashSet<String> removed = new HashSet<>(preferences(account).getStringSet("recent_removed", new HashSet<>()));
        for (TLRPC.Document doc : read(account, "recent")) removed.add(Long.toString(doc.id));
        preferences(account).edit().putString("recent", "[]").putStringSet("recent_removed", removed).apply();
    }
    private static String pinKey(TLRPC.Dialog dialog) { return "pin_" + dialog.folder_id + "_" + dialog.id; }
    public static boolean isLocalPin(int account, TLRPC.Dialog dialog) { return preferences(account).contains(pinKey(dialog)); }
    public static void removePin(int account, long dialogId) {
        SharedPreferences prefs = preferences(account);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith("pin_") && key.endsWith("_" + dialogId)) editor.remove(key);
        editor.apply();
    }
    public static boolean canPin(int account, TLRPC.Dialog dialog, ArrayList<TLRPC.Dialog> dialogs) {
        if (dialog.pinned) return true;
        int count = 0;
        boolean secret = DialogObject.isEncryptedDialog(dialog.id);
        for (TLRPC.Dialog d : dialogs) if (!(d instanceof TLRPC.TL_dialogFolder) && d.pinned && DialogObject.isEncryptedDialog(d.id) == secret) count++;
        return count < pinLimit(account, dialog.folder_id);
    }
    public static boolean changePin(int account, TLRPC.Dialog dialog, boolean pin, ArrayList<TLRPC.Dialog> dialogs) {
        if (isLocalPin(account, dialog)) {
            if (!pin) { preferences(account).edit().remove(pinKey(dialog)).apply(); dialog.pinned = false; dialog.pinnedNum = 0; }
            return true;
        }
        if (!pin || dialog.pinned || DialogObject.isEncryptedDialog(dialog.id)) return false;
        MessagesController c = MessagesController.getInstance(account);
        int serverLimit = dialog.folder_id == 0 ? (UserConfig.getInstance(account).isPremium() ? c.maxPinnedDialogsCountPremium : c.maxPinnedDialogsCountDefault)
            : (UserConfig.getInstance(account).isPremium() ? c.maxFolderPinnedDialogsCountPremium : c.maxFolderPinnedDialogsCountDefault);
        int serverCount = 0, total = 0, rank = 0;
        for (TLRPC.Dialog d : dialogs) {
            if (d instanceof TLRPC.TL_dialogFolder || !d.pinned || DialogObject.isEncryptedDialog(d.id)) continue;
            total++; if (!isLocalPin(account, d)) serverCount++;
            rank = Math.max(rank, preferences(account).getInt(pinKey(d), 0));
        }
        if (serverCount < serverLimit) return false;
        if (total >= pinLimit(account, dialog.folder_id)) return false;
        preferences(account).edit().putInt(pinKey(dialog), rank + 1).apply();
        dialog.pinned = true; dialog.pinnedNum = 1000000 + rank + 1;
        return true;
    }
    public static void applyPins(int account, ArrayList<TLRPC.Dialog> dialogs) {
        SharedPreferences prefs = preferences(account);
        for (TLRPC.Dialog dialog : dialogs) {
            int rank = prefs.getInt(pinKey(dialog), 0);
            if (rank > 0) { dialog.pinned = true; dialog.pinnedNum = 1000000 + rank; }
        }
    }
    public static void savePinOrder(int account, ArrayList<TLRPC.Dialog> dialogs) {
        SharedPreferences.Editor editor = preferences(account).edit();
        int rank = dialogs.size();
        for (TLRPC.Dialog dialog : dialogs) { if (isLocalPin(account, dialog)) editor.putInt(pinKey(dialog), rank); rank--; }
        editor.apply();
    }
}
