package org.telegram.messenger;

import java.util.ArrayList;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;

/** Explicit remote deletion which deliberately leaves this device's database intact. */
public final class GhostMessageActions {
    public static boolean canRevoke(TLRPC.User user, long selfId) {
        return user != null && !user.bot && !UserObject.isDeleted(user) && user.id != selfId
                && !GhostSimulationPolicy.virtual(user.id);
    }

    public static void deleteRemoteKeepLocal(BaseFragment fragment, long dialogId, ArrayList<Integer> ids) {
        int account = fragment.getCurrentAccount();
        MessagesController controller = MessagesController.getInstance(account);
        if (!canRevoke(controller.getUser(dialogId), UserConfig.getInstance(account).getClientUserId())) return;
        ArrayList<Integer> positive = new ArrayList<>();
        for (Integer id : ids) if (id != null && id > 0) positive.add(id);
        for (int start = 0; start < positive.size(); start += 100) {
            TLRPC.TL_messages_deleteMessages request = new TLRPC.TL_messages_deleteMessages();
            request.id = new ArrayList<>(positive.subList(start, Math.min(start + 100, positive.size())));
            request.revoke = true;
            controller.getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error != null) {
                    if (fragment.getParentActivity() != null) AlertsCreator.showSimpleAlert(fragment, error.text);
                    return;
                }
                if (!(response instanceof TLRPC.TL_messages_affectedMessages)) return;
                TLRPC.TL_messages_affectedMessages result = (TLRPC.TL_messages_affectedMessages) response;
                MessageHistoryStore.getInstance().markDeleted(account, dialogId, request.id);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messagesDeleted, request.id, 0L, false);
                Utilities.stageQueue.postRunnable(() -> controller.processNewDifferenceParams(-1, result.pts, -1, result.pts_count));
            }));
        }
    }

    /** A detached view copy: opening the rectangular frame never alters the real round document. */
    public static MessageObject fullFrame(MessageObject source) throws Exception {
        if (source == null || source.messageOwner == null || !source.isRoundVideo()) throw new IllegalArgumentException("Not a round video");
        NativeByteBuffer data = new NativeByteBuffer(source.messageOwner.getObjectSize());
        try {
            source.messageOwner.serializeToStream(data);
            data.position(0);
            TLRPC.Message copy = TLRPC.Message.TLdeserialize(data, data.readInt32(true), true);
            TLRPC.Document document = MessageObject.getDocument(copy);
            if (document == null) throw new IllegalArgumentException("Round video document is unavailable");
            for (TLRPC.DocumentAttribute attribute : document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                    attribute.round_message = false;
                    attribute.flags &= ~1;
                }
            }
            copy.media.round = false;
            copy.media.video = true;
            copy.attachPath = source.messageOwner.attachPath;
            return new MessageObject(source.currentAccount, copy, false, true);
        } finally { data.reuse(); }
    }
}
