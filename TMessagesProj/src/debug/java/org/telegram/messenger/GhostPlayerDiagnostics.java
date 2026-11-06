package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.VideoPlayer;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Play a local H.264/AAC fixture through the actual player and Android audio sink. */
public class GhostPlayerDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void main(Runnable runnable) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { runnable.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new Exception("Player UI setup failed", error.get());
    }
    private static class Preview extends BaseFragment {
        TextureView texture;
        @Override public View createView(Context context) {
            actionBar.setTitle("Playback check");
            FrameLayout layout = new FrameLayout(context);
            texture = new TextureView(context);
            layout.addView(texture, new FrameLayout.LayoutParams(-1, -1));
            return fragmentView = layout;
        }
    }
    @Override public void onStart() {
        Bundle report = new Bundle();
        boolean boost = GhostPlayback.boost, skip = GhostPlayback.skipSilence;
        VideoPlayer[] playing = new VideoPlayer[1];
        Preview[] preview = new Preview[1];
        Throwable failure = null;
        try {
            main(ApplicationLoader::postInitApplication);
            File fixture = new File(getTargetContext().getFilesDir(), "ghost_media_tests/player.mp4");
            if (!fixture.isFile()) throw new AssertionError("Local MP4 fixture missing");
            LaunchActivity activity = (LaunchActivity) startActivitySync(new Intent(getTargetContext(), LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            preview[0] = new Preview();
            main(() -> { if (!activity.presentFragment(preview[0], false, true)) throw new AssertionError("Preview navigation failed"); });
            waitForIdleSync();
            for (int mode = 0; mode < 3; mode++) {
                CountDownLatch ended = new CountDownLatch(1);
                AtomicReference<Exception> error = new AtomicReference<>();
                AtomicBoolean rendered = new AtomicBoolean();
                final int currentMode = mode;
                main(() -> {
                    GhostPlayback.boost = currentMode > 0;
                    GhostPlayback.skipSilence = currentMode == 2;
                    VideoPlayer player = playing[0] = new VideoPlayer();
                    player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                        @Override public void onStateChanged(boolean ready, int state) { if (state == androidx.media3.common.Player.STATE_ENDED) ended.countDown(); }
                        @Override public void onError(VideoPlayer p, Exception e) { error.set(e); ended.countDown(); }
                        @Override public void onVideoSizeChanged(int width, int height, int rotation, float ratio) {}
                        @Override public void onRenderedFirstFrame() { rendered.set(true); }
                    });
                    player.setTextureView(preview[0].texture);
                    player.preparePlayer(Uri.fromFile(fixture), "other");
                    player.setVolume(0);
                    player.play();
                });
                if (!ended.await(15, TimeUnit.SECONDS)) throw new AssertionError("Playback stalled, mode=" + mode);
                if (error.get() != null) throw error.get();
                if (!rendered.get()) throw new AssertionError("Video produced no frame, mode=" + mode);
                main(() -> { playing[0].releasePlayer(false); playing[0] = null; });
                report.putString("mode_" + mode, "PASS: H.264/AAC video rendered and reached end; boost=" + (mode > 0) + ", skip=" + (mode == 2));
            }
        } catch (Throwable error) { failure = error; }
        finally {
            try {
                main(() -> {
                    if (playing[0] != null) playing[0].releasePlayer(false);
                    GhostPlayback.boost = boost; GhostPlayback.skipSilence = skip;
                    if (preview[0] != null) preview[0].finishFragment(false);
                });
            } catch (Throwable error) { if (failure == null) failure = error; }
        }
        if (failure != null) report.putString("error", android.util.Log.getStackTraceString(failure));
        finish(failure == null ? -1 : 0, report);
    }
}
