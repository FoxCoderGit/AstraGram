package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.util.ArrayList;

/** Local media preparation; source files are never modified. */
public final class GhostMediaTools {
    public static final long ROUND_PART_MS = 59000;
    public static final long RECORDING_LIMIT_MS = 10 * 60 * 1000;
    public static final DispatchQueue preparationQueue = new DispatchQueue("ghostMediaPreparation");
    static { NativeLoader.initNativeLibs(ApplicationLoader.applicationContext); }
    public static native long encodeVoice(String source, String destination);
    private static native long encodeSilentVoice(String destination, int seconds);

    public static ArrayList<VideoEditedInfo> splitRoundVideo(VideoEditedInfo source) {
        ArrayList<VideoEditedInfo> parts = new ArrayList<>();
        long startUs = Math.max(0, source.startTime);
        long durationMs = source.estimatedDuration;
        long count = Math.max(1, (durationMs + ROUND_PART_MS - 1) / ROUND_PART_MS);
        long partDurationMs = (durationMs + count - 1) / count;
        for (long offsetMs = 0; offsetMs < durationMs; offsetMs += partDurationMs) {
            long lengthMs = Math.min(partDurationMs, durationMs - offsetMs);
            VideoEditedInfo part = new VideoEditedInfo();
            if (!part.parseString(source.getString())) throw new IllegalArgumentException("Cannot copy video settings");
            part.roundVideo = true;
            part.originalPath = source.originalPath;
            part.originalDuration = source.originalDuration;
            part.startTime = startUs + offsetMs * 1000;
            part.endTime = part.startTime + lengthMs * 1000;
            part.estimatedDuration = lengthMs;
            part.estimatedSize = Math.max(1, source.estimatedSize * lengthMs / Math.max(1, durationMs));
            part.bitrate = source.bitrate > 0 ? source.bitrate : 1000000;
            parts.add(part);
        }
        return parts;
    }

    public static VideoEditedInfo roundVideo(String path) {
        VideoEditedInfo info = SendMessagesHelper.createCompressionSettings(path, 0);
        if (info == null || info.originalWidth <= 0 || info.originalHeight <= 0) throw new IllegalArgumentException("Unsupported video");
        info.roundVideo = true;
        info.resultWidth = info.resultHeight = 480;
        info.bitrate = 1000000;
        info.framerate = Math.max(1, Math.min(30, info.framerate));
        info.cropState = new MediaController.CropState();
        int side = Math.min(info.originalWidth, info.originalHeight);
        info.cropState.cropPw = (float) side / info.originalWidth;
        info.cropState.cropPh = (float) side / info.originalHeight;
        info.cropState.transformWidth = info.cropState.transformHeight = 480;
        return info;
    }

    public static MessageObject voiceMessage(int account, File source) throws Exception {
        File output = File.createTempFile("ghost_voice_", ".ogg", FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO));
        long durationMs = encodeVoice(source.getAbsolutePath(), output.getAbsolutePath());
        if (durationMs <= 0) {
            output.delete();
            throw new IllegalArgumentException("Cannot decode audio");
        }
        return voiceDocument(account, output, durationMs, MediaController.getWaveform(output.getAbsolutePath()));
    }

    public static byte[] packWaveform(int[] levels) {
        if (levels == null || levels.length != 100) throw new IllegalArgumentException("Expected 100 waveform levels");
        byte[] packed = new byte[(levels.length * 5 + 7) / 8];
        for (int i = 0; i < levels.length; i++) {
            int value = Math.max(0, Math.min(31, levels[i]));
            int position = i * 5;
            int byteIndex = position / 8;
            int offset = position % 8;
            int spill = Math.max(0, 5 - (8 - offset));
            // Match the stock Android SeekBarWaveform reader, including its
            // ordering of levels which straddle two bytes.
            packed[byteIndex] |= (byte) ((value >> spill) << offset);
            if (spill > 0) packed[byteIndex + 1] |= (byte) (value & ((1 << spill) - 1));
        }
        return packed;
    }

    public static MessageObject drawnVoiceMessage(int account, int seconds, int[] levels) throws Exception {
        byte[] waveform = packWaveform(levels);
        File output = File.createTempFile("ghost_drawing_", ".ogg", FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO));
        long duration = encodeSilentVoice(output.getAbsolutePath(), seconds);
        if (duration <= 0) {
            output.delete();
            throw new IllegalStateException("Cannot create silent voice message");
        }
        return voiceDocument(account, output, duration, waveform);
    }

    private static MessageObject voiceDocument(int account, File output, long durationMs, byte[] waveform) {
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.file_reference = new byte[0];
        document.mime_type = "audio/ogg";
        document.size = output.length();
        TLRPC.TL_documentAttributeAudio attribute = new TLRPC.TL_documentAttributeAudio();
        attribute.voice = true;
        attribute.duration = durationMs / 1000.0;
        attribute.waveform = waveform;
        if (attribute.waveform != null) attribute.flags |= 4;
        document.attributes.add(attribute);
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = "";
        message.out = true;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = UserConfig.getInstance(account).getClientUserId();
        message.attachPath = output.getAbsolutePath();
        message.media = new TLRPC.TL_messageMediaDocument();
        message.media.flags |= 3;
        message.media.document = document;
        return new MessageObject(account, message, false, false);
    }
}
