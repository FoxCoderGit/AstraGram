package org.telegram.messenger;

/** Local policy: do not fetch or display Telegram's sponsored placements. */
public final class GhostAds {
    private GhostAds() {}
    public static boolean hidden() { return true; }
}
