package org.telegram.messenger;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Components.GhostCameraPopup;

/** A separate inert anchor. Exercises real Android windows without touching the recorder. */
public final class GhostCameraPopupDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private static MotionEvent event(int action,float x,float y){long now=SystemClock.uptimeMillis();return MotionEvent.obtain(now,now,action,x,y,0);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @Override public void onStart(){
        Bundle result=new Bundle();AtomicReference<Throwable> failure=new AtomicReference<>();
        try{
            ApplicationLoader.postInitApplication();
            Activity activity=startActivitySync(new Intent(getTargetContext(),LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            FrameLayout overlay=new FrameLayout(activity);View anchor=new View(activity);AtomicInteger count=new AtomicInteger();
            runOnMainSync(()->{
                FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(AndroidUtilities.dp(48),AndroidUtilities.dp(48),Gravity.RIGHT|Gravity.BOTTOM);p.rightMargin=AndroidUtilities.dp(24);p.bottomMargin=AndroidUtilities.dp(180);overlay.addView(anchor,p);activity.addContentView(overlay,new android.view.ViewGroup.LayoutParams(-1,-1));
            });waitForIdleSync();
            for(int scenario=0;scenario<6;scenario++){
                final int which=scenario;final GhostCameraPopup[] popup=new GhostCameraPopup[1];final int[] pos=new int[2];
                runOnMainSync(()->{anchor.getLocationOnScreen(pos);popup[0]=new GhostCameraPopup(anchor,null,front->count.addAndGet(front?1:10));MotionEvent down=event(MotionEvent.ACTION_DOWN,pos[0]+24,pos[1]+24);popup[0].show(down);down.recycle();});waitForIdleSync();
                runOnMainSync(()->{try{
                    int before=count.get();
                    if(which==0||which==1){View row=((android.view.ViewGroup)popup[0].getContentView()).getChildAt(which);int[] screen=new int[2];row.getLocationOnScreen(screen);MotionEvent move=event(MotionEvent.ACTION_MOVE,screen[0]+30,screen[1]+20);popup[0].pointer(move);move.recycle();check(row.isPressed(),"drag highlight");MotionEvent up=event(MotionEvent.ACTION_UP,screen[0]+30,screen[1]+20);popup[0].pointer(up);up.recycle();check(!popup[0].isShowing()&&count.get()==before+(which==0?1:10),"exactly one camera chosen");}
                    else if(which==2){MotionEvent up=event(MotionEvent.ACTION_UP,1,1);popup[0].pointer(up);up.recycle();check(!popup[0].isShowing()&&count.get()==before,"release outside cancels");}
                    else if(which==3){MotionEvent cancel=event(MotionEvent.ACTION_CANCEL,pos[0]+24,pos[1]+24);popup[0].pointer(cancel);cancel.recycle();check(!popup[0].isShowing()&&count.get()==before,"cancel does not choose");}
                    else if(which==4){MotionEvent up=event(MotionEvent.ACTION_UP,pos[0]+24,pos[1]+24);popup[0].pointer(up);up.recycle();check(popup[0].isShowing()&&count.get()==before,"tap keeps menu");((android.view.ViewGroup)popup[0].getContentView()).getChildAt(1).performClick();check(count.get()==before+10&&!popup[0].isShowing(),"second tap chooses once");}
                    else{popup[0].dismiss();check(count.get()==before,"outside dismissal does not choose");}
                }catch(Throwable error){failure.set(error);}finally{popup[0].dismiss();}});
                if(failure.get()!=null)break;
            }
            runOnMainSync(()->{android.view.ViewParent parent=overlay.getParent();if(parent instanceof android.view.ViewGroup)((android.view.ViewGroup)parent).removeView(overlay);});
            if(failure.get()!=null)throw failure.get();
            result.putString("camera","PASS: front/rear slide selection, highlight, tap then choose, release outside, cancel, dismiss and six repeated openings; recorder never invoked");
        }catch(Throwable error){result.putString("error",android.util.Log.getStackTraceString(error));}
        finish(result.containsKey("error")?0:-1,result);
    }
}
