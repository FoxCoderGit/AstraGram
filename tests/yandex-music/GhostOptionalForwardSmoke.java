import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.*;
import java.util.ArrayList;

/** Synthetic TL messages and mock settings; no network, app data or Telegram account access. */
public class GhostOptionalForwardSmoke {
    static int passed;
    static Class<?> objectType,ownerType,copy;
    static Object unsafe;static Method allocate;
    static void check(boolean ok){if(!ok)throw new AssertionError("Optional/copy scenario "+(passed+1));passed++;}
    static Object make(String name)throws Exception{return Class.forName(name).getConstructor().newInstance();}
    static void field(Object object,String name,Object value)throws Exception{object.getClass().getField(name).set(object,value);}
    static Object field(Object object,String name)throws Exception{return object.getClass().getField(name).get(object);}
    static Object message()throws Exception{
        Object owner=make("org.telegram.tgnet.TLRPC$TL_message");field(owner,"id",101);field(owner,"dialog_id",123L);
        field(owner,"message","Synthetic protected message");field(owner,"noforwards",true);
        Object message=allocate.invoke(unsafe,objectType);field(message,"messageOwner",owner);return message;
    }
    static boolean can(Object message)throws Exception{return (Boolean)copy.getMethod("canCopy",objectType).invoke(null,message);}
    public static void main(String[] args)throws Exception{
        try{run();}catch(Throwable error){error.printStackTrace(System.err);System.exit(1);}
    }
    static void run()throws Exception{
        Class<?> u=Class.forName("sun.misc.Unsafe");Field singleton=u.getDeclaredField("theUnsafe");singleton.setAccessible(true);unsafe=singleton.get(null);allocate=u.getMethod("allocateInstance",Class.class);
        Field app=Class.forName("org.telegram.messenger.ApplicationLoader").getField("applicationContext");Object previous=app.get(null);
        app.set(null,new ContextWrapper(null){@Override public SharedPreferences getSharedPreferences(String name,int mode){return GhostYandexSessionSmoke.prefs;}});
        Class<?> controllers=Class.forName("org.telegram.messenger.MessagesController");
        Field instances=controllers.getDeclaredField("Instance");instances.setAccessible(true);Object[] slots=(Object[])instances.get(null);Object oldController=slots[0];
        Object fakeController=allocate.invoke(unsafe,controllers);
        Field mainPreferences=controllers.getDeclaredField("mainPreferences");mainPreferences.setAccessible(true);mainPreferences.set(fakeController,GhostYandexSessionSmoke.prefs);slots[0]=fakeController;
        try{
            Class<?> features=Class.forName("org.telegram.messenger.GhostOptionalFeatures");Method enabled=features.getMethod("enabled",String.class),set=features.getMethod("setEnabled",String.class,boolean.class);
            GhostYandexSessionSmoke.values.put("ghost_experimental",true);
            GhostYandexSessionSmoke.values.put("translate_chat_button",true);
            for(String key:new String[]{"yandex_music","waveform_drawing","chat_translation"})check(!(Boolean)enabled.invoke(null,key));
            set.invoke(null,"yandex_music",true);check((Boolean)enabled.invoke(null,"yandex_music"));
            check(!(Boolean)enabled.invoke(null,"waveform_drawing"));
            set.invoke(null,"waveform_drawing",true);set.invoke(null,"yandex_music",false);
            check((Boolean)enabled.invoke(null,"waveform_drawing")&&!(Boolean)enabled.invoke(null,"yandex_music"));
            check((Boolean)Class.forName("org.telegram.messenger.GhostTextService").getMethod("enabled").invoke(null));
            objectType=Class.forName("org.telegram.messenger.MessageObject");ownerType=Class.forName("org.telegram.tgnet.TLRPC$Message");copy=Class.forName("org.telegram.messenger.GhostProtectedForward");
            Object original=message();check(can(original));
            Object owner=field(original,"messageOwner");
            Object header=make("org.telegram.tgnet.TLRPC$TL_messageFwdHeader");field(owner,"fwd_from",header);
            Method build=copy.getDeclaredMethod("parameters",objectType,java.io.File.class,long.class);build.setAccessible(true);
            Object params=build.invoke(null,original,null,456L);
            check("Synthetic protected message".equals(field(params,"message")));
            check(field(params,"parentObject")==null&&field(params,"replyToMsg")==null&&field(params,"replyMarkup")==null&&field(params,"params")==null);
            check(field(owner,"fwd_from")==header&&(Boolean)field(owner,"noforwards"));
            check(((ArrayList<?>)field(params,"entities"))!=field(owner,"entities"));
            field(original,"type",29);check(!can(original));
            field(original,"type",0);field(owner,"id",0);check(!can(original));field(owner,"id",101);
            Object photo=make("org.telegram.tgnet.TLRPC$TL_messageMediaPhoto");field(photo,"photo",make("org.telegram.tgnet.TLRPC$TL_photo"));field(owner,"media",photo);check(can(original));
            field(photo,"ttl_seconds",5);check(!can(original));field(photo,"ttl_seconds",0);
            Object doc=make("org.telegram.tgnet.TLRPC$TL_messageMediaDocument");field(doc,"document",make("org.telegram.tgnet.TLRPC$TL_document"));field(owner,"media",doc);check(can(original));
            field(doc,"ttl_seconds",Integer.MAX_VALUE);check(!can(original));
            field(owner,"media",make("org.telegram.tgnet.TLRPC$TL_messageMediaPoll"));check(!can(original));
            check(!can(null));
            field(doc,"ttl_seconds",0);field(owner,"media",doc);
            Object oldDocument=field(doc,"document");field(oldDocument,"id",777L);field(oldDocument,"access_hash",888L);
            field(oldDocument,"file_reference",new byte[]{9,8});field(oldDocument,"mime_type","application/octet-stream");field(oldDocument,"size",999L);
            java.io.File file=java.io.File.createTempFile("ghost-copy-smoke-",".bin",new java.io.File("/data/local/tmp"));
            try{
                try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){output.write(new byte[]{1,2,3,4});}
                Object upload=build.invoke(null,original,file,456L),fresh=field(upload,"document");
                check((Long)field(fresh,"id")==0L&&(Long)field(fresh,"access_hash")==0L&&(Integer)field(fresh,"dc_id")==0);
                check(((byte[])field(fresh,"file_reference")).length==0);
                check((Long)field(fresh,"size")==4L);
                check(field(fresh,"attributes")!=field(oldDocument,"attributes"));
                check(field(upload,"parentObject")==null&&field(upload,"replyToMsg")==null);
                check((Long)field(oldDocument,"id")==777L&&(Long)field(oldDocument,"access_hash")==888L&&((byte[])field(oldDocument,"file_reference"))[0]==9);
                check(file.getAbsolutePath().equals(field(upload,"path"))&&file.length()==4);
            }finally{file.delete();}
            field(owner,"media",photo);
            try{build.invoke(null,original,null,456L);throw new AssertionError("Missing photo accepted");}
            catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException);}
            System.out.println("Android optional features/protected copy: "+passed+" scenarios passed (synthetic data, no network)");
        }finally{slots[0]=oldController;app.set(null,previous);GhostYandexSessionSmoke.values.clear();}
    }
}
