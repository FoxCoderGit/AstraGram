import org.telegram.messenger.GhostBadgeRegistry;
import java.util.*;

/** Tests identity isolation, snapshot ownership and invalid assignments using synthetic IDs. */
public final class GhostBadgeRegistrySmoke {
    private static int checks;
    static void check(boolean value) { if (!value) throw new AssertionError("Badge case " + (checks + 1)); checks++; }
    public static void main(String[] args) {
        GhostBadgeRegistry empty = new GhostBadgeRegistry(Collections.emptyMap());
        check(empty.user(963080346L) == null); // Previous upstream default must not inherit our developer badge.
        Map<Long, GhostBadgeRegistry.Type> values = new HashMap<>();
        values.put(11L, GhostBadgeRegistry.Type.DEVELOPER);
        values.put(22L, GhostBadgeRegistry.Type.SUPPORTER);
        values.put(5330087923L, GhostBadgeRegistry.Type.SUPPORTER);
        GhostBadgeRegistry registry = new GhostBadgeRegistry(values);
        values.clear(); // Registry owns an immutable snapshot, independent of caller mutations.
        check(registry.user(11).type == GhostBadgeRegistry.Type.DEVELOPER);
        check(registry.user(22).type == GhostBadgeRegistry.Type.SUPPORTER);
        check(registry.user(5330087923L).type == GhostBadgeRegistry.Type.SUPPORTER);
        check(registry.user(33) == null);
        check(registry.user(-11) == null);
        check(empty.user(11) == null); // Empty replacement revokes previous assignments.
        check(GhostBadgeRegistry.isLocal(registry.user(11).documentId));
        check(GhostBadgeRegistry.isLocal(registry.user(22).documentId));
        check(!GhostBadgeRegistry.isLocal(5390820689676633124L));
        for (long id : new long[]{0, -1}) {
            try { new GhostBadgeRegistry(Collections.singletonMap(id, GhostBadgeRegistry.Type.DEVELOPER)); throw new AssertionError(); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        try { new GhostBadgeRegistry(Collections.singletonMap(11L, null)); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("PASS: " + checks + " GhostGram badge checks");
    }
}
