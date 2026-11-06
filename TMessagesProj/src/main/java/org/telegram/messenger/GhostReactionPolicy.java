package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.Reactions.ReactionsUtils;

import java.util.ArrayList;

/** Select live, unseen reactions to our own messages, before replacing cached reactions. */
public final class GhostReactionPolicy {
    private GhostReactionPolicy() {}

    public static ArrayList<TLRPC.MessagePeerReaction> newReactions(long selfId, TLRPC.Message message,
            TLRPC.TL_messageReactions previous, TLRPC.TL_messageReactions current) {
        return newReactions(selfId, message, previous, current, 0);
    }

    public static boolean isChatVisible(boolean paused, boolean screenOn, long openedDialog, long dialog) {
        return !paused && screenOn && openedDialog == dialog;
    }

    public static ArrayList<TLRPC.MessagePeerReaction> newReactions(long selfId, TLRPC.Message message,
            TLRPC.TL_messageReactions previous, TLRPC.TL_messageReactions current, int liveUpdateTime) {
        ArrayList<TLRPC.MessagePeerReaction> result = new ArrayList<>();
        if (message == null || current == null || message.id <= 0
                || !message.out || MessageObject.getPeerId(message.peer_id) == selfId) {
            return result;
        }
        for (TLRPC.MessagePeerReaction reaction : current.recent_reactions) {
            long actor = MessageObject.getPeerId(reaction.peer_id);
            // Another session may already have read a fresh live reaction. Its date
            // still identifies a recent event; historical snapshots use time=0.
            boolean fresh = liveUpdateTime > 0 && reaction.date >= liveUpdateTime - 30
                    && reaction.date <= liveUpdateTime + 5;
            if ((!reaction.unread && !fresh) || actor == 0 || actor == selfId || reaction.reaction == null) continue;
            boolean known = false;
            // A history/metadata response may have cached the reaction before the
            // live update. Only notification delivery history can deduplicate live events.
            if (previous != null && liveUpdateTime == 0) {
                for (TLRPC.MessagePeerReaction old : previous.recent_reactions) {
                    if (MessageObject.getPeerId(old.peer_id) == actor && old.date >= reaction.date
                            && ReactionsUtils.compare(old.reaction, reaction.reaction)) {
                        known = true;
                        break;
                    }
                }
            }
            if (!known) result.add(reaction);
        }
        return result;
    }
}
