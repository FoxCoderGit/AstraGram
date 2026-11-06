package org.telegram.messenger;

/** Packed original-creator hint, matching extractOwnerId in the inspected AyuGram APK. */
public final class GhostStickerOwnerId {
    private GhostStickerOwnerId() { }

    public static long extract(long stickerSetId) {
        long owner = stickerSetId >> 32;
        if (((stickerSetId >> 16) & 0xffL) == 0x3fL) {
            owner |= 0x80000000L;
        }
        if (((stickerSetId >> 24) & 0xffL) != 0) {
            owner += 0x100000000L;
        }
        return owner;
    }
}
