package org.telegram.messenger;

import android.os.SystemClock;
import org.telegram.ui.Components.VideoPlayer;
import java.util.ArrayList;
import java.lang.ref.WeakReference;

/** Playback controls shared by the audio sheet, voice toolbar and video viewer. */
public final class GhostPlayback {
    public static volatile boolean boost = MessagesController.getGlobalMainSettings().getBoolean("ghost_audio_boost", false);
    public static volatile boolean skipSilence = MessagesController.getGlobalMainSettings().getBoolean("ghost_skip_silence", false);
    private static final ArrayList<WeakReference<VideoPlayer>> players = new ArrayList<>();
    private static long deadline;
    private static MessageObject stopAfter;
    private static WeakReference<VideoPlayer> stopAfterPlayer;
    private static boolean restoreLooping;
    private static final Runnable timer = () -> stop();
    private GhostPlayback() {}
    public static void register(VideoPlayer player) {
        players.removeIf(ref -> ref.get() == null || ref.get() == player);
        players.add(new WeakReference<>(player));
    }
    public static void unregister(VideoPlayer player) { players.removeIf(ref -> ref.get() == null || ref.get() == player); }
    public static void toggleBoost() {
        boost = !boost;
        MessagesController.getGlobalMainSettings().edit().putBoolean("ghost_audio_boost", boost).apply();
    }
    public static void toggleSilence() {
        skipSilence = !skipSilence;
        MessagesController.getGlobalMainSettings().edit().putBoolean("ghost_skip_silence", skipSilence).apply();
        for (WeakReference<VideoPlayer> ref : new ArrayList<>(players)) {
            VideoPlayer player = ref.get();
            if (player != null) player.updateGhostPlayback();
        }
    }
    public static void sleep(int minutes) {
        sleep(minutes, null);
    }
    public static void sleep(int minutes, VideoPlayer target) {
        cancelSleep();
        if (minutes > 0) {
            deadline = SystemClock.elapsedRealtime() + minutes * 60000L;
            AndroidUtilities.runOnUIThread(timer, minutes * 60000L);
        } else if (minutes == -1) {
            if (target != null) {
                stopAfterPlayer = new WeakReference<>(target);
                restoreLooping = target.isLooping();
                target.setLooping(false);
            } else stopAfter = MediaController.getInstance().getPlayingMessageObject();
        }
    }
    public static void cancelSleep() {
        VideoPlayer target = stopAfterPlayer == null ? null : stopAfterPlayer.get();
        stopAfterPlayer = null;
        if (target != null && restoreLooping) target.setLooping(true);
        AndroidUtilities.cancelRunOnUIThread(timer); deadline = 0; stopAfter = null;
    }
    public static long sleepRemaining() { return Math.max(0, deadline - SystemClock.elapsedRealtime()); }
    public static boolean afterCurrent() { return stopAfter != null || (stopAfterPlayer != null && stopAfterPlayer.get() != null); }
    public static boolean finishPlayer(VideoPlayer player) {
        if (stopAfterPlayer == null || stopAfterPlayer.get() != player) return false;
        stopAfterPlayer = null;
        cancelSleep();
        player.pause();
        return true;
    }
    public static boolean finishCurrent(MessageObject message) {
        if (stopAfter != null && message != null && stopAfter.currentAccount == message.currentAccount
                && stopAfter.getDialogId() == message.getDialogId() && stopAfter.getId() == message.getId()) {
            cancelSleep(); return true;
        }
        return false;
    }
    private static void stop() {
        cancelSleep();
        MessageObject message = MediaController.getInstance().getPlayingMessageObject();
        if (message != null) MediaController.getInstance().pauseMessage(message);
        for (WeakReference<VideoPlayer> ref : new ArrayList<>(players)) {
            VideoPlayer player = ref.get(); if (player != null) player.pause();
        }
    }
    public static short amplify(short sample) {
        return GhostAudioGain.apply(sample);
    }
}
