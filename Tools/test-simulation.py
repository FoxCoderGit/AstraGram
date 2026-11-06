"""Check production transport isolation and offline response distribution on the JVM."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
policy = root / 'TMessagesProj/src/main/java/org/telegram/messenger/GhostSimulationPolicy.java'
responses = policy.with_name('GhostSimulationResponses.java')
fixture = '''package org.telegram.tgnet;
import java.util.ArrayList;
public class SimFixture {
 public static class TL_inputPeerUser { public long user_id; public TL_inputPeerUser(long id){user_id=id;} }
 public static class TL_messages_sendMessage { public Object peer; }
 public static class TL_messages_getHistory { public Object peer; }
 public static class TL_messages_newFutureWrite {}
 public static class TL_messages_getBotCallbackAnswer {}
 public static class TL_upload_saveFilePart {}
 public static class TL_upload_getFile {}
 public static class TL_account_updateStatus {}
 public static class TL_messages_setTyping {}
 public static class TL_messages_readHistory {}
 public static class TL_messages_deleteMessages { public ArrayList<Integer> id=new ArrayList<>(); }
 public static class TL_invokeWithLayer { public Object query; }
}'''
check = '''import org.telegram.messenger.*;
import org.telegram.tgnet.SimFixture.*;
import java.util.Random;
public class SimulationCheck {
 static void yes(boolean condition,String text){if(!condition)throw new AssertionError(text);}
 public static void main(String[] args){
  long fake=GhostSimulationPolicy.VIRTUAL_PREFIX|12345;
  yes(GhostSimulationPolicy.virtual(fake),"virtual peer");
  for(long id:new long[]{0,1,7176914601L,-7176914601L,Long.MIN_VALUE})yes(!GhostSimulationPolicy.virtual(id),"real peer");
  TL_messages_sendMessage send=new TL_messages_sendMessage();send.peer=new TL_inputPeerUser(123);
  yes(GhostSimulationPolicy.suppress(send,true),"active real peer write denied");
  yes(!GhostSimulationPolicy.suppress(send,false),"ordinary sending resumes");
  send.peer=new TL_inputPeerUser(fake);
  yes(GhostSimulationPolicy.suppress(send,false),"virtual write denied after exit");
  TL_messages_getHistory read=new TL_messages_getHistory();read.peer=new TL_inputPeerUser(123);
  yes(!GhostSimulationPolicy.suppress(read,true),"real history still available");
  read.peer=new TL_inputPeerUser(fake);yes(GhostSimulationPolicy.suppress(read,false),"virtual read denied after exit");
  for(Object write:new Object[]{new TL_upload_saveFilePart(),new TL_messages_setTyping(),new TL_messages_readHistory(),new TL_account_updateStatus(),new TL_messages_newFutureWrite(),new TL_messages_getBotCallbackAnswer()})
   yes(GhostSimulationPolicy.suppress(write,true),"write guard "+write.getClass());
  yes(!GhostSimulationPolicy.suppress(new TL_upload_getFile(),true),"media download allowed");
  TL_invokeWithLayer wrapped=new TL_invokeWithLayer();wrapped.query=send;
  yes(GhostSimulationPolicy.suppress(wrapped,false),"wrapped virtual request");
  wrapped.query=wrapped;yes(!GhostSimulationPolicy.suppress(wrapped,true),"cycle terminates");
  TL_messages_deleteMessages deletion=new TL_messages_deleteMessages();deletion.id.add(-1700000000);
  yes(GhostSimulationPolicy.suppress(deletion,false),"local delete never sent");
  int[] counts=new int[4];for(int i=0;i<100;i++)counts[GhostSimulationResponses.scenario(i)]++;
  yes(java.util.Arrays.equals(counts,new int[]{40,20,20,20}),"scenario weights");
  yes(GhostSimulationResponses.count(6)==1&&GhostSimulationResponses.count(100)==2&&GhostSimulationResponses.count(500)==3,"response lengths");
  Random random=new Random(123);for(int i=0;i<10000;i++){int ms=GhostSimulationResponses.typingMillis(random);yes(ms>=2000&&ms<=7000,"typing duration");}
  for(int i=1;i<4;i++)yes(GhostSimulationResponses.PHRASES[i].length>=16&&GhostSimulationResponses.SHORT[i].length>=3,"response variety");
  System.out.println("PASS: virtual and wrapped peers permanently isolated; sends, uploads, typing, receipts and unknown writes denied; real reads allowed; 40/20/20/20 probabilities; response lengths and 2-7s typing");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
    temporary = Path(directory)
    (temporary / 'SimFixture.java').write_text(fixture, encoding='utf-8')
    (temporary / 'SimulationCheck.java').write_text(check, encoding='utf-8')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(temporary), str(policy), str(responses), str(temporary / 'SimFixture.java'), str(temporary / 'SimulationCheck.java')], check=True)
    subprocess.run(['java', '-cp', str(temporary), 'SimulationCheck'], check=True)
helper = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java').read_text(encoding='utf-8')
entry = helper[helper.index('public void sendMessage(SendMessageParams'):]
assert entry.index('GhostSimulation.capture') < entry.index('PluginBridge')
transport = (root / 'TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java').read_text(encoding='utf-8')
internal = transport[transport.index('private void sendRequestInternal'):]
assert internal.index('GhostSimulationPolicy.suppress') < internal.index('new NativeByteBuffer')
print('PASS: helper capture precedes plugin/upload pipeline; final transport guard precedes serialization')
simulation = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/GhostSimulation.java').read_text(encoding='utf-8')
assert '}, 5000);' in simulation and '}, 15000);' not in simulation
drafts = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java').read_text(encoding='utf-8')
assert drafts.count('if (GhostSimulationPolicy.virtual(dialogId)) return;') >= 2
print('PASS: five-second idle delay; virtual drafts never enter the ordinary draft store')
