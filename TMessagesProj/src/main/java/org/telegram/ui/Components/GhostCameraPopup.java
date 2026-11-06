package org.telegram.ui.Components;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.HapticFeedbackConstants;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.Theme;
import java.util.function.Consumer;

/** Owns the complete initial pointer stream; no event is replayed into the recorder. */
public final class GhostCameraPopup extends PopupWindow {
    private final ActionBarMenuSubItem[] rows=new ActionBarMenuSubItem[2];
    private final Consumer<Boolean> selected;
    private final View anchor;
    private int hovered=-1;
    private float downX,downY;
    private boolean dragging;
    public GhostCameraPopup(View anchor,Theme.ResourcesProvider provider,Consumer<Boolean> selected){
        super(anchor.getContext());this.anchor=anchor;this.selected=selected;
        LinearLayout content=new LinearLayout(anchor.getContext());content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4),dp(4),dp(4),dp(4));content.setBackground(Theme.createRoundRectDrawable(dp(12),Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground,provider)));
        setContentView(content);setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        setWidth(dp(204));setHeight(dp(104));setElevation(dp(7));setOutsideTouchable(true);setFocusable(false);setInputMethodMode(INPUT_METHOD_NOT_NEEDED);
        for(int i=0;i<2;i++){
            final int index=i;rows[i]=new ActionBarMenuSubItem(anchor.getContext(),i==0,i==1,provider);
            rows[i].setTextAndIcon(i==0?"Фронтальная":"Основная",i==0?R.drawable.stream_flip:R.drawable.camera);
            rows[i].setOnClickListener(v->choose(index));content.addView(rows[i],new LinearLayout.LayoutParams(-1,dp(48)));
        }
    }
    private static int dp(float value){return AndroidUtilities.dp(value);}
    public void show(MotionEvent event){
        downX=event.getRawX();downY=event.getRawY();dragging=false;
        if(anchor.getParent()!=null)anchor.getParent().requestDisallowInterceptTouchEvent(true);
        showAsDropDown(anchor,anchor.getWidth()-getWidth(),-anchor.getHeight()-getHeight()-dp(8));
        View content=getContentView();content.setPivotX(getWidth());content.setPivotY(getHeight());content.setAlpha(0);content.setScaleX(.94f);content.setScaleY(.94f);
        content.animate().alpha(1).scaleX(1).scaleY(1).setDuration(150).start();
    }
    private int hit(float x,float y){for(int i=0;i<rows.length;i++){int[] p=new int[2];rows[i].getLocationOnScreen(p);if(x>=p[0]&&x<p[0]+rows[i].getWidth()&&y>=p[1]&&y<p[1]+rows[i].getHeight())return i;}return -1;}
    public boolean pointer(MotionEvent event){
        if(!isShowing())return true;
        int action=event.getActionMasked();
        if(Math.abs(event.getRawX()-downX)>ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop()||Math.abs(event.getRawY()-downY)>ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop())dragging=true;
        int now=hit(event.getRawX(),event.getRawY());
        if(now!=hovered){if(hovered>=0)rows[hovered].setPressed(false);hovered=now;if(now>=0){rows[now].setPressed(true);anchor.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}}
        if(action==MotionEvent.ACTION_CANCEL){dismiss();}
        else if(action==MotionEvent.ACTION_UP){
            if(anchor.getParent()!=null)anchor.getParent().requestDisallowInterceptTouchEvent(false);
            if(now>=0)choose(now);
            else if(dragging||!inAnchor(event.getRawX(),event.getRawY()))dismiss();
            else{setFocusable(true);update();}
        }
        return true;
    }
    private void choose(int index){dismiss();selected.accept(index==0);}
    private boolean inAnchor(float x,float y){int[] p=new int[2];anchor.getLocationOnScreen(p);return x>=p[0]&&x<p[0]+anchor.getWidth()&&y>=p[1]&&y<p[1]+anchor.getHeight();}
    @Override public void dismiss(){if(anchor.getParent()!=null)anchor.getParent().requestDisallowInterceptTouchEvent(false);super.dismiss();}
}
