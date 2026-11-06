"""Host checks: production streaming helper and extracted production gesture state machine.

Android dispatch, window transitions and rendering still need device verification.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'TMessagesProj/src/main/java/org/telegram/ui/GhostDragDrop.java').read_text(encoding='utf-8')
start = source.index('    public static final class Gesture {')
end = source.index('        private void prepare(){', start)
gesture = source[start:end] + 'void prepare(){preparing=true;starts++;} }'
code = r'''
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.telegram.messenger.GhostDragFiles;
public class DragDropTest {
static int starts;
static boolean enabled=true;
static boolean allowed(){return enabled;}
static class SystemClock {static long now;static long uptimeMillis(){return now;}}
static class AndroidUtilities {static int dp(int n){return n;}}
static class GhostDragSettings {static boolean get(String key){return true;}}
static class MessageObject {}
static class ChatMessageCell {}
static class MotionEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5;
 int action;float x,y;MotionEvent(int a,float x,float y){action=a;this.x=x;this.y=y;}
 int getActionMasked(){return action;}float getRawX(){return x;}float getRawY(){return y;}
}
static class ChatActivity {
 boolean focus,selected,canStart=true;String text;
 boolean ghostDragCanStart(){return canStart;}boolean ghostFocusIsOpen(){return focus;}
 String ghostDragTextAt(float x,float y){return text;}
 ChatMessageCell ghostDragCellAt(float x,float y){return new ChatMessageCell();}
 ArrayList<MessageObject> ghostDragMessages(ChatMessageCell c){return new ArrayList<>(Arrays.asList(new MessageObject()));}
 boolean ghostDragSelectionActive(){return selected;}
}
''' + gesture + r'''
static void check(boolean b){if(!b)throw new AssertionError();}
static void touch(Gesture g,int action,long time,float x,float y){SystemClock.now=time;g.onTouch(new MotionEvent(action,x,y));}
interface Operation {void run()throws Exception;}
static void fails(Operation f)throws Exception{try{f.run();}catch(IOException expected){return;}throw new AssertionError("Expected failure");}
static class Generated extends InputStream {
 long remaining;int largest;Generated(long n){remaining=n;}
 public int read(){if(remaining==0)return -1;remaining--;return (int)(remaining&255);}
 public int read(byte[] b,int off,int len){largest=Math.max(largest,len);if(remaining==0)return -1;int n=(int)Math.min(remaining,len);Arrays.fill(b,off,off+n,(byte)17);remaining-=n;return n;}
}
public static void main(String[] args)throws Exception {
 byte[] input=new byte[190003];new Random(19).nextBytes(input);ByteArrayOutputStream out=new ByteArrayOutputStream();
 long copied=GhostDragFiles.copy(new ByteArrayInputStream(input),out,input.length,input.length,()->false,n->{});
 check(copied==input.length&&Arrays.equals(input,out.toByteArray()));
 fails(()->GhostDragFiles.copy(new ByteArrayInputStream(input),new ByteArrayOutputStream(),input.length+1,input.length+1,()->false,n->{}));
 fails(()->GhostDragFiles.copy(new ByteArrayInputStream(input),new ByteArrayOutputStream(),-1,input.length-1,()->false,n->{}));
 fails(()->GhostDragFiles.copy(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),-1,999,()->false,n->{}));
 fails(()->GhostDragFiles.copy(null,new ByteArrayOutputStream(),-1,999,()->false,n->{}));
 fails(()->GhostDragFiles.copy(new InputStream(){public int read()throws IOException{throw new IOException("lost provider");}},new ByteArrayOutputStream(),-1,999,()->false,n->{}));
 AtomicBoolean cancel=new AtomicBoolean();AtomicLong written=new AtomicLong();
 OutputStream cancelling=new OutputStream(){public void write(int b){}public void write(byte[] b,int o,int n){written.addAndGet(n);cancel.set(true);}};
 fails(()->GhostDragFiles.copy(new Generated(999999),cancelling,-1,999999,cancel::get,n->{}));check(written.get()==65536);
 Generated large=new Generated(180000000);AtomicLong total=new AtomicLong();
 OutputStream sink=new OutputStream(){public void write(int b){total.incrementAndGet();}public void write(byte[] b,int o,int n){total.addAndGet(n);}};
 GhostDragFiles.copy(large,sink,-1,200000000,()->false,n->{});check(total.get()==180000000&&large.largest<=65536);
 InputStream zeros=new InputStream(){int left=4;public int read(){return left-- > 0?7:-1;}public int read(byte[] b,int o,int n){return left>0?0:-1;}};
 out.reset();GhostDragFiles.copy(zeros,out,4,4,()->false,n->{});check(Arrays.equals(out.toByteArray(),new byte[]{7,7,7,7}));
 check(GhostDragFiles.name("../x\\y").equals(".._x_y")&&GhostDragFiles.name("..").equals("file")&&GhostDragFiles.name(null).equals("file"));
 ChatActivity host=new ChatActivity();Gesture g=new Gesture(host);
 touch(g,0,0,0,0);touch(g,2,999,11,0);check(starts==0);touch(g,2,1000,12,0);check(starts==1);touch(g,2,1200,15,0);check(starts==1);
 touch(g,1,1300,15,0);touch(g,2,2000,20,0);check(starts==1);
 host.selected=true;touch(g,0,2000,0,0);touch(g,2,2349,11,0);check(starts==1);touch(g,2,2350,12,0);check(starts==2);
 host.selected=false;touch(g,0,3000,0,0);touch(g,2,3100,0,22);touch(g,2,4500,0,25);check(starts==2);
 touch(g,0,5000,0,0);touch(g,3,5100,0,0);touch(g,2,6500,12,0);check(starts==2);
 touch(g,0,7000,0,0);touch(g,5,7100,0,0);touch(g,2,8500,12,0);check(starts==2);
 host.focus=true;touch(g,0,9000,0,0);touch(g,2,11000,12,0);check(starts==2);host.focus=false;
 host.text="chosen fragment";check(g.onTouch(new MotionEvent(0,0,0)));touch(g,2,11350,11,0);check(starts==3);
 g.starting=true;check(g.onTouch(new MotionEvent(3,0,0)));check(g.touching);g.starting=false;g.reset();check(!g.touching);
 enabled=false;touch(g,0,12000,0,0);touch(g,2,14000,30,0);check(starts==3);
 System.out.println("Drag & Drop: byte preservation, 180 MB bounded copy, cancellation, bad/empty/partial streams, safe names; hold thresholds, scrolling, release, multitouch and native-start cancellation passed (host tests).");
}
}
'''
with tempfile.TemporaryDirectory(prefix='ghost-drag-test-') as folder:
    path=Path(folder)
    test=path/'DragDropTest.java';test.write_text(code,encoding='utf-8')
    helper=root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostDragFiles.java'
    subprocess.run(['javac','-encoding','UTF-8','-d',folder,str(helper),str(test)],check=True)
    subprocess.run(['java','-Xmx32m','-cp',folder,'DragDropTest'],check=True)
