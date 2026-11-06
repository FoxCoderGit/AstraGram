import org.telegram.messenger.GhostReactionPolicy;
import org.telegram.messenger.GhostReactionEvents;
import org.telegram.tgnet.TLRPC;

public class GhostReactionPolicyTest {
    private static final long SELF = 100;
    private static int checks;

    private static TLRPC.Peer peer(long id) {
        TLRPC.Peer peer = new TLRPC.Peer();
        peer.user_id = id;
        return peer;
    }

    private static TLRPC.MessagePeerReaction reaction(long actor, String emoji, int date, boolean unread) {
        TLRPC.MessagePeerReaction result = new TLRPC.MessagePeerReaction();
        result.peer_id = peer(actor);
        result.date = date;
        result.unread = unread;
        TLRPC.TL_reactionEmoji value = new TLRPC.TL_reactionEmoji();
        value.emoticon = emoji;
        result.reaction = value;
        return result;
    }

    private static TLRPC.TL_messageReactions reactions(TLRPC.MessagePeerReaction... items) {
        TLRPC.TL_messageReactions result = new TLRPC.TL_messageReactions();
        java.util.Collections.addAll(result.recent_reactions, items);
        return result;
    }

    private static void expect(int count, TLRPC.Message message, TLRPC.TL_messageReactions old,
            TLRPC.TL_messageReactions current) {
        int actual = GhostReactionPolicy.newReactions(SELF, message, old, current).size();
        if (actual != count) throw new AssertionError("Case " + checks + ": expected " + count + ", got " + actual);
        checks++;
    }

    public static void main(String[] args) {
        TLRPC.Message outgoing = new TLRPC.Message();
        outgoing.id = 7;
        outgoing.peer_id = peer(200);
        outgoing.out = true;
        TLRPC.TL_messageReactions first = reactions(reaction(200, "👍", 10, true));
        expect(1, outgoing, null, first);
        expect(0, outgoing, first, first); // Replayed live update.
        expect(0, outgoing, first, reactions(reaction(200, "👍", 10, false))); // Read elsewhere.
        expect(0, outgoing, reactions(reaction(200, "👍", 10, false)), first);
        expect(1, outgoing, first, reactions(reaction(200, "🔥", 11, true))); // Replaced reaction.
        expect(1, outgoing, first, reactions(reaction(200, "👍", 12, true))); // Removed and re-added.
        expect(0, outgoing, first, reactions()); // Removal.
        expect(0, outgoing, null, reactions(reaction(SELF, "👍", 10, true)));
        expect(2, outgoing, null, reactions(reaction(200, "👍", 10, true), reaction(300, "🔥", 11, true)));
        outgoing.out = false;
        expect(0, outgoing, null, first); // Somebody else's message.
        outgoing.out = true;
        outgoing.peer_id = peer(SELF);
        expect(0, outgoing, null, first); // Saved-message tags.
        outgoing.peer_id = peer(200);
        outgoing.id = -1;
        expect(0, outgoing, null, first); // Unsent local message.
        outgoing.id = 7;
        expect(0, outgoing, null, null);
        expect(0, null, null, first);
        TLRPC.TL_reactionCustomEmoji custom = new TLRPC.TL_reactionCustomEmoji();
        custom.document_id = 12345;
        TLRPC.MessagePeerReaction customReaction = reaction(200, "", 20, true);
        customReaction.reaction = custom;
        TLRPC.TL_messageReactions customUpdate = reactions(customReaction);
        expect(1, outgoing, first, customUpdate);
        expect(0, outgoing, customUpdate, customUpdate);
        TLRPC.TL_messageReactions partial = reactions(reaction(200, "🔥", 21, true));
        partial.min = true;
        expect(1, outgoing, first, partial); // Explicit unread reaction in a partial update.
        expect(0, outgoing, first, reactions(reaction(200, "👍", 9, true))); // Stale update.
        TLRPC.TL_messageReactions readLive = reactions(reaction(200, "🔥", 100, false));
        if (GhostReactionPolicy.newReactions(SELF, outgoing, null, readLive, 110).size() != 1)
            throw new AssertionError("A fresh live reaction read by another session must notify");
        checks++;
        if (!GhostReactionPolicy.newReactions(SELF, outgoing, null, readLive, 131).isEmpty())
            throw new AssertionError("Do not replay old read reactions");
        checks++;
        if (GhostReactionPolicy.newReactions(SELF, outgoing, readLive, readLive, 110).size() != 1)
            throw new AssertionError("A cached reaction is not proof of notification delivery");
        checks++;
        if (!GhostReactionPolicy.newReactions(SELF, outgoing, null, reactions(reaction(200, "🔥", 140, false)), 110).isEmpty())
            throw new AssertionError("Reject a read reaction with an implausible future date");
        checks++;
        if (GhostReactionPolicy.isChatVisible(true, true, 200, 200))
            throw new AssertionError("A stale opened-dialog ID must not suppress background notifications");
        checks++;
        if (!GhostReactionPolicy.isChatVisible(false, true, 200, 200))
            throw new AssertionError("Suppress system alerts only while the target chat is actually visible");
        checks++;
        if (GhostReactionPolicy.isChatVisible(false, false, 200, 200)
                || GhostReactionPolicy.isChatVisible(false, true, 300, 200))
            throw new AssertionError("Screen off or another chat must allow notification delivery");
        checks++;
        GhostReactionEvents delivery = new GhostReactionEvents(3, java.util.Collections.emptyList());
        if (delivery.contains("new") || !delivery.record("new") || delivery.record("new"))
            throw new AssertionError("Deduplicate only after delivery has been recorded");
        checks++;
        GhostReactionEvents restarted = new GhostReactionEvents(3, delivery.snapshot());
        if (!restarted.contains("new") || restarted.record("new"))
            throw new AssertionError("Delivery history must survive process restart");
        checks++;
        delivery.record("second"); delivery.record("third"); delivery.record("fourth");
        if (delivery.contains("new") || !delivery.contains("second") || delivery.snapshot().size() != 3)
            throw new AssertionError("Evict only the oldest delivery record when capacity is exceeded");
        checks++;
        if (!delivery.record("other-account:same-message"))
            throw new AssertionError("Different account event keys must remain independent");
        checks++;
        System.out.println("Reaction policy: " + checks + " scenarios passed.");
    }
}
