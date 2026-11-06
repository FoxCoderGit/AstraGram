package org.telegram.messenger;

import android.util.Log;

/** Opt-in device tracing; no account, peer, message IDs or content. */
public final class GhostRoundSeekTrace {
    private GhostRoundSeekTrace() { }
    public static boolean enabled() {
        return Log.isLoggable("GhostRoundSeek", Log.DEBUG);
    }
    public static void write(String event) {
        if (enabled()) {
            Log.d("GhostRoundSeek", event);
        }
    }
}
