package org.telegram.messenger;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.*;
public class GhostModeSettingsTest {
 static int count;
 static void check(boolean b,String message){count++;if(!b)throw new AssertionError(message);}
 public static void main(String[] args){
  check(GhostMode.count(0)==0,"fresh disabled");
  ApplicationLoader.applicationContext.getSharedPreferences("ghostmode_1",0).edit().putBoolean("enabled",true).apply();
  check(GhostMode.count(1)==5&&GhostMode.useSchedule(1),"legacy enabled migration");
  check(GhostMode.count(0)==0,"account isolation");
  GhostMode.toggleLock(1,GhostMode.ONLINE);GhostMode.setEnabled(1,false);
  check(GhostMode.count(1)==1&&GhostMode.hideOnline(1)&&!GhostMode.masterOn(1),"locked-on survives disable");
  GhostMode.setEnabled(1,true);check(GhostMode.count(1)==5&&GhostMode.masterOn(1),"master enables remaining");
  GhostMode.setOption(1,GhostMode.STORIES,false);GhostMode.toggleLock(1,GhostMode.STORIES);
  GhostMode.setEnabled(1,true);check(GhostMode.count(1)==4&&!GhostMode.useSchedule(1),"locked-off disables full schedule");
  check(!GhostMode.shouldSuppress(1,new TL_stories.TL_stories_readStories()),"story toggle applies");
  check(GhostMode.shouldSuppress(1,new TLRPC.TL_messages_readHistory()),"read suppression retained");
  GhostMode.toggleLock(1,GhostMode.STORIES);GhostMode.setEnabled(1,true);
  GhostMode.setReadActions(1,true);check(!GhostMode.useSchedule(1)&&GhostMode.readOnActions(1),"read action disables schedule");
  GhostMode.setSchedule(1,true);check(GhostMode.useSchedule(1)&&!GhostMode.readActionsSelected(1),"schedule disables read action");
  GhostMode.setOption(1,GhostMode.READ,false);check(!GhostMode.useSchedule(1)&&!GhostMode.shouldSuppress(1,new TLRPC.TL_messages_readHistory()),"read off is effective");
  TL_account.updateStatus online=new TL_account.updateStatus();check(GhostMode.shouldSuppress(1,online),"online still blocked");
  GhostMode.setOption(1,GhostMode.ONLINE,false);check(!GhostMode.shouldSuppress(1,online)&&!GhostMode.autoOffline(1),"online off effective");
  GhostMode.setOption(1,GhostMode.TYPING,false);check(!GhostMode.shouldSuppress(1,new TLRPC.TL_messages_setTyping()),"typing off effective");
  System.out.println("Ghost settings: "+count+" checks passed (memory preferences, no RPC).");
 }
}
