import org.telegram.messenger.GhostBadgeRegistry;
import org.telegram.messenger.GhostBadges;
import org.json.JSONException;

public final class GhostBadgeSnapshotSmoke {
    private static int checks;
    static void check(boolean value) { if (!value) throw new AssertionError(); checks++; }
    static void reject(String text) throws Exception {
        try { GhostBadges.parseSnapshot(text); throw new AssertionError("Accepted invalid snapshot"); }
        catch (JSONException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        check(GhostBadges.parseSnapshot("{\"version\":1,\"badges\":[]}").user(11) == null);
        GhostBadgeRegistry registry = GhostBadges.parseSnapshot("{\"version\":1,\"badges\":[{\"user_id\":\"11\",\"type\":\"developer\"},{\"user_id\":\"5330087923\",\"type\":\"supporter\"}]}");
        check(registry.user(11).type == GhostBadgeRegistry.Type.DEVELOPER);
        check(registry.user(5330087923L).type == GhostBadgeRegistry.Type.SUPPORTER);
        check(GhostBadges.parseSnapshot("{\"version\":1,\"badges\":[{\"user_id\":\"11\",\"type\":\"creator\"}]}").user(11).type == GhostBadgeRegistry.Type.DEVELOPER);
        reject(null); reject("oops"); reject("{}"); reject("{\"version\":2,\"badges\":[]}");
        reject("{\"version\":1,\"badges\":{}}");
        for (String id : new String[]{"0", "-11", "@example", "9223372036854775808", "1.2"})
            reject("{\"version\":1,\"badges\":[{\"user_id\":\"" + id + "\",\"type\":\"developer\"}]}");
        reject("{\"version\":1,\"badges\":[{\"user_id\":\"11\",\"type\":\"verified\"}]}");
        reject("{\"version\":1,\"badges\":[{\"user_id\":\"11\",\"type\":\"developer\"},{\"user_id\":\"11\",\"type\":\"supporter\"}]}");
        reject(new String(new char[2 * 1024 * 1024 + 1]).replace('\0', ' '));
        check(registry.user(11).type == GhostBadgeRegistry.Type.DEVELOPER); // Rejected inputs cannot mutate an earlier snapshot.
        System.out.println("PASS: " + checks + " snapshot parser checks");
    }
}
