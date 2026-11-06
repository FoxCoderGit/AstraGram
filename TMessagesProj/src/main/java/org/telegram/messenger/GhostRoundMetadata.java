package org.telegram.messenger;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Approximate metadata heuristics from Round Client Detector (@netconfiger). Never protocol data. */
public final class GhostRoundMetadata {
    public static String tag(String filename) { return tag(filename, null); }
    public static String tag(String filename, byte[] header) {
        String name = filename == null ? "" : filename.trim().toLowerCase(Locale.ROOT);
        String tag = name.equals("video.mp4") ? "iOS"
                : name.startsWith("round_") || name.contains("desktop") ? "Win"
                : name.isEmpty() ? "Android" : "custom";
        if (header != null && header.length >= 12 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
            String bytes = new String(header, StandardCharsets.ISO_8859_1);
            String brand = bytes.substring(8, 12);
            if (bytes.contains("Core Media") || bytes.contains("Apple") || bytes.contains("com.apple") || brand.equals("qt  ") || brand.equals("CAEP")) return "iOS";
            if (name.isEmpty() || tag.equals("custom")) {
                if (bytes.contains("libstagefright") || bytes.contains("c2.android") || bytes.contains("OMX.google") || bytes.contains("Android")) return "Android";
                if (bytes.contains("Lavf") || bytes.contains("Lavc")) return "Win";
            }
        }
        return tag;
    }
}
