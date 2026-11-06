package org.telegram.messenger;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Pure validation shared by authentication, network transport and attachment preparation. */
public final class GhostYandexMusicProtocol {
    private GhostYandexMusicProtocol() { }
    public static boolean isYandexHttps(String value) {
        try {
            URI uri = new URI(value); String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) return false;
            host = host.toLowerCase(Locale.US);
            return host.equals("yandex.ru") || host.endsWith(".yandex.ru") || host.equals("yandex.com")
                    || host.endsWith(".yandex.com") || host.equals("yandex.net") || host.endsWith(".yandex.net");
        } catch (Exception ignored) { return false; }
    }
    public static String callbackToken(String url, String expectedState) {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"music.yandex.ru".equalsIgnoreCase(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443
                    || uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) return null;
            Map<String,String> values = parameters(uri.getRawFragment());
            String state = values.get("state"), token = values.get("access_token");
            if (state == null || expectedState == null || expectedState.isEmpty()
                    || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), expectedState.getBytes(StandardCharsets.UTF_8))) return null;
            return token != null && token.length() >= 16 && token.length() <= 4096
                    && token.matches("[A-Za-z0-9._~+/-]+={0,2}") ? token : null;
        } catch (Exception ignored) { return null; }
    }
    public static Map<String,String> parameters(String query) throws Exception {
        Map<String,String> values = new HashMap<>();
        if (query != null) for (String pair : query.split("&")) {
            String[] parts = pair.split("=",2);
            String key = URLDecoder.decode(parts[0],"UTF-8");
            if (values.containsKey(key)) throw new IllegalArgumentException("Duplicate parameter");
            values.put(key, parts.length == 2 ? URLDecoder.decode(parts[1],"UTF-8") : "");
        }
        return values;
    }
    public static String trackId(String value) {
        if (value == null) throw new IllegalArgumentException("track");
        String id = value.split(":",2)[0];
        if (!id.matches("[0-9]{1,24}")) throw new IllegalArgumentException("track");
        return id;
    }
    public static String fileName(String artist, String title) {
        String name = ((artist == null ? "" : artist) + " — " + (title == null ? "Трек" : title))
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 150) name = name.substring(0,150);
        return (name.isEmpty() ? "Яндекс Музыка" : name) + ".mp3";
    }
    public static boolean completeAudio(long expectedMs, long actualMs) {
        return actualMs > 0 && (expectedMs <= 0 || actualMs >= expectedMs - Math.max(3000, expectedMs / 50));
    }
    public static boolean downloadTimestamp(String value) {
        // The CDN timestamp is a hexadecimal URL component, not a decimal number.
        // Preserve its original spelling, including leading zeroes.
        return value != null && value.matches("[0-9a-fA-F]{1,64}");
    }
    public static String fullTrackId(String id, String album) {
        if(id==null||!id.matches("[0-9]{1,24}(:[0-9]{1,24})?"))throw new IllegalArgumentException("track");
        if(id.indexOf(':')>=0)return id;
        return album!=null&&album.matches("[0-9]{1,24}")?id+":"+album:id;
    }
    public static boolean matchesTrack(String query,String title,String artist) {
        if(query==null||query.trim().isEmpty())return true;
        String text=((title==null?"":title)+" "+(artist==null?"":artist)).toLowerCase(Locale.ROOT).replace('ё','е');
        for(String word:query.trim().toLowerCase(Locale.ROOT).replace('ё','е').split("\\s+"))if(!text.contains(word))return false;
        return true;
    }
    public static boolean mp3Header(byte[] header) {
        return header != null && header.length >= 3 && ((header[0] == 'I' && header[1] == 'D' && header[2] == '3')
                || (header[0] & 255) == 255 && (header[1] & 0xe0) == 0xe0 && (header[1] & 0x06) != 0);
    }
}
