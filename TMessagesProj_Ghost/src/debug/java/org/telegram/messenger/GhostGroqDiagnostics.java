package org.telegram.messenger;

import android.app.*;
import android.content.*;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.EditTextBoldCursor;

/** Fake key, synthetic quota and prerecorded fixtures only. No calls to Groq. */
public final class GhostGroqDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @Override public void onStart(){
        Bundle report=new Bundle();int previous=0;boolean edited=false;Map<String,?> original=null;File extracted=null;
        SharedPreferences prefs=getTargetContext().getSharedPreferences("ghost_groq",Context.MODE_PRIVATE);
        try{
            runOnMainSync(ApplicationLoader::postInitApplication);previous=GhostSpeechModels.choice();
            check(!GhostGroq.configured(),"Existing Groq configuration: refusing to overwrite it");original=new HashMap<>(prefs.getAll());edited=true;
            String fake="gsk_test_only_not_valid_never_send";
            GhostGroq.save(fake,GhostGroq.MODELS[0],false);
            check(fake.equals(GhostGroq.key()),"Keystore decrypt roundtrip");check(!prefs.getString("key","").contains(fake),"No plaintext key in preferences");
            GhostSpeechModels.choose(GhostSpeechModels.GROQ);check(GhostSpeechModels.resolve()==4&&LocalWhisper.isAvailable(),"Explicit cloud selection");
            GhostSpeechModels.choose(GhostSpeechModels.AUTO);check(GhostSpeechModels.resolve()!=4,"Auto must stay offline");
            Method capture=GhostGroq.class.getDeclaredMethod("capture",String.class,String.class,GhostGroqHttp.Response.class);capture.setAccessible(true);
            Map<String,String> headers=new HashMap<>();headers.put("x-ratelimit-limit-requests","2000");headers.put("x-ratelimit-remaining-requests","1934");headers.put("x-ratelimit-reset-requests","2m3.5s");
            capture.invoke(null,fake,GhostGroq.MODELS[0],new GhostGroqHttp.Response(200,"",headers));
            String text=GhostGroq.quotaText(fake,GhostGroq.MODELS[0]);check(text.contains("1934 / 2000"),"Server remaining request count");
            check(!GhostGroq.quotaText(fake,GhostGroq.MODELS[1]).contains("1934 / 2000"),"Quota isolated by model");
            check(!GhostGroq.quotaText(fake+"other",GhostGroq.MODELS[0]).contains("1934 / 2000"),"Quota isolated by credential");
            Method duration=GhostGroq.class.getDeclaredMethod("duration",String.class);duration.setAccessible(true);
            check((Long)duration.invoke(null,"2m3.5s")==123500L,"Fractional reset time");check((Long)duration.invoke(null,"bad")==0,"Invalid reset time");
            headers.put("retry-after","17");capture.invoke(null,fake,GhostGroq.MODELS[0],new GhostGroqHttp.Response(429,"",headers));
            Method wait=GhostGroq.class.getDeclaredMethod("checkWait",String.class,String.class);wait.setAccessible(true);boolean blocked=false;
            try{wait.invoke(null,fake,GhostGroq.MODELS[0]);}catch(InvocationTargetException e){blocked=e.getCause() instanceof IOException;}check(blocked,"Honor Retry-After without sending another request");
            File fixture=new File(getTargetContext().getFilesDir(),"ghost_media_tests/russian.mp4");
            if(fixture.isFile()){
                Method audio=GhostGroq.class.getDeclaredMethod("audioTrack",File.class);audio.setAccessible(true);extracted=(File)audio.invoke(null,fixture);
                check(!extracted.equals(fixture)&&extracted.length()>0,"Temporary audio extraction");MediaExtractor media=new MediaExtractor();
                try{media.setDataSource(extracted.getAbsolutePath());check(media.getTrackCount()==1&&media.getTrackFormat(0).getString(MediaFormat.KEY_MIME).startsWith("audio/"),"Do not upload video frames");media.selectTrack(0);check(media.readSampleData(java.nio.ByteBuffer.allocate(1024*1024),0)>0,"Extracted audio is readable");}finally{media.release();}
                report.putString("video","PASS: synthetic MP4 converted to a single readable audio track");
            }
            Activity activity=startActivitySync(new Intent(getTargetContext(),LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(1200);
            AtomicReference<Throwable> failure=new AtomicReference<>();BaseFragment[] top={null};
            runOnMainSync(()->{try{
                top[0]=((LaunchActivity)activity).getActionBarLayout().getLastFragment();check(top[0]!=null,"Visible fragment");
                GhostGroqSettings.show(top[0],()->{});
                android.app.Dialog dialog=top[0].getVisibleDialog();check(dialog!=null&&dialog.isShowing(),"Native Groq dialog displayed");
                ArrayList<EditTextBoldCursor> inputs=new ArrayList<>();findInputs(dialog.getWindow().getDecorView(),inputs);
                check(inputs.size()==1&&inputs.get(0).length()==0&&inputs.get(0).getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod,"Masked API key input with no plaintext prefill");
                check(hasText(dialog.getWindow().getDecorView(),"Доступная квота"),"Native quota menu present");
                dialog.dismiss();
            }catch(Throwable error){failure.set(error);}});
            if(failure.get()!=null)throw failure.get();
            report.putString("storage","PASS: Android Keystore AES/GCM roundtrip, encrypted preferences, no plaintext prefill");
            report.putString("quota","PASS: server headers, per-model/key isolation, fractional reset and Retry-After");
            report.putString("ui","PASS: native configuration dialog, masked input, model and quota controls");report.putString("network","NONE: no Groq request, no private audio, no message sends");
        }catch(Throwable error){report.putString("error",android.util.Log.getStackTraceString(error));}
        finally{
            if(extracted!=null)extracted.delete();
            if(edited){SharedPreferences.Editor restore=prefs.edit().clear();for(Map.Entry<String,?> e:original.entrySet()){
                Object v=e.getValue();if(v instanceof String)restore.putString(e.getKey(),(String)v);else if(v instanceof Boolean)restore.putBoolean(e.getKey(),(Boolean)v);else if(v instanceof Long)restore.putLong(e.getKey(),(Long)v);else if(v instanceof Integer)restore.putInt(e.getKey(),(Integer)v);else if(v instanceof Float)restore.putFloat(e.getKey(),(Float)v);
            }restore.commit();GhostSpeechModels.choose(previous);}
        }
        finish(report.containsKey("error")?0:-1,report);
    }
    static void findInputs(View view,List<EditTextBoldCursor> out){if(view instanceof EditTextBoldCursor)out.add((EditTextBoldCursor)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)findInputs(((ViewGroup)view).getChildAt(i),out);}
    static boolean hasText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasText(((ViewGroup)view).getChildAt(i),text))return true;return false;}
}
