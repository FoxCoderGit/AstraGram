package org.telegram.messenger;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.text.*;
import android.text.style.ClickableSpan;
import android.view.*;
import android.widget.FrameLayout;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.AvatarSpan;
import org.telegram.ui.Components.*;

/** Real Android layouts and touch events; callbacks are counters, never gift purchases. */
public final class GhostRtlGiftDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void touch(View view,float x,float y,int action){long t=SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(t,t,action,x,y,0);view.dispatchTouchEvent(e);e.recycle();}
    static RectF visibleSpan(ButtonSpan.TextViewButtons view,Object span){
        Spanned text=(Spanned)view.getText();Path path=new Path();RectF bounds=new RectF();
        view.getLayout().getSelectionPath(text.getSpanStart(span),text.getSpanEnd(span),path);path.computeBounds(bounds,true);
        bounds.offset(view.getPaddingLeft()-view.getScrollX(),view.getTextPaddingTop()-view.getScrollY());
        check(bounds.intersect(view.getPaddingLeft()+1,view.getPaddingTop()+1,view.getWidth()-view.getPaddingRight()-1,view.getHeight()-view.getPaddingBottom()-1),"Span must be visible");
        return bounds;
    }
    @Override public void onStart(){
        Bundle report=new Bundle();FrameLayout[] host={null};ArrayList<ButtonSpan.TextViewButtons> views=new ArrayList<>();ArrayList<ClickableSpan> links=new ArrayList<>();ArrayList<ButtonSpan> buttons=new ArrayList<>();ArrayList<int[]> counts=new ArrayList<>();
        try{
            runOnMainSync(ApplicationLoader::postInitApplication);
            Activity activity=startActivitySync(new Intent(getTargetContext(),LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(1000);
            AtomicReference<Throwable> failure=new AtomicReference<>();
            runOnMainSync(()->{try{
                host[0]=new FrameLayout(activity);host[0].setAlpha(0);activity.addContentView(host[0],new ViewGroup.LayoutParams(-1,-1));
                String[] names={"سيدتي","שלום","سلام","Alex","Иван","علي Alex","Alex"};
                for(int i=0;i<names.length;i++){
                    ButtonSpan.TextViewButtons view=new ButtonSpan.TextViewButtons(activity);view.setPadding(AndroidUtilities.dp(12.66f),AndroidUtilities.dp(9.33f),AndroidUtilities.dp(12.66f),AndroidUtilities.dp(9.33f));
                    view.setTextSize(14);view.setSingleLine(true);view.setEllipsize(TextUtils.TruncateAt.END);view.setDisablePaddingsOffsetY(true);
                    if(i==6)view.setTextDirection(View.TEXT_DIRECTION_RTL);
                    int[] count={0,0};counts.add(count);ClickableSpan link=new ClickableSpan(){@Override public void onClick(View widget){count[0]++;}};links.add(link);
                    SpannableStringBuilder text=new SpannableStringBuilder("x  "+names[i]);AvatarSpan avatar=new AvatarSpan(view,UserConfig.selectedAccount,24);avatar.setImageDrawable(new ColorDrawable(Color.GRAY));text.setSpan(avatar,0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);text.setSpan(link,3,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ButtonSpan button=new ButtonSpan("отправить подарок",()->count[1]++,null);buttons.add(button);view.addButton(button);view.setText(text);
                    host[0].addView(view,new FrameLayout.LayoutParams(AndroidUtilities.dp(242),AndroidUtilities.dp(44)));views.add(view);
                }
            }catch(Throwable error){failure.set(error);}});
            if(failure.get()!=null)throw failure.get();SystemClock.sleep(350);
            runOnMainSync(()->{try{
                int rtl=0;
                for(int i=0;i<views.size();i++){
                    ButtonSpan.TextViewButtons view=views.get(i);view.bringPointIntoView(0);int[] count=counts.get(i);
                    if(view.getLayout().getParagraphDirection(0)<0){rtl++;check(view.getScrollX()>100000,"Reproduce wide RTL TextView layout");}
                    RectF name=visibleSpan(view,links.get(i));check(view.hit((int)name.centerX(),(int)name.centerY())==links.get(i),"Name hit "+i);
                    touch(view,name.centerX(),name.centerY(),MotionEvent.ACTION_DOWN);touch(view,name.centerX(),name.centerY(),MotionEvent.ACTION_UP);check(count[0]==1&&count[1]==0,"Name must open profile only, case "+i);
                    RectF button=visibleSpan(view,buttons.get(i));touch(view,button.centerX(),button.centerY(),MotionEvent.ACTION_DOWN);touch(view,button.centerX(),button.centerY(),MotionEvent.ACTION_UP);check(count[0]==1&&count[1]==1,"Gift button must remain separate, case "+i);
                    touch(view,button.centerX(),button.centerY(),MotionEvent.ACTION_DOWN);touch(view,-10,-10,MotionEvent.ACTION_MOVE);touch(view,-10,-10,MotionEvent.ACTION_UP);check(count[0]==1&&count[1]==1,"Drag outside must cancel button, case "+i);
                    check(view.hit(-10,(int)name.centerY())==null,"No click outside viewport");
                    report.putString("case_"+i,"PASS: direction="+view.getLayout().getParagraphDirection(0)+", scrollX="+view.getScrollX()+", profile/button/cancel separated");
                }
                check(rtl>=3,"RTL cases were exercised");
            }catch(Throwable error){failure.set(error);}});
            if(failure.get()!=null)throw failure.get();report.putString("actions","Synthetic callbacks only; no gifts/messages sent and no user names modified");
        }catch(Throwable error){report.putString("error",android.util.Log.getStackTraceString(error));}
        finally{runOnMainSync(()->{if(host[0]!=null)AndroidUtilities.removeFromParent(host[0]);});}
        finish(report.containsKey("error")?0:-1,report);
    }
}
