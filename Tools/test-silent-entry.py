"""Host-only session/privacy policy checks; no device or Telegram traffic."""
import pathlib
import re
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
src = root / 'TMessagesProj/src/main/java'
policy = src / 'org/telegram/messenger/GhostModePolicy.java'
types = set(re.findall(r'instanceof TLRPC\.(\w+)', policy.read_text(encoding='utf-8')))
types.update(['TL_messages_sendMessage', 'TL_messages_getHistory'])
stubs = {
    'android/content/SharedPreferences.java': '''package android.content;
public class SharedPreferences {
 private final java.util.Map<String, Boolean> data = new java.util.HashMap<>();
 public boolean getBoolean(String k, boolean d) { return data.getOrDefault(k,d); }
 public SharedPreferences edit() { return this; }
 public SharedPreferences putBoolean(String k, boolean v) { data.put(k,v); return this; }
 public void apply() {}
}''',
    'android/content/Context.java': '''package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 private final java.util.Map<String,SharedPreferences> data=new java.util.HashMap<>();
 public SharedPreferences getSharedPreferences(String k,int mode) { return data.computeIfAbsent(k,x->new SharedPreferences()); }
}''',
    'org/telegram/messenger/ApplicationLoader.java': '''package org.telegram.messenger;
public class ApplicationLoader { public static android.content.Context applicationContext=new android.content.Context(); }''',
    'org/telegram/messenger/UserConfig.java': '''package org.telegram.messenger;
public class UserConfig {
 public static final int MAX_ACCOUNT_COUNT=6;
 public static final UserConfig[] users={new UserConfig(10),new UserConfig(11),new UserConfig(12)};
 public long id; public UserConfig(long i){id=i;}
 public static UserConfig getInstance(int a){return users[a];}
 public long getClientUserId(){return id;}
}''',
    'org/telegram/messenger/MessagesController.java': '''package org.telegram.messenger;
public class MessagesController {
 public static MessagesController getInstance(int a){return new MessagesController();}
 public void onSilentEntryChanged(){}
}''',
    'org/telegram/messenger/GhostMode.java': '''package org.telegram.messenger;
public class GhostMode { public static boolean ghost; public static boolean isEnabled(int a){return ghost;} }''',
    'org/telegram/messenger/GhostSimulation.java': '''package org.telegram.messenger;
public class GhostSimulation { public static boolean sim; public static boolean active(int a){return sim;} }''',
    'org/telegram/tgnet/TLObject.java': 'package org.telegram.tgnet; public class TLObject {}',
    'org/telegram/tgnet/TLRPC.java': 'package org.telegram.tgnet; public class TLRPC {' + ''.join(
        'public static class '+t+' extends TLObject {}' for t in sorted(types)) + '}',
    'org/telegram/tgnet/tl/TL_account.java': '''package org.telegram.tgnet.tl;
public class TL_account { public static class updateStatus extends org.telegram.tgnet.TLObject {public boolean offline;} }''',
    'org/telegram/tgnet/tl/TL_stories.java': '''package org.telegram.tgnet.tl;
public class TL_stories {
 public static class TL_stories_readStories extends org.telegram.tgnet.TLObject {}
 public static class TL_stories_incrementStoryViews extends org.telegram.tgnet.TLObject {}
}''',
    'SilentEntryTest.java': '''import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.*;
public class SilentEntryTest {
 static int count;
 static void check(boolean b,String m){count++;if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  check(!GhostSilentEntry.holding(0),"Fresh install default");
  GhostSilentEntry.setEnabled(0,true);
  check(GhostSilentEntry.holding(0),"Enable guard immediately");
  check(!GhostSilentEntry.holding(1),"Accounts isolated");
  TL_account.updateStatus s=new TL_account.updateStatus();
  check(GhostSilentEntry.suppress(0,s) && !s.offline,"Online blocked without rewriting last seen");
  s.offline=true; check(GhostSilentEntry.suppress(0,s),"Offline blocked too");
  check(GhostSilentEntry.suppress(0,new TLRPC.TL_messages_readHistory()),"Read blocked");
  check(GhostSilentEntry.suppress(0,new TLRPC.TL_messages_setTyping()),"Typing blocked");
  check(GhostSilentEntry.suppress(0,new TL_stories.TL_stories_readStories()),"Story read blocked");
  check(!GhostSilentEntry.suppress(0,new TLRPC.TL_messages_getHistory()),"Browsing allowed");
  check(!GhostSilentEntry.suppress(0,new TLRPC.TL_messages_sendMessage()),"Background uploads allowed");
  check(GhostSilentEntry.holding(0),"Background send did not unlock guard");
  GhostSimulation.sim=true; GhostSilentEntry.userAction(0);
  check(GhostSilentEntry.holding(0),"Simulation cannot unlock real account");
  GhostSimulation.sim=false; GhostSilentEntry.userAction(0);
  check(!GhostSilentEntry.holding(0),"Explicit action unlocks session");
  GhostMode.ghost=true;
  check(GhostSilentEntry.privateReading(0),"Full ghost still wins");
  GhostMode.ghost=false; GhostSilentEntry.enter(0);
  check(GhostSilentEntry.holding(0),"Foreground entry rearms");
  GhostSilentEntry.setEnabled(0,false);
  check(!GhostSilentEntry.holding(0),"Disable guard");
  GhostSilentEntry.setEnabled(0,true); UserConfig.getInstance(0).id=999;
  check(!GhostSilentEntry.enabled(0),"Preferences tied to account owner, not slot");
  System.out.println("Silent entry: "+count+" assertions passed (host doubles; no device).");
 }
}''',
}
with tempfile.TemporaryDirectory(prefix='ghost-silent-entry-') as temp:
    out = pathlib.Path(temp)
    files = []
    for name, content in stubs.items():
        path = out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        files.append(str(path))
    files += [str(policy), str(src / 'org/telegram/messenger/GhostSilentEntry.java')]
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', temp, *files], check=True)
    subprocess.run(['java', '-cp', temp, 'SilentEntryTest'], check=True)
