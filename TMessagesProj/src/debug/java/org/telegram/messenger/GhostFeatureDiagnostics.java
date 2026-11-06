package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import org.telegram.ui.GhostDownloadsActivity;
import org.telegram.ui.GhostFloatingChatsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Components.VideoPlayer;

/** Isolated policy, timer and UI checks. Does not send messages or change user settings. */
public class GhostFeatureDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            runOnMainSync(ApplicationLoader::postInitApplication);
            for (Boolean selected : new Boolean[]{null, false, true}) {
                check(!GhostFloatingChats.matches(0, selected), "Off mode admitted chat");
                check(GhostFloatingChats.matches(1, selected) == !Boolean.FALSE.equals(selected), "All mode override");
                check(GhostFloatingChats.matches(2, selected) == Boolean.TRUE.equals(selected), "Selected mode override");
            }
            check(!GhostFloatingChats.enabled(UserConfig.selectedAccount, -1), "Group admitted");
            check(!GhostFloatingChats.enabled(UserConfig.selectedAccount, UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId()), "Self admitted");
            report.putString("bubble_policy", "PASS: off/all/selected policies, explicit exclusions, self and groups rejected");
            runOnMainSync(() -> {
                VideoPlayer fixture = new VideoPlayer();
                fixture.setLooping(true);
                GhostPlayback.sleep(-1, fixture);
                check(GhostPlayback.afterCurrent() && !fixture.isLooping(), "After-file timer did not disable repeat");
                GhostPlayback.cancelSleep();
                check(!GhostPlayback.afterCurrent() && fixture.isLooping(), "Cancel did not restore repeat");
                GhostPlayback.sleep(15);
                check(GhostPlayback.sleepRemaining() > 899000, "15 minute deadline");
                GhostPlayback.cancelSleep();
                check(GhostPlayback.sleepRemaining() == 0, "Timer did not cancel");
                fixture.releasePlayer(false);
            });
            report.putString("sleep_timer", "PASS: 15-minute deadline; video after-file repeat disabled; cancel restores repeat");
            org.telegram.tgnet.TLRPC.TL_message owner = new org.telegram.tgnet.TLRPC.TL_message();
            owner.id = -2146000999;
            owner.peer_id = new org.telegram.tgnet.TLRPC.TL_peerUser(); owner.peer_id.user_id = 2146000999L;
            owner.media = new org.telegram.tgnet.TLRPC.TL_messageMediaDocument();
            owner.media.document = new org.telegram.tgnet.TLRPC.TL_document();
            owner.media.document.id = 214600099999L; owner.media.document.dc_id = 2; owner.media.document.mime_type = "application/octet-stream";
            owner.message = "";
            MessageObject fixture = new MessageObject(UserConfig.selectedAccount, owner, false, false);
            DownloadController controller = DownloadController.getInstance(UserConfig.selectedAccount);
            String pauseKey = "ghost_download_paused_" + fixture.getFileName();
            try {
                runOnMainSync(() -> {
                    MessagesController.getMainSettings(UserConfig.selectedAccount).edit().putBoolean(pauseKey, true).commit();
                    controller.downloadingFiles.add(fixture);
                    controller.onDownloadFail(fixture, 1);
                });
                waitForIdleSync();
                runOnMainSync(() -> check(controller.downloadingFiles.contains(fixture), "Paused file vanished from downloads"));
            } finally {
                runOnMainSync(() -> {
                    controller.downloadingFiles.remove(fixture);
                    MessagesController.getMainSettings(UserConfig.selectedAccount).edit().remove(pauseKey).commit();
                });
            }
            report.putString("download_pause", "PASS: cancel callback preserves a paused file in the download list; isolated fixture removed");
            LaunchActivity activity = (LaunchActivity) startActivitySync(new Intent(getTargetContext(), LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            GhostDownloadsActivity downloads = new GhostDownloadsActivity(UserConfig.selectedAccount);
            runOnMainSync(() -> check(activity.presentFragment(downloads, false, true), "Downloads navigation failed"));
            waitForIdleSync();
            runOnMainSync(() -> check(downloads.listView != null && downloads.listView.adapter.getItemCount() >= 4, "Downloads screen empty"));
            runOnMainSync(() -> downloads.finishFragment(false));
            GhostFloatingChatsActivity bubbles = new GhostFloatingChatsActivity(UserConfig.selectedAccount);
            runOnMainSync(() -> check(activity.presentFragment(bubbles, false, true), "Bubbles navigation failed"));
            waitForIdleSync();
            runOnMainSync(() -> check(bubbles.listView != null && bubbles.listView.adapter.getItemCount() >= 11, "Bubbles settings empty"));
            runOnMainSync(() -> bubbles.finishFragment(false));
            report.putString("screens", "PASS: native downloads and floating-chat settings open and populate on the device");
            finish(-1, report);
        } catch (Throwable error) {
            report.putString("error", android.util.Log.getStackTraceString(error));
            finish(0, report);
        }
    }
}
