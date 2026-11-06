package org.telegram.ui.Components;

import android.content.Context;
import org.telegram.messenger.GhostPlayback;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

public final class GhostPlayerMenu {
    private GhostPlayerMenu() {}
    private static String label(int resource) { return LocaleController.getString(resource); }
    public static void show(Context context, Theme.ResourcesProvider provider) {
        show(context, provider, null);
    }
    public static void show(Context context, Theme.ResourcesProvider provider, VideoPlayer target) {
        String sleep = label(R.string.GhostSleepTimer);
        if (GhostPlayback.afterCurrent()) sleep += " · " + label(R.string.GhostAfterCurrent);
        else if (GhostPlayback.sleepRemaining() > 0) sleep += " · " + ((GhostPlayback.sleepRemaining() + 59999) / 60000) + " " + label(R.string.GhostMinutes);
        new AlertDialog.Builder(context, provider).setTitle(label(R.string.GhostPlayerTools)).setItems(new CharSequence[]{
            (GhostPlayback.boost ? "✓ " : "") + label(R.string.GhostAudioBoost),
            (GhostPlayback.skipSilence ? "✓ " : "") + label(R.string.GhostSkipSilence) + " · голосовые", sleep
        }, (dialog, which) -> {
            if (which == 0) GhostPlayback.toggleBoost();
            else if (which == 1) GhostPlayback.toggleSilence();
            else sleep(context, provider, target);
        }).show();
    }
    private static void sleep(Context context, Theme.ResourcesProvider provider, VideoPlayer target) {
        new AlertDialog.Builder(context, provider).setTitle(label(R.string.GhostSleepTimer)).setItems(new CharSequence[]{
            "15 " + label(R.string.GhostMinutes), "30 " + label(R.string.GhostMinutes), "60 " + label(R.string.GhostMinutes),
            label(R.string.GhostAfterCurrent), label(R.string.GhostDisableTimer)
        }, (dialog, which) -> {
            if (which == 4) GhostPlayback.cancelSleep();
            else GhostPlayback.sleep(which == 3 ? -1 : new int[]{15,30,60}[which], target);
        }).show();
    }
}
