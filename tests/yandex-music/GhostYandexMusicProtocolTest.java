import org.telegram.messenger.GhostYandexMusicProtocol;

public class GhostYandexMusicProtocolTest {
    private static int checks;
    private static final String TOKEN="AQAAAA_test-token_0123456789";
    private static final String STATE="one-use-state";
    private static void check(boolean condition,String description){if(!condition)throw new AssertionError(description);checks++;}
    private static String callback(String base,String parameters){return base+"#access_token="+TOKEN+"&"+parameters;}
    public static void main(String[] args){
        check(TOKEN.equals(GhostYandexMusicProtocol.callbackToken(callback("https://music.yandex.ru/","state="+STATE),STATE)),"Accept official callback with matching state");
        check(GhostYandexMusicProtocol.callbackToken(callback("http://music.yandex.ru/","state="+STATE),STATE)==null,"No HTTP callback");
        check(GhostYandexMusicProtocol.callbackToken(callback("https://music.yandex.ru.evil.test/","state="+STATE),STATE)==null,"Reject lookalike host");
        check(GhostYandexMusicProtocol.callbackToken(callback("https://music.yandex.ru@evil.test/","state="+STATE),STATE)==null,"Reject user-info spoof");
        check(GhostYandexMusicProtocol.callbackToken(callback("https://user@music.yandex.ru/","state="+STATE),STATE)==null,"Reject URL credentials");
        check(GhostYandexMusicProtocol.callbackToken(callback("https://music.yandex.ru:8443/","state="+STATE),STATE)==null,"Reject custom callback port");
        check(GhostYandexMusicProtocol.callbackToken(callback("https://music.yandex.ru/album/1","state="+STATE),STATE)==null,"Callback must use registered root");
        check(GhostYandexMusicProtocol.callbackToken(callback("https://music.yandex.ru/","state=other"),STATE)==null,"Reject mismatched state");
        check(GhostYandexMusicProtocol.callbackToken(callback("https://music.yandex.ru/","expires_in=100"),STATE)==null,"Require state");
        check(GhostYandexMusicProtocol.callbackToken(callback("https://music.yandex.ru/","state="+STATE),"")==null,"Require pending login");
        check(GhostYandexMusicProtocol.callbackToken(callback("https://music.yandex.ru/","state="+STATE+"&state="+STATE),STATE)==null,"Reject duplicate state");
        check(GhostYandexMusicProtocol.callbackToken(callback("https://music.yandex.ru/","state="+STATE+"&access_token="+TOKEN),STATE)==null,"Reject duplicate token");
        check(GhostYandexMusicProtocol.callbackToken("https://music.yandex.ru/#access_token=bad%0Atoken_0123456789&state="+STATE,STATE)==null,"No token header injection");
        check(GhostYandexMusicProtocol.callbackToken("https://music.yandex.ru/#access_token=AQAAAA%2B%2Ftoken_0123456789%3D&state="+STATE,STATE)!=null,"Accept URL-encoded bearer characters");
        check(GhostYandexMusicProtocol.isYandexHttps("https://api.music.yandex.net/account/status"),"Allow Yandex API");
        check(GhostYandexMusicProtocol.isYandexHttps("https://cache.strm.yandex.net/get-mp3/opaque"),"Allow Yandex CDN");
        check(!GhostYandexMusicProtocol.isYandexHttps("https://yandex.net.attacker.test/file"),"No token/download on lookalike domain");
        check(!GhostYandexMusicProtocol.isYandexHttps("file:///data/local/private"),"No local-file download");
        check(!GhostYandexMusicProtocol.isYandexHttps("http://api.music.yandex.net/search"),"No insecure API");
        check(!GhostYandexMusicProtocol.isYandexHttps("https://127.0.0.1/"),"No arbitrary backend");
        check("123".equals(GhostYandexMusicProtocol.trackId("123:456")),"Canonical track ID");
        boolean rejected=false;try{GhostYandexMusicProtocol.trackId("../private");}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"No track-path traversal");
        String name=GhostYandexMusicProtocol.fileName("../Исполнитель","Трек\n/\\:*");
        check(!name.contains("/")&&!name.contains("\\")&&!name.contains("\n")&&!name.startsWith("."),"Safe attachment filename");
        check(name.endsWith(".mp3")&&name.contains("Исполнитель"),"Keep human-readable Unicode metadata");
        check(!GhostYandexMusicProtocol.completeAudio(180000,30000),"Do not send a preview as a full track");
        check(GhostYandexMusicProtocol.completeAudio(180000,179000),"Allow normal duration rounding");
        check(!GhostYandexMusicProtocol.completeAudio(180000,0),"Reject unreadable/empty audio");
        check(GhostYandexMusicProtocol.downloadTimestamp("5dc4a6f2"),"Accept service hexadecimal timestamp");
        check(GhostYandexMusicProtocol.downloadTimestamp("0005BD24C9CC56A1"),"Accept uppercase and zero-padded hex timestamp");
        check(GhostYandexMusicProtocol.downloadTimestamp("123456"),"Keep decimal-only timestamp compatibility");
        check(!GhostYandexMusicProtocol.downloadTimestamp(""),"Reject empty timestamp");
        check(!GhostYandexMusicProtocol.downloadTimestamp(null),"Reject missing timestamp");
        check(!GhostYandexMusicProtocol.downloadTimestamp("123/extra"),"Reject timestamp path injection");
        check(!GhostYandexMusicProtocol.downloadTimestamp("123%2fextra"),"Reject encoded timestamp path injection");
        check(!GhostYandexMusicProtocol.downloadTimestamp("g123"),"Reject non-hex timestamp");
        check(!GhostYandexMusicProtocol.downloadTimestamp(new String(new char[65]).replace('\0','a')),"Reject oversized timestamp");
        check("123:456".equals(GhostYandexMusicProtocol.fullTrackId("123","456")),"Keep album on liked track hydration");
        check("123:456".equals(GhostYandexMusicProtocol.fullTrackId("123:456","999")),"Do not duplicate album component");
        check("123".equals(GhostYandexMusicProtocol.fullTrackId("123",null)),"Liked track without an album");
        check("123".equals(GhostYandexMusicProtocol.fullTrackId("123","../bad")),"No album path injection");
        rejected=false;try{GhostYandexMusicProtocol.fullTrackId("123/other",null);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Reject malformed liked ID");
        check(GhostYandexMusicProtocol.matchesTrack("ЕЛКА трек","Трек","Ёлка"),"Liked filter is case-insensitive and ignores word order");
        check(!GhostYandexMusicProtocol.matchesTrack("другой","Трек","Ёлка"),"Filter excludes unrelated liked tracks");
        check(GhostYandexMusicProtocol.matchesTrack("  ",null,null),"Empty liked filter includes all metadata");
        check(GhostYandexMusicProtocol.matchesTrack("my SONG","My Song","Artist"),"English liked filter");
        check(GhostYandexMusicProtocol.mp3Header(new byte[]{'I','D','3'}),"ID3 header");
        check(GhostYandexMusicProtocol.mp3Header(new byte[]{(byte)255,(byte)251,0}),"MPEG audio header");
        check(!GhostYandexMusicProtocol.mp3Header(new byte[]{0,0,0}),"No protected binary as MP3");
        check(!GhostYandexMusicProtocol.mp3Header(new byte[]{(byte)255,(byte)241,0}),"No AAC disguised as MP3");
        check(!GhostYandexMusicProtocol.mp3Header(null),"No missing header");
        System.out.println("Yandex Music protocol: "+checks+" scenarios passed.");
    }
}
