import org.telegram.messenger.GhostStickerOwnerId;

public class GhostStickerOwnerIdTest {
    private static int checks;
    private static void check(long packed, long expected) {
        long actual = GhostStickerOwnerId.extract(packed);
        if (actual != expected) throw new AssertionError(packed + ": " + actual + " != " + expected);
        checks++;
    }
    public static void main(String[] args) {
        check(0, 0);
        check((1518223313L << 32) | 0x1234L, 1518223313L);
        check((123456789L << 32) | 0x003f0000L, 2270940437L);
        check((123456789L << 32) | 0x01000000L, 4418424085L);
        check((123456789L << 32) | 0x013f0000L, 6565907733L);
        check((123456789L << 32) | 0xff3f0000L, 6565907733L);
        check((123456789L << 32) | 0x003e0000L, 123456789L);
        check((123456789L << 32) | 0x003f0001L, 2270940437L);
        check((123456789L << 32) | 0x01ff0000L, 4418424085L);
        check(Long.MIN_VALUE, -2147483648L);
        check(-1L, 4294967295L);
        System.out.println("PASS: " + checks + " packed creator-ID checks");
    }
}
