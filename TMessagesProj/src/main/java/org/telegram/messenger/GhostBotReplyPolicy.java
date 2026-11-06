package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

/** Only new incoming messages from the selected bot belong to this lookup. */
public final class GhostBotReplyPolicy {
    private GhostBotReplyPolicy() { }
    public static boolean fresh(TLRPC.Message message, long botId, int baseline) {
        return message != null && message.id > baseline && !message.out
                && message.peer_id != null && message.peer_id.user_id == botId
                && (message.from_id == null || message.from_id.user_id == botId);
    }
    public static boolean matches(TLRPC.User user, long target) {
        return user != null && user.id == target && !(user instanceof TLRPC.TL_userEmpty) && !user.min;
    }
    public static boolean mentions(TLRPC.Message message, long target) {
        if (message == null || message.entities == null) return false;
        for (TLRPC.MessageEntity entity : message.entities) {
            if (entity instanceof TLRPC.TL_messageEntityMentionName
                    && ((TLRPC.TL_messageEntityMentionName) entity).user_id == target) return true;
            if (profileLink(entity.url, target)) return true;
        }
        return false;
    }
    public static boolean profileLink(String url, long target) {
        if (url == null) return false;
        String user = "tg://user?id=" + target;
        String chat = "tg://openmessage?user_id=" + target;
        return url.equals(user) || url.startsWith(user + "&") || url.equals(chat) || url.startsWith(chat + "&");
    }
}
