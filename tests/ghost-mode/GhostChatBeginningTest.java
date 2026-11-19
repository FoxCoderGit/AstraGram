import org.telegram.messenger.GhostChatBeginning;
import org.telegram.tgnet.TLRPC;

/** Host test against production protocol classes; never sends requests. */
public final class GhostChatBeginningTest {
    private static int checks;
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); checks++; }
    public static void main(String[] args) {
        TLRPC.TL_inputPeerChat peer = new TLRPC.TL_inputPeerChat();
        peer.chat_id = 123;
        TLRPC.TL_messages_getHistory history = (TLRPC.TL_messages_getHistory) GhostChatBeginning.request(peer, 0, null);
        check(history.peer == peer);
        check(history.offset_id == 1 && history.add_offset == -1 && history.limit == 1);
        check(history.offset_date == 0 && history.min_id == 0 && history.max_id == 0);
        TLRPC.TL_messages_getReplies topic = (TLRPC.TL_messages_getReplies) GhostChatBeginning.request(peer, 456, null);
        check(topic.peer == peer && topic.msg_id == 456);
        check(topic.offset_id == 1 && topic.add_offset == -1 && topic.limit == 1);
        TLRPC.TL_inputPeerUser correspondent = new TLRPC.TL_inputPeerUser();
        correspondent.user_id = 789;
        TLRPC.TL_messages_getSavedHistory saved = (TLRPC.TL_messages_getSavedHistory) GhostChatBeginning.request(peer, 789, correspondent);
        check(saved.peer == correspondent && saved.parent_peer == peer);
        check(saved.offset_id == 1 && saved.add_offset == -1 && saved.limit == 1);
        TLRPC.TL_messages_messages response = new TLRPC.TL_messages_messages();
        check(GhostChatBeginning.first(response) == null);
        TLRPC.TL_messageEmpty deleted = new TLRPC.TL_messageEmpty(); deleted.id = 1;
        response.messages.add(deleted);
        TLRPC.TL_message pending = new TLRPC.TL_message(); pending.id = -1; response.messages.add(pending);
        check(GhostChatBeginning.first(response) == null);
        TLRPC.TL_message later = new TLRPC.TL_message(); later.id = 269; response.messages.add(later);
        check(GhostChatBeginning.first(response) == later);
        TLRPC.TL_message earliest = new TLRPC.TL_message(); earliest.id = 17; response.messages.add(earliest);
        check(GhostChatBeginning.first(response) == earliest);
        System.out.println("Chat beginning: " + checks + " checks passed (real protocol classes; no network/accounts).");
    }
}
