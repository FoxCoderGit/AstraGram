package org.telegram.messenger;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Build a seekable temporary MP4 by copying encoded samples, never by re-encoding. */
public final class GhostRoundSeekSource {
    private GhostRoundSeekSource() { }

    public static File prepare(File source, BooleanSupplier cancelled) throws Exception {
        File output = File.createTempFile("round_seek_", ".mp4", ApplicationLoader.applicationContext.getCacheDir());
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean success = false;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] tracks = new int[extractor.getTrackCount()];
            Arrays.fill(tracks, -1);
            int count = 0;
            int rotation = 0;
            int bufferSize = android.os.Build.VERSION.SDK_INT >= 28 ? 256 * 1024 : 2 * 1024 * 1024;
            for (int i = 0; i < tracks.length; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) continue;
                if (format.containsKey("rotation-degrees")) rotation = format.getInteger("rotation-degrees");
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = Math.max(bufferSize, Math.min(16 * 1024 * 1024, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)));
                }
                tracks[i] = muxer.addTrack(format);
                extractor.selectTrack(i);
                count++;
            }
            if (count == 0) throw new IllegalStateException("No audio/video tracks");
            if (rotation == 90 || rotation == 180 || rotation == 270) muxer.setOrientationHint(rotation);
            muxer.start();
            ByteBuffer data = ByteBuffer.allocateDirect(bufferSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int samples = 0;
            while (extractor.getSampleTrackIndex() >= 0) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                long sampleSize = android.os.Build.VERSION.SDK_INT >= 28 ? extractor.getSampleSize() : -1;
                if (sampleSize > 16 * 1024 * 1024) throw new IllegalStateException("Oversized media sample");
                if (sampleSize > data.capacity()) data = ByteBuffer.allocateDirect((int) sampleSize);
                data.clear();
                int size = extractor.readSampleData(data, 0);
                if (size < 0) break;
                int flags = extractor.getSampleFlags();
                if ((flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw new IllegalStateException("Encrypted media sample");
                // Extractor and codec flags differ; only map the sync-frame bit.
                info.set(0, size, extractor.getSampleTime(), (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(tracks[extractor.getSampleTrackIndex()], data, info);
                samples++;
                if (!extractor.advance()) break;
            }
            if (samples == 0) throw new IllegalStateException("No media samples");
            muxer.stop();
            if (cancelled.getAsBoolean()) throw new CancellationException();
            success = true;
            return output;
        } finally {
            extractor.release();
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (!success) output.delete();
        }
    }
}
