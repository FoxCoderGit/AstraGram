package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

/** Local presentation only: does not modify Telegram users or block lists. */
public final class GhostLocalBlock {
    private GhostLocalBlock() { }

    public static boolean available(TLRPC.User user) {
        return user != null && user.id > 0 && !user.bot && !user.deleted && !UserObject.isUserSelf(user);
    }

    public static boolean enabled(int account, long userId) {
        return userId > 0 && userId != UserConfig.getInstance(account).getClientUserId()
                && MessagesController.getMainSettings(account).getBoolean("ghost_local_block_" + userId, false);
    }

    public static void toggle(int account, long userId) {
        TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        if (!available(user)) return;
        boolean enabled = !enabled(account, userId);
        android.content.SharedPreferences.Editor edit = MessagesController.getMainSettings(account).edit();
        if (enabled) edit.putBoolean("ghost_local_block_" + userId, true);
        else edit.remove("ghost_local_block_" + userId);
        edit.apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,
                MessagesController.UPDATE_MASK_AVATAR | MessagesController.UPDATE_MASK_STATUS);
    }
}
