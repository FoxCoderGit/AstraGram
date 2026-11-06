import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import android.net.NetworkCapabilities;
import org.json.JSONObject;
import java.util.Arrays;

/** Runs against the installed APK with app_process; no account access or network calls. */
public final class GhostYandexMusicAndroidSmoke {
    private static int checks;
    private static Method mp3Url;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static String parse(String xml) throws Exception {
        return (String) mp3Url.invoke(null, (Object) xml.getBytes(StandardCharsets.UTF_8));
    }
    private static void rejects(String xml) throws Exception {
        try { parse(xml); } catch (InvocationTargetException expected) { checks++; return; }
        throw new AssertionError("Malformed download XML was accepted");
    }
    private static void acceptsTimestamp(String xml,String timestamp) throws Exception {
        String link=parse(xml.replace("<ts>123</ts>","<ts>"+timestamp+"</ts>"));
        if(!link.endsWith("/"+timestamp+"/audio.mp3"))throw new AssertionError("Timestamp was changed");
        checks++;
    }
    public static void main(String[] args) {
        try { runTests(); }
        catch(Throwable error) {
            Throwable cause=error instanceof InvocationTargetException?error.getCause():error;
            System.out.println("Android XML test failed: "+cause.getClass().getSimpleName()+": "+cause.getMessage());
            System.exit(1);
        }
    }
    private static void runTests() throws Exception {
        mp3Url=Class.forName("org.telegram.messenger.GhostYandexMusicClient")
                .getDeclaredMethod("mp3Url",byte[].class);
        mp3Url.setAccessible(true);
        String valid="<download-info><host>test.storage.yandex.net</host><path>/audio.mp3</path><ts>123</ts><s>ticket</s></download-info>";
        String link=parse(valid);
        if(!link.startsWith("https://test.storage.yandex.net/get-mp3/")||!link.endsWith("/123/audio.mp3"))throw new AssertionError("Invalid MP3 URL");
        checks++;
        // Hex timestamps are used by the service. Keep leading zeroes verbatim.
        acceptsTimestamp(valid,"5dc4a6f2");
        acceptsTimestamp(valid,"0005bd24c9cc56a1");
        acceptsTimestamp(valid,"5DC4A6F2");
        rejects("<!DOCTYPE download-info [<!ENTITY x SYSTEM 'file:///data/local/tmp/nonexistent'>]>"+valid.replace("ticket","&x;"));
        rejects(valid.replace("</download-info>","<host>other.yandex.net</host></download-info>"));
        rejects(valid.replace("<s>ticket</s>",""));
        rejects(valid.replace("test.storage.yandex.net","yandex.net.attacker.invalid"));
        rejects(valid.replace("/audio.mp3","relative.mp3"));
        rejects(valid.replace("<ts>123</ts>","<ts>g123</ts>"));
        rejects(valid.replace("<ts>123</ts>","<ts>123/extra</ts>"));
        rejects(valid.replace("<ts>123</ts>","<ts>123%2fextra</ts>"));
        rejects(valid.replace("<ts>123</ts>","<ts></ts>"));
        rejects(valid.replace("<s>ticket</s>","<s><nested>ticket</nested></s>"));
        Method liked=Class.forName("org.telegram.messenger.GhostYandexMusicClient").getDeclaredMethod("likedIds",JSONObject.class);liked.setAccessible(true);
        JSONObject library=new JSONObject("{\"result\":{\"library\":{\"tracks\":[{\"id\":123,\"albumId\":45},{\"id\":123,\"albumId\":99},{\"id\":456,\"albumId\":null}]}}}");
        check(liked.invoke(null,library).equals(Arrays.asList("123:45","456")),"Liked order, album and duplicate removal");
        check(liked.invoke(null,new JSONObject("{\"result\":{\"library\":{\"tracks\":[]}}}")).equals(Arrays.asList()),"Empty liked library");
        check(liked.invoke(null,new JSONObject("{\"result\":{\"library\":{\"tracks\":[null,{\"id\":\"../bad\"},{\"id\":\"777:88\"}]}}}")).equals(Arrays.asList("777:88")),"Malformed liked entries do not hide valid tracks");
        Method rank=Class.forName("org.telegram.messenger.GhostYandexMusicNetwork").getDeclaredMethod("rank",NetworkCapabilities.class);rank.setAccessible(true);
        check((int)rank.invoke(null,new Object[]{null})<0,"Reject a disappeared network without crashing");
        System.out.println("Android installed APK music: "+checks+" scenarios passed.");
        System.exit(0);
    }
}
