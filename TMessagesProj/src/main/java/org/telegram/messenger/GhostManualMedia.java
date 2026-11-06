package org.telegram.messenger;

import java.util.ArrayList;
import org.telegram.tgnet.TLRPC;

/** Incoming disappearing photos/videos are consumed only by the explicit viewer action. */
public final class GhostManualMedia {
    private GhostManualMedia() { }

    public static boolean hold(MessageObject message) {
        return message != null && !message.forceExpired && hold(message.messageOwner);
    }

    public static boolean hold(TLRPC.Message message) {
        if (message == null || MessageObject.isOut(message)) return false;
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media == null || media instanceof TLRPC.TL_messageMediaPaidMedia) return false;
        boolean timed = media.ttl_seconds > 0 || message instanceof TLRPC.TL_message_secret && message.ttl > 0;
        if (!timed) return false;
        return media.photo != null && !(media.photo instanceof TLRPC.TL_photoEmpty)
            || media.document != null && !(media.document instanceof TLRPC.TL_documentEmpty)
                && (MessageObject.isVideoMessage(message) || MessageObject.isGifMessage(message)
                    || MessageObject.isRoundVideoMessage(message));
    }

    /** Only called after pressing Destroy, never from open/close/player callbacks. */
    public static void destroy(MessageObject message) {
        if (!hold(message)) return;
        final int account = message.currentAccount;
        final long dialogId = message.getDialogId();
        final int mid = message.getId();
        message.forceExpired = true;
        MessagesController controller = MessagesController.getInstance(account);
        if (message.isSecret()) {
            controller.markMessageAsRead(dialogId, message.messageOwner.random_id, Integer.MIN_VALUE);
        } else {
            controller.markMessageAsRead2(dialogId, mid, null, 0, 0, false);
        }
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(mid);
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            try {
                MessageHistoryStore.getInstance().forgetMessages(account, dialogId, ids);
            } catch (Exception error) {
                FileLog.e(error);
            }
            storage.emptyMessagesMedia(dialogId, ids);
        });
    }
}
