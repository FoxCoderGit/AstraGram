package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** HTTPS music relay; each licensed installation enrolls with its Keystore device proof. */
public final class GhostYandexMusicRelay {
    public static final String SERVER="music.codebyfox.ru";
    private static final String ENDPOINT="https://"+SERVER;
    private static final String ALIAS="ghost_yandex_music_relay";
    public static final class Profile {
        private final String credential;
        private Profile(String credential){this.credential=credential;}
    }
    private GhostYandexMusicRelay(){}
    private static SharedPreferences prefs(){return ApplicationLoader.applicationContext.getSharedPreferences(ALIAS,Context.MODE_PRIVATE);}
    private static SecretKey key(boolean create)throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        if(!create)return null;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    public static synchronized Profile stored(){
        try{
            String value=prefs().getString("credential",null);if(value==null)return null;
            SecretKey key=key(false);if(key==null)return null;
            byte[] packed=Base64.decode(value,Base64.NO_WRAP);if(packed.length<29)return null;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(packed,0,12)));
            String token=new String(cipher.doFinal(packed,12,packed.length-12),StandardCharsets.UTF_8);
            return token.matches("[A-Za-z0-9_-]{40,100}")?new Profile(token):null;
        }catch(Exception ignored){return null;}
    }
    public static Profile active(){return enabled()?stored():null;}
    public static boolean enabled(){return prefs().getBoolean("enabled",true);}
    public static void setEnabled(boolean enabled){prefs().edit().putBoolean("enabled",enabled).apply();}
    private static synchronized void save(String token)throws Exception{
        if(!token.matches("[A-Za-z0-9_-]{40,100}"))throw new Exception("Invalid relay credential");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));
        byte[] encrypted=cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),packed=new byte[12+encrypted.length];
        System.arraycopy(cipher.getIV(),0,packed,0,12);System.arraycopy(encrypted,0,packed,12,encrypted.length);
        if(!prefs().edit().putString("credential",Base64.encodeToString(packed,Base64.NO_WRAP)).putBoolean("auto_enrolled",true).commit())throw new Exception("Relay storage unavailable");
    }
    private static HttpsURLConnection connect(String path)throws Exception{
        HttpsURLConnection connection=(HttpsURLConnection)new URL(ENDPOINT+path).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(20000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
        return connection; // System CA trust and standard hostname verification.
    }
    public static HttpsURLConnection open(URL target,Profile profile)throws Exception{
        HttpsURLConnection connection=connect("/relay");
        connection.setRequestProperty("X-Ghost-Music-Token",profile.credential);
        connection.setRequestProperty("X-Ghost-Upstream-Url",target.toExternalForm());
        return connection;
    }
    public static synchronized void invalidate(Profile failed){
        Profile current=stored();
        if(current!=null&&failed!=null&&current.credential.equals(failed.credential))prefs().edit().remove("credential").apply();
    }
    static synchronized Profile ensure(GhostYandexMusicClient.Operation operation)throws Exception{
        Profile profile=stored();
        if(profile!=null&&prefs().getBoolean("auto_enrolled",false))return profile;
        JSONObject challenge=request("/enroll/challenge",null,operation);
        String nonce=challenge.getString("nonce");
        String proof=GhostLicenseBridge.musicRelayProof(nonce);
        if(proof==null)throw new PairingError("Не удалось подтвердить доступ AstraGram. Дождитесь проверки доступа и повторите попытку.");
        String token=request("/enroll",proof,operation).getString("token");
        operation.check();save(token);return new Profile(token);
    }
    private static JSONObject request(String path,String payload,GhostYandexMusicClient.Operation operation)throws Exception{
        HttpsURLConnection connection=connect(path);operation.connection=connection;
        try{
            operation.check();
            if(payload!=null){
                byte[] body=payload.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(body.length);
                try(java.io.OutputStream output=connection.getOutputStream()){output.write(body);}
            }
            int status=connection.getResponseCode();
            if(status!=200)throw new PairingError(status==429?"Повторите подключение через минуту.":status==401?"Не удалось подтвердить доступ AstraGram. Повторите после проверки доступа.":"Музыкальный сервер временно недоступен ("+status+").");
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int count;
                while((count=input.read(buffer))!=-1){operation.check();if(output.size()+count>65536)throw new PairingError("Некорректный ответ музыкального сервера");output.write(buffer,0,count);}
                return new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
            }
        }finally{connection.disconnect();operation.connection=null;}
    }
    static final class PairingError extends Exception{PairingError(String message){super(message);}}
    static String error(String error){
        if("relay-auth".equals(error))return "Не удалось обновить подключение к музыкальному серверу. Повторите попытку.";
        if("upstream".equals(error))return "RU-сервер не смог связаться с Яндекс Музыкой. Повторите запрос.";
        if("size".equals(error))return "Файл превышает ограничение RU-сервера";
        return "RU-сервер отклонил этот запрос к Яндекс Музыке";
    }
}
