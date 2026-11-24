package com.exteragram.messenger.utils.chats;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

public final class ChatUtils {
    private static final ChatUtils INSTANCE = new ChatUtils();

    private ChatUtils() {}

    public static ChatUtils getInstance() { return INSTANCE; }

    public void resolveChannel(String username, Utilities.Callback<TLRPC.Chat> callback) {
        AccountInstance account = AccountInstance.getInstance(UserConfig.selectedAccount);
        TLRPC.TL_contacts_resolveUsername request = new TLRPC.TL_contacts_resolveUsername();
        request.username = username != null && username.startsWith("@") ? username.substring(1) : username;
        if (request.username == null || request.username.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> callback.run(null));
            return;
        }
        account.getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            TLRPC.Chat chat = null;
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer peer = (TLRPC.TL_contacts_resolvedPeer) response;
                account.getMessagesController().putUsers(peer.users, false);
                account.getMessagesController().putChats(peer.chats, false);
                if (!peer.chats.isEmpty()) chat = peer.chats.get(0);
            }
            callback.run(chat);
        }));
    }
}
