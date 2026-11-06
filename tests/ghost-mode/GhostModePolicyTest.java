import org.telegram.messenger.GhostModePolicy;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

public class GhostModePolicyTest {
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        TLObject[] privateActivity = {
                new TLRPC.TL_messages_setTyping(),
                new TLRPC.TL_messages_setEncryptedTyping(),
                new TLRPC.TL_messages_readHistory(),
                new TLRPC.TL_channels_readHistory(),
                new TLRPC.TL_messages_readDiscussion(),
                new TLRPC.TL_messages_readSavedHistory(),
                new TLRPC.TL_messages_readMessageContents(),
                new TLRPC.TL_channels_readMessageContents(),
                new TLRPC.TL_messages_readEncryptedHistory(),
                new TL_stories.TL_stories_readStories(),
                new TL_stories.TL_stories_incrementStoryViews()
        };
        for (TLObject request : privateActivity) {
            check(!GhostModePolicy.shouldSuppress(request, false), "Disabled mode changed request");
            check(GhostModePolicy.shouldSuppress(request, true), "Activity was allowed: " + request.getClass());
            check(!GhostModePolicy.shouldSuppress(request, false), "Mode cannot be disabled");
        }
        TLObject[] normalTraffic = {
                new TLRPC.TL_messages_sendMessage(),
                new TLRPC.TL_messages_getHistory(),
                new TLRPC.TL_messages_getDialogs(),
                new TLObject()
        };
        for (TLObject request : normalTraffic) {
            check(!GhostModePolicy.shouldSuppress(request, true), "Normal traffic blocked");
        }
        TL_account.updateStatus status = new TL_account.updateStatus();
        check(!GhostModePolicy.shouldSuppress(status, false) && !status.offline, "Normal online status changed");
        check(GhostModePolicy.shouldSuppress(status, true) && !status.offline, "Ghost mode leaked online status");
        status.offline = true;
        check(!GhostModePolicy.shouldSuppress(status, true) && status.offline, "Offline status blocked");
        check(GhostModePolicy.shouldSuppress(new TLRPC.TL_messages_readHistory(), true,false,false,false), "Read option ignored");
        check(!GhostModePolicy.shouldSuppress(new TLRPC.TL_messages_readHistory(), false,true,true,true), "Read off ignored");
        check(GhostModePolicy.shouldSuppress(new TL_stories.TL_stories_readStories(), false,true,false,false), "Story option ignored");
        check(!GhostModePolicy.shouldSuppress(new TL_stories.TL_stories_readStories(), true,false,true,true), "Story off ignored");
        check(GhostModePolicy.shouldSuppress(new TLRPC.TL_messages_setTyping(), false,false,false,true), "Typing option ignored");
        check(!GhostModePolicy.shouldSuppress(new TLRPC.TL_messages_setTyping(), true,true,true,false), "Typing off ignored");
        status.offline=false;
        check(GhostModePolicy.shouldSuppress(status,false,false,true,false), "Online option ignored");
        check(!GhostModePolicy.shouldSuppress(status,true,true,false,true), "Online off ignored");
        System.out.println("GhostModePolicy: " + assertions + " assertions passed (protocol type doubles).");
    }
}
