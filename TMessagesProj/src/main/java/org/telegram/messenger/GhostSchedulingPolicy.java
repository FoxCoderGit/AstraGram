package org.telegram.messenger;

/** Scheduling must stay beyond Telegram's near-now immediate-send window. */
public final class GhostSchedulingPolicy {
    private GhostSchedulingPolicy() { }
    public static int delay(long bytes) {
        return (int) Math.min(86400, Math.max(12, Math.ceil(Math.max(0L, bytes) / 1048576d * 4.5d)));
    }
    public static int deadline(int now, long bytes) { return now + delay(bytes); }
    public static int refresh(int date, int now) { return Math.max(date, now + 12); }
    public static boolean tooSoon(int date, int now) { return date <= now + 10; }
}
