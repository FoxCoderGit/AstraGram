package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** One personal Yandex login per installation, shared by its Telegram accounts. */
public final class GhostYandexMusicSession {
    private static final Object LOCK=new Object();
    private static final String KEY="ghost_yandex_music_device_v1";
    private static final String MIGRATED="device_session_migrated_v1";
    private final SharedPreferences preferences;
    public GhostYandexMusicSession(long telegramOwner){
        if(telegramOwner<=0)throw new IllegalArgumentException("account");
        preferences=ApplicationLoader.applicationContext.getSharedPreferences("ghost_yandex_music",Context.MODE_PRIVATE);
        migrate(telegramOwner);
    }
    private SecretKey secret(String alias,boolean create)throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(alias))return (SecretKey)store.getKey(alias,null);
        if(!create)return null;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build());
        return generator.generateKey();
    }
    private JSONObject read(String alias){
        try{
            String value=preferences.getString(alias,null);if(value==null)return null;
            SecretKey secret=secret(alias,false);if(secret==null)return null;
            byte[] packed=Base64.decode(value,Base64.NO_WRAP);if(packed.length<29)return null;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,secret,new GCMParameterSpec(128,Arrays.copyOfRange(packed,0,12)));
            return new JSONObject(new String(cipher.doFinal(packed,12,packed.length-12),StandardCharsets.UTF_8));
        }catch(Exception ignored){return null;}
    }
    private void migrate(long owner){
        synchronized(LOCK){
            if(preferences.getBoolean(MIGRATED,false))return;
            if(read(KEY)!=null){preferences.edit().putBoolean(MIGRATED,true).commit();return;}
            // Prefer the currently opened Telegram account. Import only active local accounts.
            JSONObject data=read("ghost_yandex_music_"+owner);
            if(data==null){
                boolean legacy=false;
                for(String name:preferences.getAll().keySet())if(name.matches("ghost_yandex_music_[0-9]+")){legacy=true;break;}
                if(legacy)for(int i=0;i<UserConfig.MAX_ACCOUNT_COUNT;i++){
                    UserConfig config=UserConfig.getInstance(i);
                    if(!config.isClientActivated())continue;
                    data=read("ghost_yandex_music_"+config.getClientUserId());if(data!=null)break;
                }
            }
            if(data==null){preferences.edit().putBoolean(MIGRATED,true).commit();return;}
            try{save(data.getString("token"),data.optString("name",""),data.optLong("uid",0));}
            catch(Exception ignored){/* Leave migration pending if Keystore/storage is temporarily unavailable. */}
        }
    }
    public void save(String token,String name,long uid)throws Exception{
        synchronized(LOCK){
            if(token==null||token.isEmpty())throw new IllegalArgumentException("token");
            JSONObject data=new JSONObject().put("token",token).put("name",name).put("uid",uid);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret(KEY,true));
            byte[] encrypted=cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
            byte[] packed=new byte[12+encrypted.length];
            System.arraycopy(cipher.getIV(),0,packed,0,12);System.arraycopy(encrypted,0,packed,12,encrypted.length);
            if(!preferences.edit().putString(KEY,Base64.encodeToString(packed,Base64.NO_WRAP)).putBoolean(MIGRATED,true).commit())throw new Exception("Session storage unavailable");
        }
    }
    public String token(){synchronized(LOCK){JSONObject data=read(KEY);return data==null?null:data.optString("token",null);}}
    public String name(){synchronized(LOCK){JSONObject data=read(KEY);return data==null?"":data.optString("name","");}}
    public long uid(){synchronized(LOCK){JSONObject data=read(KEY);return data==null?0:data.optLong("uid",0);}}
    public boolean updateUid(String token,String name,long uid)throws Exception{
        synchronized(LOCK){if(!token.equals(token()))return false;save(token,name,uid);return true;}
    }
    public void clearIfToken(String failed){synchronized(LOCK){if(failed!=null&&failed.equals(token()))clear();}}
    public void clear(){
        synchronized(LOCK){
            // Keep the migration marker: logging out must never resurrect a legacy account token.
            if(!preferences.edit().remove(KEY).putBoolean(MIGRATED,true).commit())return;
            try{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(KEY);}catch(Exception ignored){}
        }
    }
}
