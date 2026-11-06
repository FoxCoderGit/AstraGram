package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import java.util.HashMap;

/** Synthetic SQLite records only. Never reads the gallery or sends a Telegram request. */
public final class GhostCloudDiagnostics extends Instrumentation {
    private static final long OWNER=-9223372036854700001L,OTHER=OWNER+1;
    private static final String HASH="a".repeat(64);
    private static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private TLRPC.Message message(int id,String kind){
        TLRPC.TL_message m=new TLRPC.TL_message();m.id=id;m.date=100;m.out=true;m.flags=256;
        TLRPC.TL_peerChannel peer=new TLRPC.TL_peerChannel();peer.channel_id=12345;m.peer_id=peer;m.from_id=peer;
        m.message="fixture";m.attachPath="/fixture/original.jpg";m.random_id=123;
        m.params=new HashMap<>();m.params.put("ghost_cloud_hash",HASH);m.params.put("ghost_cloud_owner",Long.toString(OWNER));m.params.put("ghost_cloud_kind",kind);
        return m;
    }
    @Override public void onStart(){
        Bundle result=new Bundle();GhostCloudStore store=null,other=null;
        try {
            ApplicationLoader.postInitApplication();
            TLRPC.TL_channels_channelParticipants participants=new TLRPC.TL_channels_channelParticipants();participants.count=1;
            TLRPC.TL_channelParticipantCreator creator=new TLRPC.TL_channelParticipantCreator();creator.peer=new TLRPC.TL_peerUser();creator.peer.user_id=12345;creator.admin_rights=new TLRPC.TL_chatAdminRights();participants.participants.add(creator);
            org.telegram.tgnet.SerializedData encoded=new org.telegram.tgnet.SerializedData();participants.serializeToStream(encoded);
            org.telegram.tgnet.SerializedData input=new org.telegram.tgnet.SerializedData(encoded.toByteArray());
            TLRPC.channels_ChannelParticipants decodedPeople=TLRPC.channels_ChannelParticipants.TLdeserialize(input,input.readInt32(true),true);input.cleanup();encoded.cleanup();
            check(decodedPeople.participants.get(0).user_id==0&&GhostCloudParticipants.onlyOwner(decodedPeople,12345),"actual creator protocol uses peer.user_id");
            check(!GhostCloudParticipants.onlyOwner(decodedPeople,12346),"foreign participant rejected");
            participants.count=2;check(!GhostCloudParticipants.onlyOwner(participants,12345),"another participant in count rejected");
            participants.count=1;participants.participants.add(creator);check(!GhostCloudParticipants.onlyOwner(participants,12345),"extra participant in list rejected");
            participants.participants.clear();check(!GhostCloudParticipants.onlyOwner(participants,12345),"empty result not trusted");
            TLRPC.TL_channelParticipantSelf self=new TLRPC.TL_channelParticipantSelf();self.user_id=12345;participants.participants.add(self);check(GhostCloudParticipants.onlyOwner(participants,12345),"self user_id supported");
            store=new GhostCloudStore(OWNER);other=new GhostCloudStore(OTHER);
            GhostCloudStore.Config c=store.config();check(!c.enabled&&!c.mobile&&c.camera,"safe defaults");
            c.enabled=true;c.scheduled=true;c.days=1;c.start=23*60;c.end=5*60;c.folders.add("fixture");store.save(c);
            check(!other.config().enabled&&other.config().folders.isEmpty(),"owner isolation");
            GhostCloudStore.Photo p=new GhostCloudStore.Photo();p.hash=HASH;p.uri="content://fixture/1";p.name="fixture.jpg";p.mime="image/jpeg";p.size=100;p.taken=1000;
            store.enqueue(p);store.enqueue(p);check(store.photos().size()==1&&store.next()!=null,"content deduplication");
            store.seen(p.uri,"generation1",HASH);check(HASH.equals(store.seen(p.uri,"generation1"))&&store.seen(p.uri,"generation2")==null,"changed source rescanned");
            TLRPC.Message pending=message(-1,"photo");pending.send_state=1;store.pending(HASH,pending);
            store.close();store=new GhostCloudStore(OWNER);
            check(store.config().enabled&&store.config().start==1380,"settings survive reopening");
            check(GhostCloudStore.SENDING.equals(store.get(HASH).state)&&store.get(HASH).localId==-1,"queue survives reopening");
            TLRPC.Message decoded=store.decode(store.get(HASH).pending);
            check(decoded!=null&&decoded.id==-1&&HASH.equals(decoded.params.get("ghost_cloud_hash"))&&decoded.attachPath.equals(pending.attachPath),"outbox payload serialization");
            check(store.statistics()[0]==0,"pending is not a saved copy");
            store.error(HASH,"FLOOD_WAIT_120");check(store.next()==null&&store.get(HASH).retry>System.currentTimeMillis()+119000,"flood wait respected");
            long flood=store.preferences.getLong("flood",0);store.retryErrors();check(store.preferences.getLong("flood",0)==flood,"manual retry cannot remove server wait");
            JSONObject meta=new JSONObject().put("hash",HASH).put("owner",OWNER).put("target",20).put("name",p.name).put("mime",p.mime).put("size",p.size).put("taken",p.taken);
            TLRPC.Message saved=message(20,"photo");store.remote(saved,meta,false);
            check(GhostCloudStore.DONE.equals(store.get(HASH).state)&&store.get(HASH).pending==null&&store.next()==null,"confirmed original completes queue");
            TLRPC.Message tombstone=message(30,"delete");store.remote(tombstone,meta,true);store.remote(saved,meta,false);
            check(GhostCloudStore.DELETING.equals(store.get(HASH).state)&&store.get(HASH).id==20&&store.nextDeletion()!=null,"old original cannot override newer deletion");
            android.content.ContentValues gone=new android.content.ContentValues();gone.put("state",GhostCloudStore.DELETED);store.update(HASH,gone);store.remote(tombstone,meta,true);store.remote(saved,meta,false);store.enqueue(p);
            check(GhostCloudStore.DELETED.equals(store.get(HASH).state)&&store.photos().isEmpty()&&store.next()==null,"deleted hashes stay suppressed");
            // Fresh local index receives newest-to-oldest channel history after reinstall.
            other.remote(saved,meta,false);check(other.get(HASH)==null,"wrong owner metadata rejected");
            meta.put("owner",OTHER);other.remote(tombstone,meta,true);other.remote(saved,meta,false);
            check(other.get(HASH).id==20&&other.get(HASH).message!=null&&GhostCloudStore.DELETING.equals(other.get(HASH).state),"restore tombstone before older original");
            store.sourceError(p.uri,p.name,"fixture error");check(store.sourceErrors().contains("fixture error"),"source error visible");store.clearSourceError(p.uri);check(store.sourceErrors().isEmpty(),"source error recovery");
            HashMap<String,String> params=pending.params;
            long random=GhostCloud.randomId(0,params);check(random!=0&&random==GhostCloud.randomId(1,params),"stable retry identifier");
            params.put("ghost_cloud_owner",Long.toString(OTHER));check(random!=GhostCloud.randomId(0,params),"different owner identifier");params.put("ghost_cloud_kind","delete");check(random!=GhostCloud.randomId(0,params),"deletion identifier differs");
            GhostCloudMedia.Source source=new GhostCloudMedia.Source();source.camera=true;source.folder="camera";source.taken=10;source.added=20;
            c=new GhostCloudStore.Config();c.since=15;check(GhostCloudMedia.selected(source,c),"new uses added date");c.period=1;check(!GhostCloudMedia.selected(source,c),"since uses shooting date");c.period=2;check(GhostCloudMedia.selected(source,c),"whole gallery");c.camera=false;check(!GhostCloudMedia.selected(source,c),"unselected folder ignored");c.folders.add("camera");check(GhostCloudMedia.selected(source,c),"manual folder selection");
            check(!GhostCloud.uploadAllowed(new java.io.File(ApplicationLoader.applicationContext.getCacheDir(),"cloud_123/unrestored.original").getAbsolutePath()),"unsent upload blocked until owner restoration");
            source.video=true;c.video=false;check(!GhostCloudMedia.selected(source,c),"videos off by default");c.video=true;check(GhostCloudMedia.selected(source,c),"videos included only after opt-in");store.save(c);check(store.config().video,"video setting persists");
            java.io.File original=new java.io.File(getTargetContext().getCacheDir(),"ghost_cloud_original_fixture_"+System.nanoTime()+".jpg");
            TLRPC.TL_document doc=new TLRPC.TL_document();doc.id=System.nanoTime();doc.dc_id=4;doc.mime_type="image/jpeg";doc.file_reference=new byte[0];doc.date=100;doc.size=6;
            java.io.File replica=FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(doc,true);
            try{
                check(!original.exists()&&!replica.exists(),"fixture paths are new");
                try(java.io.FileOutputStream output=new java.io.FileOutputStream(original)){output.write(new byte[]{1,2,3,4,5,6});}
                try(java.io.FileOutputStream output=new java.io.FileOutputStream(replica)){output.write(new byte[]{1,2,3,4,5,6});}
                GhostCloudStore.Photo local=new GhostCloudStore.Photo();local.uri=android.net.Uri.fromFile(original).toString();local.hash=GhostCloudMedia.hash(local.uri);local.size=6;local.name="fixture.jpg";local.mime="image/jpeg";local.taken=100;store.enqueue(local);
                check(local.uri.equals(GhostCloudMedia.findOriginal(store,local)),"original is used in place");
                GhostCloudMedia.releaseReplicas(UserConfig.selectedAccount,store,local,doc);
                check(original.isFile()&&!replica.exists(),"only identical cache replica removed, original retained");
                try(java.io.FileOutputStream output=new java.io.FileOutputStream(replica)){output.write(new byte[]{6,5,4,3,2,1});}
                GhostCloudMedia.releaseReplicas(UserConfig.selectedAccount,store,local,doc);check(original.isFile()&&replica.isFile(),"different cache contents never removed");
            }finally{original.delete();replica.delete();}
            result.putString("cloud","PASS: SQLite reopen, owner isolation, content deduplication, pending serialization, confirmed-only completion, flood wait, deletion recovery, stable send IDs, folder/period filters, upload gate; no gallery reads or RPCs");
        }catch(Throwable error){result.putString("error",android.util.Log.getStackTraceString(error));}
        finally {
            if(store!=null)store.close();if(other!=null)other.close();
            for(long owner:new long[]{OWNER,OTHER}){getTargetContext().deleteDatabase("ghost_cloud_"+owner+".db");getTargetContext().getSharedPreferences("ghost_cloud_"+owner,0).edit().clear().commit();}
        }
        finish(result.containsKey("error")?0:-1,result);
    }
}
