import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.util.Base64;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/** Synthetic sessions only: mock preferences and the shell UID's own real Android Keystore. */
public class GhostYandexSessionSmoke {
    static final Map<String,Object> values=new HashMap<>();
    static final String KEY="ghost_yandex_music_device_v1",LEGACY="ghost_yandex_music_101";
    static int passed;
    static void check(boolean value){if(!value)throw new AssertionError("Session scenario "+(passed+1));passed++;}
    static Object editor(){
        Map<String,Object> changes=new HashMap<>();
        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(self,method,args)->{
            String name=method.getName();
            if(name.startsWith("put")){changes.put((String)args[0],args[1]);return self;}
            if(name.equals("remove")){changes.put((String)args[0],null);return self;}
            if(name.equals("commit")||name.equals("apply")){
                for(Map.Entry<String,Object> entry:changes.entrySet())if(entry.getValue()==null)values.remove(entry.getKey());else values.put(entry.getKey(),entry.getValue());
                return name.equals("commit")?Boolean.TRUE:null;
            }
            throw new UnsupportedOperationException(name);
        });
    }
    static final SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(self,method,args)->{
        String name=method.getName();
        if(name.equals("getAll"))return new HashMap<>(values);
        if(name.equals("edit"))return editor();
        if(name.startsWith("get"))return values.containsKey(args[0])?values.get(args[0]):args[1];
        throw new UnsupportedOperationException(name);
    });
    static Object call(Object session,String method,Class<?>[] types,Object...args)throws Exception{return session.getClass().getMethod(method,types).invoke(session,args);}
    static String token(Object session)throws Exception{return (String)call(session,"token",new Class[]{});}
    static void save(Object session,String token)throws Exception{call(session,"save",new Class[]{String.class,String.class,long.class},token,"Synthetic test",12L);}
    public static void main(String[] args)throws Exception{
        if(java.security.Security.getProvider("AndroidKeyStore")==null){
            Class.forName("android.security.keystore2.AndroidKeyStoreProvider").getMethod("install").invoke(null);
        }
        Field context=Class.forName("org.telegram.messenger.ApplicationLoader").getField("applicationContext");Object previous=context.get(null);
        context.set(null,new ContextWrapper(null){@Override public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}});
        Class<?> type=Class.forName("org.telegram.messenger.GhostYandexMusicSession");
        try{
            Object first=type.getConstructor(long.class).newInstance(101L);
            check(token(first)==null);
            save(first,"synthetic-token-first");
            Object second=type.getConstructor(long.class).newInstance(202L);
            check("synthetic-token-first".equals(token(second)));
            check(!values.get(KEY).toString().contains("synthetic-token-first"));
            save(second,"synthetic-token-second");
            check("synthetic-token-second".equals(token(first)));
            call(first,"clearIfToken",new Class[]{String.class},"synthetic-token-first");
            check("synthetic-token-second".equals(token(second)));
            check(!(Boolean)call(first,"updateUid",new Class[]{String.class,String.class,long.class},"synthetic-token-first","test",13L));
            check((Boolean)call(first,"updateUid",new Class[]{String.class,String.class,long.class},"synthetic-token-second","test",13L));
            check((Long)call(second,"uid",new Class[]{})==13L);
            call(first,"clear",new Class[]{});check(token(second)==null);
            // Seed a synthetic old account session using the actual historical AES/GCM layout.
            Method secret=type.getDeclaredMethod("secret",String.class,boolean.class);secret.setAccessible(true);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,(SecretKey)secret.invoke(first,LEGACY,true));
            byte[] encrypted=cipher.doFinal("{\"token\":\"synthetic-legacy-token\",\"name\":\"Synthetic\",\"uid\":77}".getBytes(StandardCharsets.UTF_8));
            byte[] packed=new byte[12+encrypted.length];System.arraycopy(cipher.getIV(),0,packed,0,12);System.arraycopy(encrypted,0,packed,12,encrypted.length);
            values.clear();values.put(LEGACY,Base64.encodeToString(packed,Base64.NO_WRAP));
            Object migrated=type.getConstructor(long.class).newInstance(101L);
            check("synthetic-legacy-token".equals(token(migrated)));
            Object other=type.getConstructor(long.class).newInstance(303L);
            check("synthetic-legacy-token".equals(token(other)));
            call(other,"clear",new Class[]{});
            check(token(type.getConstructor(long.class).newInstance(101L))==null);
            System.out.println("Android shared Yandex session: "+passed+" scenarios passed (synthetic data, real Keystore, shell UID)");
        }finally{
            context.set(null,previous);values.clear();
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry(KEY);keys.deleteEntry(LEGACY);
        }
    }
}
