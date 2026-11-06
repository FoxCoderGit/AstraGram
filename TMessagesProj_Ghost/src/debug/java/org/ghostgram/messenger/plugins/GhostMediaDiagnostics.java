package org.ghostgram.messenger.plugins;

import android.app.Instrumentation;
import android.os.Bundle;
import android.media.MediaMetadataRetriever;
import org.telegram.messenger.*;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import java.io.File;
import java.util.ArrayList;

/** Local fixture conversion only. Does not send messages. */
public class GhostMediaDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            runOnMainSync(() -> ApplicationLoader.postInitApplication());
            File directory = new File(ApplicationLoader.applicationContext.getFilesDir(), "ghost_media_tests");
            directory.mkdirs();
            File audio = new File(directory, "stereo.mp3");
            File output = new File(directory, "voice.ogg");
            long duration = GhostMediaTools.encodeVoice(audio.getAbsolutePath(), output.getAbsolutePath());
            if (duration < 2300 || duration > 2450 || MediaController.isOpusFile(output.getAbsolutePath()) != 1) throw new AssertionError("Invalid Opus voice: " + duration);
            report.putString("voice", "PASS: stereo MP3 to mono Ogg/Opus, duration=" + duration + "ms");
            VideoEditedInfo longVideo = new VideoEditedInfo();
            longVideo.originalPath = "local-fixture.mp4";
            longVideo.originalWidth = longVideo.originalHeight = longVideo.resultWidth = longVideo.resultHeight = 480;
            longVideo.estimatedDuration = 125000;
            longVideo.estimatedSize = 100000;
            longVideo.startTime = 2500000;
            longVideo.roundVideo = true;
            ArrayList<VideoEditedInfo> parts = GhostMediaTools.splitRoundVideo(longVideo);
            long cursor = longVideo.startTime;
            for (VideoEditedInfo part : parts) {
                if (part.startTime != cursor || part.estimatedDuration > 59000 || part.estimatedDuration < 1000) throw new AssertionError("Invalid part boundary");
                cursor = part.endTime;
            }
            if (parts.size() != 3 || cursor != 127500000) throw new AssertionError("Split lost duration");
            report.putString("split", "PASS: 125 seconds, three contiguous parts");
            File video = new File(directory, "landscape.mp4");
            VideoEditedInfo info = GhostMediaTools.roundVideo(video.getAbsolutePath());
            info.startTime = 500000;
            info.endTime = 2500000;
            info.estimatedDuration = 2000;
            File round = new File(directory, "round.mp4");
            MediaCodecVideoConvertor.ConvertVideoParams params = MediaCodecVideoConvertor.ConvertVideoParams.of(
                    info.originalPath, round, 0, info.rotationValue, false,
                    info.originalWidth, info.originalHeight, info.resultWidth, info.resultHeight,
                    info.framerate, info.bitrate, info.originalBitrate, info.startTime, info.endTime, -1,
                    true, info.estimatedDuration * 1000, new MediaController.VideoConvertorListener() {
                        public boolean checkConversionCanceled() { return false; }
                        public void didWriteData(long size, float progress) { }
                    }, info);
            if (new MediaCodecVideoConvertor().convertVideo(params)) throw new AssertionError("Round conversion failed");
            MediaMetadataRetriever reader = new MediaMetadataRetriever();
            try {
                reader.setDataSource(round.getAbsolutePath());
                int width = Integer.parseInt(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int height = Integer.parseInt(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                int milliseconds = Integer.parseInt(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                if (width != 480 || height != 480 || milliseconds < 1800 || milliseconds > 2200) throw new AssertionError("Incorrect round output: " + width + "x" + height + ", " + milliseconds);
                report.putString("video", "PASS: centre crop, 480x480, trimmed duration=" + milliseconds + "ms");
            } finally { reader.release(); }
            finish(-1, report);
        } catch (Throwable error) {
            report.putString("error", android.util.Log.getStackTraceString(error));
            finish(0, report);
        }
    }
}
