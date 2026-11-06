package org.telegram.messenger;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.net.Uri;
import android.view.*;
import android.widget.FrameLayout;
import android.graphics.BitmapFactory;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.telegram.ui.*;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.GhostChatScreenshot;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.tgnet.TLRPC;

/** Uses synthetic messages, short taps and a neutral map; never sends messages or records. */
public final class GhostChatToolsDiagnostics extends Instrumentation {
    Bundle options;
    @Override public void onCreate(Bundle args){super.onCreate(args);options=args==null?new Bundle():args;start();}
    static Object field(Object object,String name)throws Exception{Class<?> c=object.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new NoSuchFieldException(name);}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void tap(View view){long t=SystemClock.uptimeMillis();MotionEvent d=MotionEvent.obtain(t,t,0,24,24,0),u=MotionEvent.obtain(t,t+25,1,24,24,0);view.dispatchTouchEvent(d);view.dispatchTouchEvent(u);d.recycle();u.recycle();}
    @Override public void onStart(){
        Bundle report=new Bundle();Object[] export={null};ArrayList<Uri> created=new ArrayList<>();
        try{
            runOnMainSync(ApplicationLoader::postInitApplication);
            Activity activity=startActivitySync(new Intent(getTargetContext(),LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1200);
            ChatActivity[] source={null};AtomicReference<Throwable> failure=new AtomicReference<>();
            runOnMainSync(()->{try{
                LaunchActivity launch=(LaunchActivity)activity;BaseFragment top=launch.getActionBarLayout().getLastFragment();
                if(!(top instanceof ChatActivity)||((ChatActivity)top).getDialogId()!=UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId()){
                    Bundle args=new Bundle();args.putLong("user_id",UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
                    ChatActivity savedChat=new ChatActivity(args);savedChat.setCurrentAccount(UserConfig.selectedAccount);launch.getActionBarLayout().presentFragment(savedChat);source[0]=savedChat;
                }else{
                    source[0]=(ChatActivity)top;
                }
            }catch(Throwable error){failure.set(error);}});
            if(failure.get()!=null)throw failure.get();SystemClock.sleep(800);
            runOnMainSync(()->{try{
                LaunchActivity launch=(LaunchActivity)activity;
                check(source[0].getDialogId()==UserConfig.getInstance(source[0].getCurrentAccount()).getClientUserId(),"Only Saved Messages may be used");
                check(!launch.isGhostDrawerHome(),"drawer must be disabled in chat");GhostLegacyDrawer.show(launch);check(launch.ghostLegacyDrawer==null,"direct drawer must be blocked in chat");
                ChatActivityEnterView enter=(ChatActivityEnterView)field(source[0],"chatActivityEnterView");check(enter.getEditField().length()==0,"Draft must be empty");
                Method mode=ChatActivityEnterView.class.getDeclaredMethod("isInVideoMode");mode.setAccessible(true);boolean initial=(Boolean)mode.invoke(enter);View button=enter.getAudioVideoButtonContainer();
                tap(button);check((Boolean)mode.invoke(enter)!=initial,"first tap toggles mode");tap(button);check((Boolean)mode.invoke(enter)==initial,"second tap restores mode");
                check(!(Boolean)field(enter,"recordingAudioVideo"),"taps must not record");report.putString("camera","PASS: short taps switch voice/video both ways; original mode restored, no recording");
            }catch(Throwable error){failure.set(error);}});
            if(failure.get()!=null)throw failure.get();report.putString("drawer","PASS: gesture and direct opening blocked inside chat");
            runOnMainSync(()->{try{
                Calendar selected=Calendar.getInstance();selected.set(2024,1,29);
                Method factory=GhostChatScreenshot.class.getDeclaredMethod("datePicker",ChatActivity.class,Calendar.class,String.class,AlertsCreator.DatePickerDelegate.class);factory.setAccessible(true);
                org.telegram.ui.ActionBar.AlertDialog picker=(org.telegram.ui.ActionBar.AlertDialog)factory.invoke(null,source[0],selected,"Проверка даты",(AlertsCreator.DatePickerDelegate)(y,m,d)->{});
                picker.show();ArrayList<org.telegram.ui.Components.NumberPicker> values=new ArrayList<>();findPickers(picker.getWindow().getDecorView(),values);
                check(values.size()==3,"three date wheels");boolean year=false;
                for(org.telegram.ui.Components.NumberPicker value:values)if(value.getMaxValue()>1000){check(value.getMinValue()==2013&&value.getMaxValue()==2037&&value.getValue()==2024,"absolute years must not shift into 4000s");year=true;}
                check(year,"year wheel found");picker.dismiss();report.putString("date_picker","PASS: 2013..2037, selected leap year 2024 unchanged");
            }catch(Throwable error){failure.set(error);}});
            if(failure.get()!=null)throw failure.get();
            if(!"true".equals(options.getString("skip_maps"))){
            CountDownLatch tiles=new CountDownLatch(1);IMapsProvider.IMapView[] map={null};FrameLayout[] mapHost={null};
            runOnMainSync(()->{try{
                GoogleMapsProvider provider=new GoogleMapsProvider();provider.initializeMaps(activity);map[0]=provider.onCreateMapView(activity);map[0].onCreate(null);
                mapHost[0]=new FrameLayout(activity);mapHost[0].addView(map[0].getView(),new FrameLayout.LayoutParams(-1,-1));activity.addContentView(mapHost[0],new ViewGroup.LayoutParams(AndroidUtilities.dp(240),AndroidUtilities.dp(240)));map[0].onResume();
                map[0].getMapAsync(m->{report.putString("maps_ready","YES");m.setOnMapLoadedCallback(tiles::countDown);m.moveCamera(provider.newCameraUpdateLatLngZoom(new IMapsProvider.LatLng(55.75,37.61),12));});
            }catch(Throwable error){report.putString("maps_error",android.util.Log.getStackTraceString(error));tiles.countDown();}});
            boolean loaded=tiles.await(60,TimeUnit.SECONDS);report.putString("maps_tiles",loaded&&!report.containsKey("maps_error")?"PASS: Google map tiles loaded":"NOT_LOADED: inspect SDK authorization log");
            runOnMainSync(()->{if(map[0]!=null){map[0].onPause();map[0].onDestroy();}if(mapHost[0]!=null)AndroidUtilities.removeFromParent(mapHost[0]);});
            }
            final int messageCount=Math.max(45,Integer.parseInt(options.getString("messages","45")));
            long exportStarted=SystemClock.elapsedRealtime();
            runOnMainSync(()->{try{
                Class<?> type=Class.forName("org.telegram.ui.Components.GhostChatScreenshot$Export");Constructor<?> constructor=type.getDeclaredConstructor(ChatActivity.class,int.class,int.class);constructor.setAccessible(true);export[0]=constructor.newInstance(source[0],0,Integer.MAX_VALUE);
                Method add=type.getDeclaredMethod("add",TLRPC.Message.class);add.setAccessible(true);int now=(int)(System.currentTimeMillis()/1000);
                for(int i=0;i<messageCount;i++){
                    TLRPC.TL_message m=new TLRPC.TL_message();m.id=-1000-i;m.date=now-30*86400+(int)(i*30L*86400/messageCount);m.out=i%2==0;m.flags=256;m.message=i==10?repeat("Длинное проверочное сообщение без сокращения текста. ",180):"Проверка экспорта "+i+" · 2 + 2 = 4 · 😀";
                    m.peer_id=new TLRPC.TL_peerUser();m.peer_id.user_id=source[0].getDialogId();m.from_id=new TLRPC.TL_peerUser();m.from_id.user_id=m.out?source[0].getDialogId():0;m.media=new TLRPC.TL_messageMediaEmpty();add.invoke(export[0],m);
                }
                for(MessageObject fixture:(ArrayList<MessageObject>)field(export[0],"rows"))fixture.isOutOwnerCached=fixture.messageOwner.out;
                Method prepare=type.getDeclaredMethod("prepare");prepare.setAccessible(true);prepare.invoke(export[0]);
                // Destroy the source chat while native rendering continues on its captured data.
                ((LaunchActivity)activity).getActionBarLayout().presentFragment(new GhostTasksActivity(),true);
            }catch(Throwable error){failure.set(error);}});
            if(failure.get()!=null)throw failure.get();long end=SystemClock.elapsedRealtime()+Math.max(90000,messageCount*30L);boolean complete=false;
            while(SystemClock.elapsedRealtime()<end){
                boolean[] done={false};runOnMainSync(()->{try{if((Boolean)field(export[0],"canceled"))throw new AssertionError("Export: "+field(export[0],"failureReason"));done[0]=field(export[0],"host")==null&&(Integer)field(export[0],"part")>0;}catch(Throwable error){failure.set(error);}});
                if(failure.get()!=null)throw failure.get();if(done[0]){complete=true;break;}SystemClock.sleep(150);
            }
            check(complete,"Synthetic export timeout");created.addAll((ArrayList<Uri>)field(export[0],"saved"));check(created.size()==1,"Entire chat must be one PNG");int number=0;
            for(Uri uri:created){
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;try(InputStream in=getTargetContext().getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}check(options.outWidth==(Integer)field(export[0],"outputWidth")&&options.outHeight>8192,"One adaptive long PNG, beyond stripe bitmap height");
                File fixture=new File(getTargetContext().getFilesDir(),"ghost53-export-fixture-"+(number++)+".png");try(InputStream in=getTargetContext().getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(fixture)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            }
            report.putString("screenshot","PASS: "+messageCount+" synthetic messages across 30 days, incoming/outgoing, long text, native cells/wallpaper, ONE long PNG at "+field(export[0],"outputWidth")+"px saved to gallery");
            report.putLong("screenshot_elapsed_ms",SystemClock.elapsedRealtime()-exportStarted);
            check((Integer)field(export[0],"index")==messageCount,"Every selected message must be rendered");
            check(!source[0].getFragmentView().isAttachedToWindow(),"Source chat was detached during background export");
            report.putString("background","PASS: source chat detached; native task screen remains interactive; export continues to one complete PNG");
        }catch(Throwable error){report.putString("error",android.util.Log.getStackTraceString(error));}
        finally{
            for(Uri uri:created)getTargetContext().getContentResolver().delete(uri,null,null);
            if(export[0]!=null&&report.containsKey("error"))runOnMainSync(()->{try{Method cancel=export[0].getClass().getDeclaredMethod("cancel");cancel.setAccessible(true);cancel.invoke(export[0]);}catch(Exception ignored){}});
        }
        finish(report.containsKey("error")?0:-1,report);
    }
    static String repeat(String s,int count){StringBuilder result=new StringBuilder();for(int i=0;i<count;i++)result.append(s);return result.toString();}
    static void findPickers(View view,ArrayList<org.telegram.ui.Components.NumberPicker> result){if(view instanceof org.telegram.ui.Components.NumberPicker)result.add((org.telegram.ui.Components.NumberPicker)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)findPickers(((ViewGroup)view).getChildAt(i),result);}
}
