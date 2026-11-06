package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.util.Locale;

/** Local prerecorded fixtures only; never reads chats or sends messages. */
public final class GhostSpeechDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @Override public void onStart(){
        Bundle report=new Bundle();int previous=0;
        try{
            runOnMainSync(ApplicationLoader::postInitApplication);long ready=SystemClock.elapsedRealtime()+15000;
            while(!org.ghostgram.messenger.license.LicenseManager.ready&&SystemClock.elapsedRealtime()<ready)SystemClock.sleep(20);
            check(GhostLicenseBridge.allowed(),"Active license required");previous=GhostSpeechModels.choice();
            File dir=new File(getTargetContext().getFilesDir(),"ghost_media_tests");
            for(int engine:new int[]{1,2,3}){
                GhostSpeechModels.choose(engine);check(GhostSpeechModels.resolve()==engine,"Explicit engine override");
                check(GhostSpeechModels.available(engine),"Bundled model available "+engine);
                long start=SystemClock.elapsedRealtime();String text=LocalWhisper.transcribeFile(new File(dir,"russian.ogg"),engine);long elapsed=SystemClock.elapsedRealtime()-start;
                String normalized=text==null?"":text.toLowerCase(Locale.ROOT);
                check(!normalized.startsWith("error:")&&normalized.contains("завтра"),"Russian model "+engine+": "+text);
                report.putString("engine_"+engine,elapsed+"ms: "+text);
            }
            String video=LocalWhisper.transcribeFile(new File(dir,"russian.mp4"),GhostSpeechModels.VOSK);check(video!=null&&!video.startsWith("ERROR:")&&video.contains("завтра"),"Vosk video audio track: "+video);report.putString("vosk_video",video);
            String silence=LocalWhisper.transcribeFile(new File(dir,"silence.ogg"),GhostSpeechModels.VOSK);check(silence.contains("NO_SPEECH"),"Silence must not hallucinate");
            check(GhostSpeechModels.automatic(0,16L<<30,8,0,0)==1,"Slow CPU chooses Vosk");
            check(GhostSpeechModels.automatic(1,6L<<30,8,0,0)==2,"Mid-range chooses Small");
            check(GhostSpeechModels.automatic(2,12L<<30,8,0,0)==3,"Fast device initially chooses Turbo");
            check(GhostSpeechModels.automatic(2,12L<<30,8,5,0)==2,"Observed slow Turbo chooses Small");
            check(GhostSpeechModels.automatic(2,12L<<30,8,5,5)==1,"Observed slow Small chooses Vosk");
            GhostSpeechModels.choose(GhostSpeechModels.AUTO);report.putString("auto",GhostSpeechModels.summary());report.putString("silence","PASS");
        }catch(Throwable error){report.putString("error",android.util.Log.getStackTraceString(error));}
        finally{GhostSpeechModels.choose(previous);}
        finish(report.containsKey("error")?0:-1,report);
    }
}
