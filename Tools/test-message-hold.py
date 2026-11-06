"""Host checks of the production hold handlers with a synthetic touch scheduler."""
from pathlib import Path
import subprocess
import tempfile

root=Path(__file__).resolve().parents[1]
source=(root/'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java').read_text(encoding='utf-8')
a=source.index('    private boolean ghostForceSelection,')
b=source.index('    RecyclerListView.OnItemLongClickListenerExtended',a)
handlers=source[a:b]
stub='''public class MessageHoldTest {
static class AndroidUtilities {
 static Runnable pending;static int delay;
 static void runOnUIThread(Runnable r,int ms){pending=r;delay=ms;}
 static void cancelRunOnUIThread(Runnable r){if(pending==r)pending=null;}
 static void fire(){Runnable r=pending;pending=null;if(r!=null)r.run();}
}
static class MotionEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5;
 int action;float x,y;MotionEvent(int a,float x,float y){action=a;this.x=x;this.y=y;}
 static MotionEvent obtain(long a,long b,int c,float x,float y,int d){return new MotionEvent(c,x,y);}
 int getActionMasked(){return action;}float getX(){return x;}float getY(){return y;}void recycle(){}
}
static class HapticFeedbackConstants {static final int LONG_PRESS=0;}
static class MessageObject {}
static class GhostDragGesture {boolean onTouch(MotionEvent e){return false;}}
GhostDragGesture ghostDragGesture;
static class ChatMessageCell {MessageObject message=new MessageObject();Object parent=new Object();MessageObject getMessageObject(){return message;}Object getParent(){return parent;}void performHapticFeedback(int type){}}
static class ActionBar {boolean selected;boolean isActionModeShowed(){return selected;}}
static class ListView {int cancelled;void dispatchTouchEvent(MotionEvent e){if(e.action==3)cancelled++;}void stopScroll(){}}
static class Layout {boolean scroll=true;void setCanScrollVertically(boolean s){scroll=s;}}
ActionBar actionBar=new ActionBar();ListView chatListView=new ListView();Layout chatLayoutManager=new Layout();boolean inPreviewMode,isInsideContainer,blur;int menus;
boolean isReport(){return false;}static int dp(int n){return n;}
void createMenu(ChatMessageCell c,boolean single,boolean list,float x,float y,boolean longpress){if(ghostForceSelection)actionBar.selected=true;else menus++;}
void clearSelectionMode(){actionBar.selected=false;}
boolean reenterRemoval;
boolean isGhostSelectionMember(MessageObject m){return true;} void captureGhostFocusMessages(ChatMessageCell c){}
void showGhostFocusOverlay(){}void removeGhostFocusOverlay(){if(reenterRemoval){reenterRemoval=false;trackGhostMessageHold(new MotionEvent(3,0,0));}}
void dimBehindView(ChatMessageCell c,boolean a,boolean b){blur=true;}
void dimBehindView(boolean b){blur=b;}
''' + handlers + '''
void touch(int a,float x,float y){trackGhostMessageHold(new MotionEvent(a,x,y));}
static void check(boolean b){if(!b)throw new AssertionError();}
public static void main(String[] args){
 MessageHoldTest t=new MessageHoldTest();ChatMessageCell cell=new ChatMessageCell();
 t.touch(0,10,10);check(t.beginGhostMessageHold(cell,10,10,false));check(t.actionBar.selected&&!t.blur&&t.menus==0&&AndroidUtilities.delay==450);
 t.touch(1,10,10);AndroidUtilities.fire();check(t.actionBar.selected&&!t.blur);
 t=new MessageHoldTest();t.touch(0,10,10);t.beginGhostMessageHold(cell,10,10,false);AndroidUtilities.fire();check(!t.actionBar.selected&&t.blur&&t.ghostFocusHeld&&!t.chatLayoutManager.scroll&&t.chatListView.cancelled==1&&t.menus==0);
 check(t.trackGhostMessageHold(new MotionEvent(1,10,10)));check(t.blur&&t.ghostFocusHeld&&!t.ghostHoldTouching);
 t.reenterRemoval=true;check(t.trackGhostMessageHold(new MotionEvent(0,10,10)));check(t.blur);
 check(t.trackGhostMessageHold(new MotionEvent(1,10,10)));check(!t.blur&&!t.ghostFocusHeld&&t.chatLayoutManager.scroll&&!t.actionBar.selected);
 t=new MessageHoldTest();t.touch(0,10,10);t.beginGhostMessageHold(cell,10,10,true);t.touch(2,40,10);AndroidUtilities.fire();check(t.actionBar.selected&&!t.blur);
 t=new MessageHoldTest();t.touch(0,10,10);t.beginGhostMessageHold(cell,10,10,false);t.touch(3,10,10);AndroidUtilities.fire();check(!t.blur);
 t=new MessageHoldTest();t.touch(0,10,10);t.beginGhostMessageHold(cell,10,10,false);cell.message=new MessageObject();AndroidUtilities.fire();check(!t.blur);
 t=new MessageHoldTest();t.touch(0,10,10);t.beginGhostMessageHold(cell,10,10,false);AndroidUtilities.fire();t.endGhostMessageHold();check(!t.blur&&t.chatLayoutManager.scroll);
 t=new MessageHoldTest();t.actionBar.selected=true;check(t.beginGhostMessageHold(cell,10,10,false));check(t.actionBar.selected);
 System.out.println("Message hold: selection, delayed focus, release, move, cancellation, recycling, pause and existing selection passed (host doubles).");
}}
'''
with tempfile.TemporaryDirectory(prefix='message-hold-') as temp:
    path=Path(temp)
    (path/'MessageHoldTest.java').write_text(stub,encoding='utf-8')
    android=path/'android/os/SystemClock.java';android.parent.mkdir(parents=True)
    android.write_text('package android.os; public class SystemClock {public static long uptimeMillis(){return 1;}}',encoding='utf-8')
    subprocess.run(['javac','-d',temp,str(path/'MessageHoldTest.java'),str(android)],check=True)
    subprocess.run(['java','-cp',temp,'MessageHoldTest'],check=True)
