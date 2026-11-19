package org.telegram.messenger;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** Resolve the oldest accessible message, including when message 1 was deleted. */
public final class GhostChatBeginning {
    public static TLObject request(TLRPC.InputPeer peer, long threadId, TLRPC.InputPeer savedPeer) {
        if (savedPeer != null) {
            TLRPC.TL_messages_getSavedHistory request = new TLRPC.TL_messages_getSavedHistory();
            request.peer = savedPeer;
            request.parent_peer = peer;
            request.offset_id = 1;
            request.add_offset = -1;
            request.limit = 1;
            return request;
        }
        if (threadId != 0) {
            TLRPC.TL_messages_getReplies request = new TLRPC.TL_messages_getReplies();
            request.peer = peer;
            request.msg_id = (int) threadId;
            request.offset_id = 1;
            request.add_offset = -1;
            request.limit = 1;
            return request;
        }
        TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
        request.peer = peer;
        request.offset_id = 1;
        request.add_offset = -1;
        request.limit = 1;
        return request;
    }

    public static TLRPC.Message first(TLRPC.messages_Messages history) {
        TLRPC.Message first = null;
        for (TLRPC.Message message : history.messages) {
            if (message == null || message instanceof TLRPC.TL_messageEmpty || message.id <= 0) continue;
            if (first == null || message.id < first.id) first = message;
        }
        return first;
    }

    private GhostChatBeginning() {}
}
