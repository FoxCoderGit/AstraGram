package org.telegram.messenger;

/** Per-conversation replacement for the global double-tap reaction. */
public final class PerChatQuickReaction {
    private static final String PREFIX = "ghost_quick_reaction_";

    private PerChatQuickReaction() { }

    public static String get(int account, long dialogId, String fallback) {
        return MessagesController.getEmojiSettings(account).getString(PREFIX + dialogId, fallback);
    }

    /** A null value restores the global default for this conversation. */
    public static void set(int account, long dialogId, String reaction) {
        if (reaction == null) {
            MessagesController.getEmojiSettings(account).edit().remove(PREFIX + dialogId).apply();
        } else {
            MessagesController.getEmojiSettings(account).edit().putString(PREFIX + dialogId, reaction).apply();
        }
    }
}
