"""Exercise production manual-media policy/action with isolated Java transport/storage doubles.

No Android UI, real Telegram session, user messages or media are used.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'org/telegram/tgnet/TLRPC.java': '''package org.telegram.tgnet;
public class TLRPC {
 public static class Message { public boolean out, video, gif,round; public int ttl,id; public long dialog_id,random_id; public MessageMedia media; }
 public static class TL_message_secret extends Message {}
 public static class MessageMedia { public int ttl_seconds; public Photo photo; public Document document; }
 public static class TL_messageMediaPaidMedia extends MessageMedia {}
 public static class Photo {} public static class TL_photoEmpty extends Photo {}
 public static class Document {} public static class TL_documentEmpty extends Document {}
}''',
    'org/telegram/messenger/MessageObject.java': '''package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class MessageObject {
 public int currentAccount; public boolean forceExpired; public TLRPC.Message messageOwner;
 public static boolean isOut(TLRPC.Message m){return m.out;}
 public static TLRPC.MessageMedia getMedia(TLRPC.Message m){return m.media;}
 public static boolean isVideoMessage(TLRPC.Message m){return m.video;}
 public static boolean isGifMessage(TLRPC.Message m){return m.gif;}
 public static boolean isRoundVideoMessage(TLRPC.Message m){return m.round;}
 public long getDialogId(){return messageOwner.dialog_id;} public int getId(){return messageOwner.id;}
 public boolean isSecret(){return messageOwner instanceof TLRPC.TL_message_secret;}
}''',
    'org/telegram/messenger/MessagesController.java': '''package org.telegram.messenger;
public class MessagesController {
 private final int account; private MessagesController(int a){account=a;}
 public static MessagesController getInstance(int a){return new MessagesController(a);}
 public void markMessageAsRead(long d,long r,int t){ManualMediaTest.trace.add("secret:"+account+":"+d+":"+r+":"+t);}
 public void markMessageAsRead2(long d,int m,Object c,int ttl,long task,boolean create){ManualMediaTest.trace.add("read:"+account+":"+d+":"+m+":"+ttl+":"+create);}
}''',
    'org/telegram/messenger/MessagesStorage.java': '''package org.telegram.messenger;
import java.util.ArrayList;
public class MessagesStorage {
 private final int account; private MessagesStorage(int a){account=a;}
 public static MessagesStorage getInstance(int a){return new MessagesStorage(a);}
 public static class Queue { public void postRunnable(Runnable r){ManualMediaTest.pending.add(r);} }
 public Queue getStorageQueue(){return new Queue();}
 public void emptyMessagesMedia(long d,ArrayList<Integer> ids){ManualMediaTest.trace.add("empty:"+account+":"+d+":"+ids);}
}''',
    'org/telegram/messenger/MessageHistoryStore.java': '''package org.telegram.messenger;
import java.util.ArrayList;
public class MessageHistoryStore {
 public static MessageHistoryStore getInstance(){return new MessageHistoryStore();}
 public void forgetMessages(int a,long d,ArrayList<Integer> ids){ManualMediaTest.trace.add("forget:"+a+":"+d+":"+ids);}
}''',
    'org/telegram/messenger/FileLog.java': '''package org.telegram.messenger;
public class FileLog { public static void e(Exception e){throw new AssertionError(e);} }''',
    'org/telegram/messenger/ManualMediaTest.java': '''package org.telegram.messenger;
import java.util.ArrayList; import java.util.Arrays; import org.telegram.tgnet.TLRPC;
public class ManualMediaTest {
 static ArrayList<String> trace=new ArrayList<>(); static ArrayList<Runnable> pending=new ArrayList<>();
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
 static MessageObject message(int kind,int ttl,boolean outgoing,boolean secret){
  MessageObject o=new MessageObject();o.currentAccount=2;
  TLRPC.Message m=secret?new TLRPC.TL_message_secret():new TLRPC.Message();o.messageOwner=m;
  m.out=outgoing;m.id=25;m.dialog_id=123;m.random_id=456;m.media=new TLRPC.MessageMedia();
  if(secret)m.ttl=ttl;else m.media.ttl_seconds=ttl;
  if(kind==0)m.media.photo=new TLRPC.Photo();else if(kind==4)m.media.photo=new TLRPC.TL_photoEmpty();
  else{m.media.document=kind==5?new TLRPC.TL_documentEmpty():new TLRPC.Document();m.video=kind==1||kind==5;m.gif=kind==2;m.round=kind==7;}
  if(kind==6){TLRPC.MessageMedia paid=new TLRPC.TL_messageMediaPaidMedia();paid.photo=new TLRPC.Photo();paid.ttl_seconds=ttl;m.media=paid;}
  return o;
 }
 static void drain(){while(!pending.isEmpty())pending.remove(0).run();}
 public static void main(String[] args){
  int cases=0;
  for(int kind=0;kind<8;kind++)for(int ttl:new int[]{0,1,60,120,Integer.MAX_VALUE})for(boolean outgoing:new boolean[]{false,true})for(boolean secret:new boolean[]{false,true}){
   MessageObject o=message(kind,ttl,outgoing,secret);
   boolean expected=!outgoing&&ttl>0&&(kind<3||kind==7);
   check(GhostManualMedia.hold(o)==expected,"classification "+kind+":"+ttl+":"+outgoing+":"+secret);
   check(trace.isEmpty()&&pending.isEmpty(),"checking media must never send/read/delete");cases++;
  }
  check(!GhostManualMedia.hold((MessageObject)null)&&!GhostManualMedia.hold((TLRPC.Message)null),"null");
  MessageObject normal=message(0,5,false,false);GhostManualMedia.destroy(normal);
  check(normal.forceExpired&&!GhostManualMedia.hold(normal),"explicit action consumes once");
  check(trace.equals(Arrays.asList("read:2:123:25:0:false")),"read receipt without timer, original account/dialog");
  GhostManualMedia.destroy(normal);check(pending.size()==1,"double press cannot destroy twice");drain();
  check(trace.equals(Arrays.asList("read:2:123:25:0:false","forget:2:123:[25]","empty:2:123:[25]")),"purge anti-recall archive before emptying local media");trace.clear();
  MessageObject secret=message(1,60,false,true);GhostManualMedia.destroy(secret);drain();
  check(trace.equals(Arrays.asList("secret:2:123:456:"+Integer.MIN_VALUE,"forget:2:123:[25]","empty:2:123:[25]")),"secret read does not schedule TTL");trace.clear();
  MessageObject round=message(7,Integer.MAX_VALUE,false,false);
  check(GhostManualMedia.hold(round)&&trace.isEmpty(),"incoming round remains unconsumed before explicit action");
  GhostManualMedia.destroy(round);GhostManualMedia.destroy(round);drain();
  check(round.forceExpired&&trace.equals(Arrays.asList("read:2:123:25:0:false","forget:2:123:[25]","empty:2:123:[25]")),"round explicit destruction exactly once without timer");trace.clear();
  for(MessageObject o:new MessageObject[]{message(0,5,true,false),message(1,0,false,false),message(3,5,false,false),message(4,5,false,false),message(6,5,false,false)})GhostManualMedia.destroy(o);
  GhostManualMedia.destroy(null);check(trace.isEmpty()&&pending.isEmpty(),"ordinary/outgoing/empty/paid media untouched");
  System.out.println("PASS: "+cases+" classifications; explicit destruction, no automatic side effects, account isolation, no new TTL task, archive purge, repeat press and secret media.");
 }
}''',
}

with tempfile.TemporaryDirectory(prefix='ghost-manual-media-') as directory:
    output = Path(directory)
    for name, content in SOURCES.items():
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
    production = ROOT / 'TMessagesProj/src/main/java/org/telegram/messenger/GhostManualMedia.java'
    javac = shutil.which('javac')
    java = shutil.which('java')
    if not javac or not java:
        raise RuntimeError('Java JDK required')
    subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(output), str(production), *map(str, output.rglob('*.java'))], check=True)
    subprocess.run([java, '-cp', str(output), 'org.telegram.messenger.ManualMediaTest'], check=True)
