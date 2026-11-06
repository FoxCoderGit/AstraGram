"""Regression: execute production sync entry points with asynchronous transport doubles."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
source = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java').read_text(encoding='utf-8')
out = root / 'Tools/tests/ghost88'
out.mkdir(parents=True, exist_ok=True)

def method(signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

state = method('public void loadCurrentState()')
diff = method('public void getDifference(int pts, int date, int qts, boolean slice)')
diff = diff[:diff.index('registerForPush(')] + 'syncStarts++;}'
dialogs = method('public void loadDialogs(final int folderId, int offset, int count, boolean fromCache, Runnable onEmptyCallback)')
dialogs = dialogs[:dialogs.index('if (!fromCache && getUserConfig()')] + 'dialogStarts++;}'

harness = r'''
import java.util.*;
public class SessionSyncTest {
 int currentAccount, syncStarts, dialogStarts, checks;
 boolean updatingState;
 Map<Integer,Boolean> loadingDialogs=new HashMap<>();
 Transport transport=new Transport(); Storage storage=new Storage();
 Transport getConnectionsManager(){return transport;}
 Storage getMessagesStorage(){return storage;}
 void processUpdatesQueue(int a,int b){}
 void check(boolean b){if(!b)throw new AssertionError();checks++;}
 METHODS
 void run(){
   GhostAccountSessions.off=true;
   loadCurrentState();getDifference(0,0,0,false);
   loadingDialogs.put(0,true);loadDialogs(0,0,100,false,null);
   check(transport.requests==0&&!updatingState&&syncStarts==0);
   check(!loadingDialogs.get(0)&&dialogStarts==0);
   loadDialogs(0,0,100,true,null);check(dialogStarts==1);
   GhostAccountSessions.off=false;loadCurrentState();check(transport.requests==1&&updatingState);
   // Disconnect after enqueue but before the asynchronous ACCOUNT_DISABLED callback.
   GhostAccountSessions.off=true;transport.error(-1001);
   check(transport.requests==1&&!updatingState);
   GhostAccountSessions.off=false;loadCurrentState();check(transport.requests==2&&updatingState);
   transport.success();check(!updatingState&&storage.saved==1);
   getDifference(0,0,0,false);check(syncStarts==1);
   loadDialogs(0,0,100,false,null);check(dialogStarts==2);
   loadCurrentState();transport.error(401);check(transport.requests==3&&!updatingState);
   System.out.println("PASS: "+checks+" sync regression checks using production methods; no Android/network");
 }
 public static void main(String[] a){new SessionSyncTest().run();}
}
class GhostAccountSessions {static boolean off;static boolean disabled(int a){return off;}}
class TLRPC {
 static class TL_updates_getState{}
 static class TL_updates_state {int date=1,pts=2,seq=3,qts=4;}
 static class TL_error {int code;}
}
class Transport {
 interface Callback{void done(Object r,TLRPC.TL_error e);}
 int requests;Callback callback;
 void sendRequest(TLRPC.TL_updates_getState r,Callback c){requests++;callback=c;}
 void error(int code){Callback c=callback;callback=null;TLRPC.TL_error e=new TLRPC.TL_error();e.code=code;c.done(null,e);}
 void success(){Callback c=callback;callback=null;c.done(new TLRPC.TL_updates_state(),null);}
}
class Storage {
 int saved;
 void setLastDateValue(int v){}void setLastPtsValue(int v){}void setLastSeqValue(int v){}void setLastQtsValue(int v){}
 int getLastDateValue(){return 1;}int getLastPtsValue(){return 2;}int getLastSeqValue(){return 3;}int getLastQtsValue(){return 4;}
 void saveDiffParams(int a,int b,int c,int d){saved++;}
}
'''.replace('METHODS', state + '\n' + diff + '\n' + dialogs)
path = out / 'SessionSyncTest.java'
path.write_text(harness, encoding='utf-8')
java = Path('C:/Program Files/Android/Android Studio/jbr/bin')
subprocess.run([str(java/'javac.exe'), '-encoding', 'UTF-8', '-d', str(out), str(path)], check=True)
subprocess.run([str(java/'java.exe'), '-cp', str(out), 'SessionSyncTest'], check=True)
