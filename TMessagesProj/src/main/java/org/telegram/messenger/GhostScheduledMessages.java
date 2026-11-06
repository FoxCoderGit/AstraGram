package org.telegram.messenger;

import java.util.HashMap;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** Uses Telegram's real scheduled-message queue, including its persistence and errors. */
public final class GhostScheduledMessages {
    public static final String MARKER = "ghost_auto_schedule";
    private GhostScheduledMessages() { }

    public static boolean eligible(int account, long peer) {
        if (!GhostMode.useSchedule(account) || peer == 0 || DialogObject.isEncryptedDialog(peer)
                || peer == UserConfig.getInstance(account).getClientUserId()) return false;
        TLRPC.User user = peer > 0 ? MessagesController.getInstance(account).getUser(peer) : null;
        return user == null || !user.bot;
    }

    public static int date(int account, long bytes) {
        return GhostSchedulingPolicy.deadline(org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime() + networkMargin(account), bytes);
    }

    private static int networkMargin(int account) {
        int ping = org.telegram.tgnet.ConnectionsManager.native_getCurrentPingTime(account);
        return (int) Math.min(30L, 2L * ((Math.max(0L, ping) + 999L) / 1000L));
    }

    public static void prepare(int account, SendMessagesHelper.SendMessageParams p) {
        if (!eligible(account, p.peer) || GhostCloud.cloud(p.params) || p.sendingStory != null
                || p.ephemeralReceiverBotId != 0 || p.quick_reply_shortcut != null || p.quick_reply_shortcut_id != 0
                || (p.sendMessageChatArguments != null && (p.sendMessageChatArguments.quickReplyShortcut != null
                    || p.sendMessageChatArguments.quickReplyShortcutId != 0))) return;
        if (p.scheduleDate != 0 && !marked(p.params)) return;
        if (p.params == null) p.params = new HashMap<>();
        p.params.put(MARKER, "1");
        long size = p.document == null ? 0 : p.document.size;
        p.scheduleDate = date(account, size);
    }

    public static boolean marked(HashMap<String, String> params) {
        return params != null && "1".equals(params.get(MARKER));
    }

    /** Fail closed for bypasses (plugins, an upload started before enabling ghost mode). */
    public static boolean unsafeImmediate(int account, TLObject request) {
        if (!GhostMode.useSchedule(account) || GhostCloud.cloudRequest(request)) return false;
        TLRPC.InputPeer peer;
        int date;
        if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage r = (TLRPC.TL_messages_sendMessage) request; peer = r.peer; date = r.schedule_date;
        } else if (request instanceof TLRPC.TL_messages_sendMedia) {
            TLRPC.TL_messages_sendMedia r = (TLRPC.TL_messages_sendMedia) request; peer = r.peer; date = r.schedule_date;
        } else if (request instanceof TLRPC.TL_messages_sendMultiMedia) {
            TLRPC.TL_messages_sendMultiMedia r = (TLRPC.TL_messages_sendMultiMedia) request; peer = r.peer; date = r.schedule_date;
        } else if (request instanceof TLRPC.TL_messages_forwardMessages) {
            TLRPC.TL_messages_forwardMessages r = (TLRPC.TL_messages_forwardMessages) request; peer = r.to_peer; date = r.schedule_date;
        } else if (request instanceof TLRPC.TL_messages_sendInlineBotResult) {
            TLRPC.TL_messages_sendInlineBotResult r = (TLRPC.TL_messages_sendInlineBotResult) request; peer = r.peer; date = r.schedule_date;
        } else return false;
        if (peer == null || peer instanceof TLRPC.TL_inputPeerSelf) return false;
        long id = peer.user_id != 0 ? peer.user_id : peer.channel_id != 0 ? -peer.channel_id : -peer.chat_id;
        return eligible(account, id) && GhostSchedulingPolicy.tooSoon(date, org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime());
    }

    /** Rebase after uploads/file-reference retries; never let an expired date send immediately. */
    public static void refresh(int account, TLObject request, TLRPC.Message message) {
        if (message == null || !marked(message.params)) return;
        int now = org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime() + networkMargin(account);
        refreshAt(request, message, now);
    }

    static void refreshAt(TLObject request, TLRPC.Message message, int now) {
        if (message == null || !marked(message.params)) return;
        int date;
        if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage r = (TLRPC.TL_messages_sendMessage) request;
            date = r.schedule_date = GhostSchedulingPolicy.refresh(r.schedule_date, now); r.flags |= 1024;
        } else if (request instanceof TLRPC.TL_messages_sendMedia) {
            TLRPC.TL_messages_sendMedia r = (TLRPC.TL_messages_sendMedia) request;
            date = r.schedule_date = GhostSchedulingPolicy.refresh(r.schedule_date, now); r.flags |= 1024;
        } else if (request instanceof TLRPC.TL_messages_sendMultiMedia) {
            TLRPC.TL_messages_sendMultiMedia r = (TLRPC.TL_messages_sendMultiMedia) request;
            date = r.schedule_date = GhostSchedulingPolicy.refresh(r.schedule_date, now); r.flags |= 1024;
        } else if (request instanceof TLRPC.TL_messages_forwardMessages) {
            TLRPC.TL_messages_forwardMessages r = (TLRPC.TL_messages_forwardMessages) request;
            date = r.schedule_date = GhostSchedulingPolicy.refresh(r.schedule_date, now); r.flags |= 1024;
        } else if (request instanceof TLRPC.TL_messages_sendInlineBotResult) {
            TLRPC.TL_messages_sendInlineBotResult r = (TLRPC.TL_messages_sendInlineBotResult) request;
            date = r.schedule_date = GhostSchedulingPolicy.refresh(r.schedule_date, now); r.flags |= 1024;
        } else return;
        message.date = date;
    }
}
