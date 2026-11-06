"""Exercise the production drop handler using host UI doubles, without network sends."""
from pathlib import Path
import subprocess
import tempfile

root=Path(__file__).resolve().parents[1]
source=(root/'TMessagesProj/src/main/java/org/telegram/ui/GhostDragDrop.java').read_text(encoding='utf-8')
a=source.index('        private boolean event(View target,DragEvent event,boolean onInput){')
b=source.index('        public void destroy()',a)
handler=source[a:b]
code=r'''
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
public class DropReceiverTest {
static class GhostDragSettings {static Set<String> off=new HashSet<>();static boolean get(String k){return !off.contains(k);}}
static class Uri {String scheme;Uri(String s){scheme=s;}String getScheme(){return scheme;}public String toString(){return scheme+"://link";}}
static class ClipData {
 static class Item {String text;Uri uri;Item(String s){text=s;}Item(Uri u){uri=u;}CharSequence getText(){return text;}Uri getUri(){return uri;}}
 ArrayList<Item> items=new ArrayList<>();ClipData(Item... values){items.addAll(Arrays.asList(values));}int getItemCount(){return items.size();}Item getItemAt(int n){return items.get(n);}
}
static class DragEvent {
 static final int ACTION_DRAG_ENDED=4,ACTION_DRAG_STARTED=1,ACTION_DRAG_ENTERED=5,ACTION_DRAG_LOCATION=2,ACTION_DRAG_EXITED=6,ACTION_DROP=3;
 int action;float x,y;ClipData clip;Payload payload;DragEvent(int a,float x,float y,ClipData c,Payload p){action=a;this.x=x;this.y=y;clip=c;payload=p;}
 int getAction(){return action;}Object getClipDescription(){return clip;}float getX(){return x;}float getY(){return y;}ClipData getClipData(){return clip;}
}
static class View {void getLocationOnScreen(int[] xy){xy[0]=0;xy[1]=0;}}
static class Input extends View {
 StringBuilder text=new StringBuilder("hello world");int caret;boolean visible=true,enabled=true;
 boolean isShown(){return visible;}boolean isEnabled(){return enabled;}int getWidth(){return 100;}int getHeight(){return 30;}
 void getLocationOnScreen(int[] xy){xy[0]=0;xy[1]=100;}
 int length(){return text.length();}StringBuilder getText(){return text;}int getOffsetForPosition(float x,float y){return (int)x;}void setSelection(int n){caret=n;}void requestFocus(){}
}
static class Payload {int sourceAccount=0;long sourceDialog=10;ArrayList<Object> items=new ArrayList<>();AtomicBoolean claimed=new AtomicBoolean();Object targetContext;}
static Payload active;
static Payload from(DragEvent e){return e.payload;}
static void restoreSource(Payload p){}
static int external;
static void receiveExternal(Host h,DragEvent e,boolean preview){external++;h.preview=preview;}
static void notice(String s){}
static boolean accepts(Object d){return GhostDragSettings.get("enabled");}
static boolean fileDescription(Object d){return hasFileUris((ClipData)d);}
static boolean hasFileUris(ClipData d){for(ClipData.Item i:d.items)if(i.uri!=null&&i.uri.scheme.equals("content"))return true;return false;}
static class Host {
 int received;boolean preview,canReceive=true;
 int getCurrentAccount(){return 0;}long getDialogId(){return 10;}
 int ghostDragDropArea(float x,float y){return y<0||y>130||x<0||x>100?0:y>=100?2:1;}
 boolean ghostDragCanReceive(Payload p){return canReceive;}
 Object ghostDragContext(){return new Object();}
 void ghostDragReceive(Payload p,boolean preview){received++;this.preview=preview;}
}
static class Receiver {
 Host host;Input input;boolean handled,hintFiles,highlighted;Receiver(Host h,Input i){host=h;input=i;}
 void highlight(boolean b){highlighted=b;}void cancelNavigation(){}
''' + handler + r'''
}
static void check(boolean b){if(!b)throw new AssertionError();}
static DragEvent drop(float x,float y,ClipData c,Payload p){return new DragEvent(3,x,y,c,p);}
static ClipData file(){return new ClipData(new ClipData.Item(new Uri("content")));}
static Payload payload(){Payload p=new Payload();p.items.add(new Object());return p;}
public static void main(String[] args){
 View root=new View();Host h=new Host();Input input=new Input();Receiver r=new Receiver(h,input);Payload p=payload();
 check(!r.event(root,drop(10,-1,file(),p),false));check(h.received==0&&!p.claimed.get());
 check(r.event(root,drop(10,50,file(),p),false));check(h.received==1&&!h.preview);
 check(!r.event(root,drop(10,50,file(),p),false));check(h.received==1);
 Receiver other=new Receiver(h,input);check(!other.event(root,drop(10,50,file(),p),false));check(h.received==1);
 r=new Receiver(h,input);p=payload();check(r.event(root,drop(10,110,file(),p),false));check(h.received==2&&h.preview);
 r=new Receiver(h,input);p=payload();GhostDragSettings.off.add("same_chat");check(!r.event(root,drop(10,50,file(),p),false));check(!p.claimed.get());GhostDragSettings.off.clear();
 h.canReceive=false;check(!r.event(root,drop(10,50,file(),p),false));check(!p.claimed.get());h.canReceive=true;
 GhostDragSettings.off.add("files");check(!r.event(root,drop(10,50,file(),p),false));GhostDragSettings.off.clear();
 GhostDragSettings.off.add("multiple");ClipData two=new ClipData(new ClipData.Item(new Uri("content")),new ClipData.Item(new Uri("content")));check(!r.event(root,drop(10,50,two,p),false));GhostDragSettings.off.clear();
 r=new Receiver(h,input);check(r.event(root,drop(10,110,file(),null),false));check(external==1&&h.preview);
 r=new Receiver(h,input);ClipData text=new ClipData(new ClipData.Item("X"));int sends=h.received;
 check(!r.event(root,drop(5,50,text,null),false));check(input.text.toString().equals("hello world"));
 check(r.event(root,drop(5,110,text,null),false));check(input.text.toString().equals("helloX world")&&input.caret==6&&h.received==sends);
 r=new Receiver(h,input);GhostDragSettings.off.add("text");check(!r.event(root,drop(5,110,text,null),false));GhostDragSettings.off.clear();
 input.visible=false;check(!r.event(root,drop(5,110,text,null),false));input.visible=true;
 r.highlighted=true;GhostDragSettings.off.add("enabled");check(r.event(root,new DragEvent(4,0,0,file(),p),false));check(!r.highlighted&&h.received==sends);
 System.out.println("Drop receiver: invalid areas, body send vs preview, rights, same-chat/files/multiple switches, external files, text insertion without send, per-drag deduplication and disabled-state cleanup passed (host doubles).");
}
}
'''
with tempfile.TemporaryDirectory(prefix='ghost-drop-receiver-') as temp:
    file=Path(temp)/'DropReceiverTest.java';file.write_text(code,encoding='utf-8')
    subprocess.run(['javac','-encoding','UTF-8','-d',temp,str(file)],check=True)
    subprocess.run(['java','-cp',temp,'DropReceiverTest'],check=True)
