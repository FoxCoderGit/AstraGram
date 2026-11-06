package org.telegram.messenger;
import java.util.*;
import org.telegram.tgnet.TLRPC;
public final class OnlinePeekRulesCheck {
 public static void main(String[] args)throws Exception{
  ArrayList<TLRPC.PrivacyRule> source=new ArrayList<>();TLRPC.TL_privacyValueDisallowChatParticipants chats=new TLRPC.TL_privacyValueDisallowChatParticipants();chats.chats.add(1234567890123L);chats.chats.add(42L);source.add(chats);source.add(new TLRPC.TL_privacyValueAllowCloseFriends());source.add(new TLRPC.TL_privacyValueAllowPremium());source.add(new TLRPC.TL_privacyValueDisallowBots());source.add(new TLRPC.TL_privacyValueAllowContacts());
  ArrayList<TLRPC.InputPrivacyRule> rules=GhostOnlinePeek.copyRules(0,source);
  if(rules.size()!=source.size()||!((TLRPC.TL_inputPrivacyValueDisallowChatParticipants)rules.get(0)).chats.equals(chats.chats))throw new AssertionError("Lost chat exceptions/order");
  TLRPC.TL_inputPrivacyValueAllowUsers users=new TLRPC.TL_inputPrivacyValueAllowUsers();TLRPC.TL_inputUser user=new TLRPC.TL_inputUser();user.user_id=9876543210123L;user.access_hash=1234567898765L;users.users.add(user);rules.add(0,users);
  TLRPC.TL_inputPrivacyValueDisallowUsers blocked=new TLRPC.TL_inputPrivacyValueDisallowUsers();blocked.users.add(user);rules.add(1,blocked);
  String saved=GhostOnlinePeek.encode(rules);ArrayList<TLRPC.InputPrivacyRule> restored=GhostOnlinePeek.decode(saved);
  if(!saved.equals(GhostOnlinePeek.encode(restored)))throw new AssertionError("Snapshot is not lossless");
  TLRPC.TL_inputUser restoredUser=(TLRPC.TL_inputUser)((TLRPC.TL_inputPrivacyValueAllowUsers)restored.get(0)).users.get(0);if(restoredUser.user_id!=user.user_id||restoredUser.access_hash!=user.access_hash)throw new AssertionError("Lost user/access hash");
  try{GhostOnlinePeek.decode("AAAAAA==");throw new AssertionError("Accepted empty backup");}catch(IllegalStateException expected){}
  System.out.println("PASS: privacy order, chat exceptions, allow/deny users with 64-bit IDs/access hashes, contacts, close friends, premium, bots, exact serialization round trip and invalid snapshot rejection. No Telegram RPC used.");
 }
}
