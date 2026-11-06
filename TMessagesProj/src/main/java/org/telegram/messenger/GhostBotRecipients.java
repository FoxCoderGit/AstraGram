package org.telegram.messenger;

import android.content.SharedPreferences;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.GhostBotUpdates;
import org.telegram.tgnet.tl.TL_update;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** A bot's observed conversations, never the phone account's address book. */
public final class GhostBotRecipients {
    private GhostBotRecipients() { }
    public static boolean isBot(int account) {
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        return self != null && self.bot;
    }
    static SharedPreferences preferences(int account, long bot) {
        return ApplicationLoader.applicationContext.getSharedPreferences("ghost_bot_recipients_" + account + "_" + bot, 0);
    }
    static synchronized void record(int account, long bot, long dialog, boolean blocked, int date) {
        if (dialog == 0 || dialog == bot || DialogObject.isEncryptedDialog(dialog)) return;
        SharedPreferences prefs = preferences(account, bot);
        int previous = prefs.getInt("peer_" + dialog, 0);
        if (date < previous) return;
        prefs.edit().putInt("peer_" + dialog, Math.max(previous, date)).putBoolean("blocked_" + dialog, blocked).apply();
    }
    public static ArrayList<Long> known(int account) { return known(account, UserConfig.getInstance(account).getClientUserId()); }
    static ArrayList<Long> known(int account, long bot) {
        SharedPreferences prefs = preferences(account, bot);
        HashMap<Long, Integer> dates = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith("peer_")) continue;
            try {
                long id = Long.parseLong(entry.getKey().substring(5));
                if (!prefs.getBoolean("blocked_" + id, false)) dates.put(id, (Integer) entry.getValue());
            } catch (Exception ignore) { }
        }
        ArrayList<Long> ids = new ArrayList<>(dates.keySet());
        ids.sort((a, b) -> { int cmp = Integer.compare(dates.get(b), dates.get(a)); return cmp != 0 ? cmp : Long.compare(a, b); });
        return ids;
    }
    static long messageDialog(long self, TLRPC.Message message) {
        if (message.peer_id == null) return 0;
        if (message.peer_id.user_id != 0) {
            return message.peer_id.user_id == self && message.from_id != null ? message.from_id.user_id : message.peer_id.user_id;
        }
        return message.peer_id.channel_id != 0 ? -message.peer_id.channel_id : -message.peer_id.chat_id;
    }
    public static void observeUpdates(int account, ArrayList<TLRPC.Update> updates, ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        if (!isBot(account)) return;
        long self = UserConfig.getInstance(account).getClientUserId();
        boolean changed = false;
        for (TLRPC.Update update : updates) {
            if (update instanceof GhostBotUpdates.UpdateBotStopped) {
                GhostBotUpdates.UpdateBotStopped stopped = (GhostBotUpdates.UpdateBotStopped) update;
                record(account, self, stopped.user_id, stopped.stopped, stopped.date); changed = true;
            } else if (update instanceof GhostBotUpdates.UpdateBotCallbackQuery) {
                GhostBotUpdates.UpdateBotCallbackQuery callback = (GhostBotUpdates.UpdateBotCallbackQuery) update;
                long dialog = MessageObject.getPeerId(callback.peer);
                if (!preferences(account, self).contains("peer_" + dialog)) {
                    record(account, self, dialog, false, currentTime(account)); changed = true;
                }
            } else {
                TLRPC.Message message = update instanceof TL_update.TL_updateNewMessage ? ((TL_update.TL_updateNewMessage) update).message
                    : update instanceof TL_update.TL_updateNewChannelMessage ? ((TL_update.TL_updateNewChannelMessage) update).message : null;
                if (message != null && !message.out && !(message instanceof TLRPC.TL_messageEmpty)) {
                    record(account, self, messageDialog(self, message), false, message.date); changed = true;
                }
            }
        }
        if (!changed) return;
        MessagesController controller = MessagesController.getInstance(account);
        MessagesStorage.getInstance(account).putUsersAndChats(users, chats, true, true);
        AndroidUtilities.runOnUIThread(() -> {
            controller.putUsers(users, false); controller.putChats(chats, false);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        });
    }
    private static int currentTime(int account) { return org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime(); }
    public static void seedCachedDialogs(int account) {
        if (!isBot(account)) return;
        MessagesController controller = MessagesController.getInstance(account);
        long self = UserConfig.getInstance(account).getClientUserId();
        SharedPreferences prefs = preferences(account, self);
        for (TLRPC.Dialog dialog : controller.allDialogs) {
            if (!prefs.contains("peer_" + dialog.id)) record(account, self, dialog.id, false, dialog.last_message_date);
        }
    }
    public static void loadPeers(int account, Runnable done) {
        ArrayList<Long> users = new ArrayList<>(), chats = new ArrayList<>();
        for (long id : known(account)) { if (id > 0) users.add(id); else chats.add(-id); }
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<TLRPC.User> loadedUsers = storage.getUsers(users);
            ArrayList<TLRPC.Chat> loadedChats = storage.getChats(chats);
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController c = MessagesController.getInstance(account);
                c.putUsers(loadedUsers, true); c.putChats(loadedChats, true); done.run();
            });
        });
    }
}
