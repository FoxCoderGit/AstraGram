package org.telegram.messenger;

/** Pure scheduling rules, also used by host-JVM tests. Monday is day zero. */
public final class GhostCloudPolicy {
    private GhostCloudPolicy() { }
    public static boolean inWindow(int day, int minute, boolean scheduled, int days, int start, int end) {
        if (!scheduled) return true;
        if (day < 0 || day > 6 || minute < 0 || minute >= 1440 || days == 0 || start<0 || start>=1440 || end<0 || end>=1440) return false;
        if (start == end) return (days & (1 << day)) != 0;
        if (start < end) return (days & (1 << day)) != 0 && minute >= start && minute < end;
        if (minute >= start) return (days & (1 << day)) != 0;
        return minute < end && (days & (1 << ((day + 6) % 7))) != 0;
    }
    public static long retryDelay(int attempts, String error) {
        if (error != null && (error.startsWith("FLOOD_WAIT_") || error.startsWith("FLOOD_PREMIUM_WAIT_") || error.startsWith("SLOWMODE_WAIT_"))) {
            try { return Math.addExact(Math.multiplyExact(Math.max(0,Long.parseLong(error.substring(error.lastIndexOf('_') + 1))), 1000L),1000L); }
            catch (Exception ignored) { return 24 * 60 * 60 * 1000L; }
        }
        return Math.min(6 * 60 * 60 * 1000L, 15000L << Math.min(10, Math.max(0, attempts)));
    }
}
