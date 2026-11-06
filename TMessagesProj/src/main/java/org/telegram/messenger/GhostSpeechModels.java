package org.telegram.messenger;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.*;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Device-local recognition model preference and verified offline model installation. */
public final class GhostSpeechModels {
    public static final int AUTO=0,VOSK=1,SMALL=2,TURBO=3,GROQ=4,TELEGRAM=5;
    public static final String[] LABELS={"Автоматически · локально","Vosk · быстро","Whisper Small · баланс","Whisper Large v3 Turbo · качество","Groq · онлайн","Telegram · штатная расшифровка"};
    private static final String SMALL_FILE="ggml-small-q5_0.bin",TURBO_FILE="ggml-large-v3-turbo-q5_0.bin";
    private static final String VOSK_ASSET="vosk/vosk-model-small-ru-0.22.zip";
    private static final String VOSK_HASH="961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4";
    private static volatile long memory;
    private GhostSpeechModels(){}
    private static SharedPreferences prefs(){return MessagesController.getGlobalMainSettings();}
    public static synchronized int choice(){if(!prefs().getBoolean("ghost_speech_telegram_default_v85",false))prefs().edit().putBoolean("ghost_speech_telegram_default_v85",true).putInt("ghost_speech_model",TELEGRAM).apply();int value=prefs().getInt("ghost_speech_model",TELEGRAM);return value>=AUTO&&value<=TELEGRAM?value:TELEGRAM;}
    public static void choose(int value){prefs().edit().putBoolean("ghost_speech_telegram_default_v85",true).putInt("ghost_speech_model",Math.max(AUTO,Math.min(TELEGRAM,value))).apply();}
    public static String summary(){int selected=choice();return selected==AUTO||selected==TURBO&&!turboInstalled()?"Авто · "+LABELS[resolve()].split(" · ")[0]:LABELS[selected];}
    private static String speedKey(int engine){return "ghost_asr_rtf_"+Integer.toHexString(Build.FINGERPRINT.hashCode())+"_"+engine;}
    public static int resolve(){
        int selected=choice();if(selected!=AUTO&&(selected!=TURBO||turboInstalled()))return selected;
        long ram=memory;
        if(ram==0){ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();((ActivityManager)ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);memory=ram=info.totalMem;}
        int performance=SharedConfig.getDevicePerformanceClass();
        int engine=automatic(performance,ram,Runtime.getRuntime().availableProcessors(),prefs().getFloat(speedKey(TURBO),0),prefs().getFloat(speedKey(SMALL),0));
        // Auto must never start a network download or choose a missing optional model.
        return engine==TURBO&&!turboInstalled()?(prefs().getFloat(speedKey(SMALL),0)>2.5f?VOSK:SMALL):engine;
    }
    /** Pure selection policy; observed slow models are skipped only in Auto. */
    public static int automatic(int performance,long ram,int cores,float turboRtf,float smallRtf){
        if(performance==SharedConfig.PERFORMANCE_CLASS_LOW||ram<5L*1024*1024*1024||cores<4)return VOSK;
        if(performance==SharedConfig.PERFORMANCE_CLASS_HIGH&&ram>=10L*1024*1024*1024&&cores>=8&&turboRtf<=2.5f)return TURBO;
        return smallRtf>2.5f?VOSK:SMALL;
    }
    public static void record(int engine,long elapsed,double seconds,boolean failed){
        if(failed)GhostLicenseBridge.recordError("speech");
        if(seconds>0.2)prefs().edit().putFloat(speedKey(engine),failed?100f:(float)(elapsed/1000.0/seconds)).apply();
    }
    public static File file(int engine){return engine==VOSK?ApplicationLoader.getFilesDirFixed("vosk-small-ru-0.22"):new File(LocalWhisper.getModelDir(),engine==SMALL?SMALL_FILE:TURBO_FILE);}
    static String asset(int engine){return engine==VOSK?VOSK_ASSET:"whisper/"+(engine==SMALL?SMALL_FILE:TURBO_FILE);}
    static long size(int engine){return engine==VOSK?46236750L:engine==SMALL?175209663L:574041195L;}
    static String hash(int engine){return engine==VOSK?VOSK_HASH:engine==SMALL?"23ad5215d37677f8eb8cbc41b4d0d4e451229f811be5206ff116dd9337eba748":"394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2";}
    static File archive(int engine){return engine==VOSK?ApplicationLoader.getFilesDirFixed("vosk-small-ru-0.22.zip"):file(engine);}
    static String downloadUrl(int engine){return engine==VOSK?"https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip":engine==SMALL?"https://93.88.204.2:9443/models/whisper-small-q5_0.bin":"https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"+TURBO_FILE;}
    public static boolean turboInstalled(){return file(TURBO).isFile()&&file(TURBO).length()==size(TURBO);}
    public static boolean available(int engine){
        if(engine==TELEGRAM)return false;
        if(engine==GROQ)return GhostGroq.configured();
        if(engine==TURBO)return turboInstalled();
        if(engine!=VOSK&&file(engine).isFile()&&file(engine).length()==size(engine))return true;
        if(engine==VOSK&&voskInstalled())return true;
        return false;
    }
    private static boolean voskInstalled(){return new File(file(VOSK),".ready").isFile()&&new File(file(VOSK),"am/final.mdl").isFile()&&new File(file(VOSK),"graph/Gr.fst").isFile();}
    public static synchronized File prepare(int engine)throws Exception{
        if(engine==GROQ||engine==TELEGRAM)throw new IOException("Online engine");
        File target=file(engine);
        if(engine==VOSK){prepareVosk();return target;}
        if(target.isFile()&&target.length()==size(engine))return target;
        if(!available(engine))throw new IOException("Модель не загружена. Выберите её в настройках распознавания речи и подтвердите загрузку.");
        File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create model folder");
        File stage=new File(parent,target.getName()+".installing");
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long length=0;
            try(InputStream input=ApplicationLoader.applicationContext.getAssets().open(asset(engine));FileOutputStream out=new FileOutputStream(stage)){
                byte[] bytes=new byte[131072];int n;while((n=input.read(bytes))!=-1){out.write(bytes,0,n);digest.update(bytes,0,n);length+=n;}out.getFD().sync();
            }
            if(length!=size(engine)||!hex(digest.digest()).equals(hash(engine)))throw new IOException("Model integrity check failed");
            if(!stage.renameTo(target))throw new IOException("Cannot activate model");
            return target;
        }finally{if(stage.exists())stage.delete();}
    }
    private static void prepareVosk()throws Exception{
        if(voskInstalled())return;
        File folder=file(VOSK);if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create Vosk folder");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(archive(VOSK))){byte[] bytes=new byte[131072];int n;while((n=input.read(bytes))!=-1)digest.update(bytes,0,n);}
        if(!hex(digest.digest()).equals(VOSK_HASH))throw new IOException("Vosk model integrity check failed");
        String root=folder.getCanonicalPath()+File.separator;
        long length=0;
        try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive(VOSK)))){
            ZipEntry entry;byte[] bytes=new byte[131072];
            while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();int slash=name.indexOf('/');if(slash<0)continue;name=name.substring(slash+1);if(name.isEmpty())continue;
                File destination=new File(folder,name);if(!destination.getCanonicalPath().startsWith(root))throw new IOException("Invalid model entry");
                if(entry.isDirectory()){if(!destination.isDirectory()&&!destination.mkdirs())throw new IOException("Cannot create model directory");continue;}
                if(!destination.getParentFile().isDirectory()&&!destination.getParentFile().mkdirs())throw new IOException("Cannot create model directory");
                try(FileOutputStream out=new FileOutputStream(destination)){int n;while((n=zip.read(bytes))!=-1){length+=n;if(length>512L*1024*1024)throw new IOException("Invalid model size");out.write(bytes,0,n);}out.getFD().sync();}
            }
        }
        if(!new File(folder,"am/final.mdl").isFile()||!new File(folder,"graph/Gr.fst").isFile())throw new IOException("Incomplete Vosk model");
        try(FileOutputStream marker=new FileOutputStream(new File(folder,".ready"))){marker.write(1);marker.getFD().sync();}
        archive(VOSK).delete();
    }
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.US,"%02x",b&255));return out.toString();}
}
