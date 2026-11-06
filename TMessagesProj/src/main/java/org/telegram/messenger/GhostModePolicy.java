package org.telegram.messenger;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

/**
 * Runs before serialization, including requests from notification actions.
 * Request categories and offline rewriting follow AyuGram4A's request hook
 * (7013145676d36d82ee13c02a89f72097b7490dcd), adapted to Telegram 12.10.6.
 * See docs/ghost-mode.md for upstream attribution and behavioral differences.
 */
public final class GhostModePolicy {
    private GhostModePolicy() {}

    public static boolean shouldSuppress(TLObject request, boolean enabled) {
        return shouldSuppress(request, enabled, enabled, enabled, enabled);
    }

    public static boolean shouldSuppress(TLObject request, boolean read, boolean stories, boolean online, boolean typing) {
        if (request instanceof TL_account.updateStatus) return online && !((TL_account.updateStatus) request).offline;
        if (request instanceof TLRPC.TL_messages_setTyping || request instanceof TLRPC.TL_messages_setEncryptedTyping) return typing;
        if (request instanceof TL_stories.TL_stories_readStories || request instanceof TL_stories.TL_stories_incrementStoryViews) return stories;
        return read && (request instanceof TLRPC.TL_messages_readHistory
                || request instanceof TLRPC.TL_channels_readHistory
                || request instanceof TLRPC.TL_messages_readDiscussion
                || request instanceof TLRPC.TL_messages_readSavedHistory
                || request instanceof TLRPC.TL_messages_readMessageContents
                || request instanceof TLRPC.TL_channels_readMessageContents
                || request instanceof TLRPC.TL_messages_readEncryptedHistory);
    }
}
