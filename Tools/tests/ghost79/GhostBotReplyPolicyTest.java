import org.telegram.messenger.GhostBotReplyPolicy;
import org.telegram.tgnet.TLRPC;
public final class GhostBotReplyPolicyTest {
 static int n;
 static void check(boolean b,String name){n++;if(!b)throw new AssertionError(name);}
 static TLRPC.TL_message message(long peer,int id,boolean out){
  TLRPC.TL_message m=new TLRPC.TL_message();m.peer_id=new TLRPC.TL_peerUser();m.peer_id.user_id=peer;
  m.from_id=new TLRPC.TL_peerUser();m.from_id.user_id=peer;m.id=id;m.out=out;return m;
 }
 public static void main(String[] args){
  long bot=444000,target=5000000001L;
  check(GhostBotReplyPolicy.fresh(message(bot,101,false),bot,100),"new reply");
  check(!GhostBotReplyPolicy.fresh(message(bot,100,false),bot,100),"baseline replay");
  check(!GhostBotReplyPolicy.fresh(message(bot,99,false),bot,100),"old reply");
  check(!GhostBotReplyPolicy.fresh(message(bot,101,true),bot,100),"own request ignored");
  check(!GhostBotReplyPolicy.fresh(message(bot+1,101,false),bot,100),"other dialogue ignored");
  TLRPC.TL_message m=message(bot,102,false);m.from_id.user_id=bot+1;
  check(!GhostBotReplyPolicy.fresh(m,bot,100),"other sender ignored");
  m.from_id=null;check(GhostBotReplyPolicy.fresh(m,bot,100),"implicit private sender");
  check(!GhostBotReplyPolicy.fresh(null,bot,100),"null reply");
  TLRPC.TL_user u=new TLRPC.TL_user();u.id=target;
  check(GhostBotReplyPolicy.matches(u,target),"64-bit identity accepted");
  check(!GhostBotReplyPolicy.matches(u,target+1),"renamed username mismatch rejected");
  u.min=true;check(!GhostBotReplyPolicy.matches(u,target),"partial user rejected");
  TLRPC.TL_userEmpty empty=new TLRPC.TL_userEmpty();empty.id=target;
  check(!GhostBotReplyPolicy.matches(empty,target),"empty profile rejected");
  check(!GhostBotReplyPolicy.matches(null,target),"missing profile rejected");
  TLRPC.TL_messageEntityMentionName mention=new TLRPC.TL_messageEntityMentionName();mention.user_id=target;m.entities.add(mention);
  check(GhostBotReplyPolicy.mentions(m,target),"target text mention");
  check(!GhostBotReplyPolicy.mentions(m,target+1),"other mention rejected");
  check(!GhostBotReplyPolicy.mentions(null,target),"null mention rejected");
  check(GhostBotReplyPolicy.profileLink("tg://user?id="+target,target),"profile URL accepted");
  check(GhostBotReplyPolicy.profileLink("tg://openmessage?user_id="+target+"&message_id=1",target),"profile URL with options");
  check(!GhostBotReplyPolicy.profileLink("tg://user?id="+target+"2",target),"ID prefix rejected");
  check(!GhostBotReplyPolicy.profileLink("https://example.org/"+target,target),"unrelated URL rejected");
  check(!GhostBotReplyPolicy.profileLink(null,target),"null URL rejected");
  System.out.println("Bot reply policy: "+n+" checks passed (real TL classes; no RPC).");
 }
}
