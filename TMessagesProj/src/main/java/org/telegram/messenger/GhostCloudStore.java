package org.telegram.messenger;

import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.HashSet;

/** One database and configuration per Telegram user ID, never per selected slot. */
public final class GhostCloudStore extends SQLiteOpenHelper {
    public static final String QUEUED="queued", SENDING="sending", DONE="done", ERROR="error", DELETED="deleted", DELETING="deleting";
    public final long owner;
    public final SharedPreferences preferences;
    public final java.util.concurrent.atomic.AtomicLong galleryRevision=new java.util.concurrent.atomic.AtomicLong();
    public static final class Config {
        public boolean enabled, paused, mobile, charging, scheduled, camera = true, video;
        public int days=127, start=0, end=0, period=0;
        public long since=System.currentTimeMillis();
        public HashSet<String> folders = new HashSet<>();
        JSONObject json() {
            JSONObject value = new JSONObject();
            try {
                value.put("enabled",enabled).put("paused",paused).put("mobile",mobile).put("charging",charging)
                    .put("scheduled",scheduled).put("camera",camera).put("video",video).put("days",days).put("start",start).put("end",end)
                    .put("period",period).put("since",since).put("folders",new JSONArray(folders));
            } catch (Exception error) { throw new IllegalStateException(error); }
            return value;
        }
        static Config parse(String data) {
            Config c=new Config();
            try {
                JSONObject v=new JSONObject(data);
                c.enabled=v.optBoolean("enabled");c.paused=v.optBoolean("paused");c.mobile=v.optBoolean("mobile");c.charging=v.optBoolean("charging");
                c.scheduled=v.optBoolean("scheduled");c.camera=v.optBoolean("camera",true);c.video=v.optBoolean("video",false);c.days=v.optInt("days",127);
                c.start=v.optInt("start");c.end=v.optInt("end");c.period=v.optInt("period");c.since=v.optLong("since",c.since);
                JSONArray f=v.optJSONArray("folders");if(f!=null)for(int i=0;i<f.length();i++)c.folders.add(f.getString(i));
            } catch(Exception ignored) { }
            return c;
        }
    }
    public static final class Photo {
        public String hash, uri, name, mime, state, stage, error;
        public long size,taken,retry;
        public int id,localId,attempts,event;
        public byte[] message,pending;
    }
    public GhostCloudStore(long owner) {
        super(ApplicationLoader.applicationContext,"ghost_cloud_"+owner+".db",null,2);
        this.owner=owner;
        preferences=ApplicationLoader.applicationContext.getSharedPreferences("ghost_cloud_"+owner,0);
        getWritableDatabase().enableWriteAheadLogging();
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE photos(hash TEXT PRIMARY KEY,uri TEXT,name TEXT,mime TEXT,size INTEGER,taken INTEGER,state TEXT,stage TEXT,error TEXT,server_id INTEGER DEFAULT 0,local_id INTEGER DEFAULT 0,attempts INTEGER DEFAULT 0,retry INTEGER DEFAULT 0,event INTEGER DEFAULT 0,message BLOB,pending BLOB)");
        db.execSQL("CREATE TABLE sources(source TEXT PRIMARY KEY,revision TEXT,hash TEXT)");
        db.execSQL("CREATE TABLE source_errors(source TEXT PRIMARY KEY,name TEXT,error TEXT)");
        db.execSQL("CREATE INDEX queue_state ON photos(state,retry)");
        db.execSQL("CREATE INDEX gallery_taken ON photos(taken,hash)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { if(oldVersion<2)db.execSQL("CREATE INDEX IF NOT EXISTS gallery_taken ON photos(taken,hash)"); }
    public Config config() { return Config.parse(preferences.getString("config","{}")); }
    public void save(Config config) { preferences.edit().putString("config",config.json().toString()).commit(); }
    public String seen(String source,String revision) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT hash FROM sources WHERE source=? AND revision=?",new String[]{source,revision})) { return c.moveToFirst()?c.getString(0):null; }
    }
    public void seen(String source,String revision,String hash) {
        ContentValues v=new ContentValues();v.put("source",source);v.put("revision",revision);v.put("hash",hash);
        getWritableDatabase().insertWithOnConflict("sources",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public void sourceError(String source,String name,String error) {
        ContentValues v=new ContentValues();v.put("source",source);v.put("name",name);v.put("error",error);
        getWritableDatabase().insertWithOnConflict("source_errors",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public void clearSourceError(String source) {getWritableDatabase().delete("source_errors","source=?",new String[]{source});}
    public long sourceErrorCount() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM source_errors",null)){return c.moveToFirst()?c.getLong(0):0;}
    }
    public String sourceErrors() {
        StringBuilder result=new StringBuilder();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT name,error FROM source_errors LIMIT 20",null)){while(c.moveToNext()){if(result.length()>0)result.append('\n');result.append(c.getString(0)).append(": ").append(c.getString(1));}}
        return result.toString();
    }
    public void enqueue(Photo photo) {
        ContentValues v=new ContentValues();v.put("hash",photo.hash);v.put("uri",photo.uri);v.put("name",photo.name);v.put("mime",photo.mime);
        v.put("size",photo.size);v.put("taken",photo.taken);v.put("state",QUEUED);
        getWritableDatabase().insertWithOnConflict("photos",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        galleryRevision.incrementAndGet();
    }
    public Photo get(String hash) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM photos WHERE hash=?",new String[]{hash})) { return c.moveToFirst()?read(c):null; }
    }
    public ArrayList<Photo> photos() {
        return photos(200);
    }
    public ArrayList<Photo> photos(int limit) {
        ArrayList<Photo> photos=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM photos WHERE state!='deleted' ORDER BY taken DESC,hash LIMIT ?",new String[]{Integer.toString(limit)})) {while(c.moveToNext())photos.add(read(c));}
        return photos;
    }
    /** Lightweight gallery index: no serialized messages, pending uploads or decoded images. */
    public ArrayList<Photo> gallery(int kind,int status,long from,long until,boolean oldest,String query){
        StringBuilder where=new StringBuilder("state!='deleted'");ArrayList<String> args=new ArrayList<>();
        if(kind==1)where.append(" AND mime LIKE 'image/%'");else if(kind==2)where.append(" AND mime LIKE 'video/%'");
        if(status==1)where.append(" AND state='done'");else if(status==2)where.append(" AND state IN ('queued','sending')");else if(status==3)where.append(" AND state='error'");
        if(from>0){where.append(" AND taken>=?");args.add(""+from);}if(until>0){where.append(" AND taken<?");args.add(""+until);}
        ArrayList<Photo> rows=new ArrayList<>();String search=query.trim().toLowerCase(java.util.Locale.ROOT);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT hash,uri,name,mime,size,taken,state,server_id,event FROM photos WHERE "+where+" ORDER BY taken "+(oldest?"ASC":"DESC")+",hash",args.toArray(new String[0]))){
            while(c.moveToNext()){
                Photo p=new Photo();p.hash=c.getString(0);p.uri=c.getString(1);p.name=c.getString(2);p.mime=c.getString(3);p.size=c.getLong(4);p.taken=c.getLong(5);p.state=c.getString(6);p.id=c.getInt(7);p.event=c.getInt(8);
                if(search.isEmpty()||p.name!=null&&p.name.toLowerCase(java.util.Locale.ROOT).contains(search))rows.add(p);
            }
        }return rows;
    }
    public long[] statistics() {
        long[] value=new long[4];
        try(Cursor c=getReadableDatabase().rawQuery("SELECT state,COUNT(*),COALESCE(SUM(size),0) FROM photos GROUP BY state",null)) {
            while(c.moveToNext()) {
                String state=c.getString(0);
                if(DONE.equals(state)){value[0]+=c.getLong(1);value[3]+=c.getLong(2);}
                else if(ERROR.equals(state))value[2]+=c.getLong(1);
                else if(!DELETED.equals(state))value[1]+=c.getLong(1);
            }
        }
        return value;
    }
    public Photo next() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM photos WHERE state IN ('queued','sending','error','deleting') AND retry<=? ORDER BY CASE WHEN state='sending' THEN 0 WHEN state='deleting' THEN 1 ELSE 2 END,taken LIMIT 1",new String[]{Long.toString(System.currentTimeMillis())})) {
            return c.moveToFirst()?read(c):null;
        }
    }
    public Photo nextDeletion() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM photos WHERE server_id>0 AND state IN ('deleting','error') AND retry<=? ORDER BY taken LIMIT 1",new String[]{Long.toString(System.currentTimeMillis())})){return c.moveToFirst()?read(c):null;}
    }
    public boolean hasDeletions() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM photos WHERE server_id>0 AND state IN ('deleting','error') LIMIT 1",null)){return c.moveToFirst();}
    }
    private Photo read(Cursor c) {
        Photo p=new Photo();p.hash=c.getString(0);p.uri=c.getString(1);p.name=c.getString(2);p.mime=c.getString(3);p.size=c.getLong(4);p.taken=c.getLong(5);
        p.state=c.getString(6);p.stage=c.getString(7);p.error=c.getString(8);p.id=c.getInt(9);p.localId=c.getInt(10);p.attempts=c.getInt(11);p.retry=c.getLong(12);p.event=c.getInt(13);p.message=c.getBlob(14);p.pending=c.getBlob(15);return p;
    }
    public void update(String hash,ContentValues values) { getWritableDatabase().update("photos",values,"hash=?",new String[]{hash});galleryRevision.incrementAndGet(); }
    public void pending(String hash,TLRPC.Message message) {
        Photo p=get(hash);if(p==null || DONE.equals(p.state)||DELETED.equals(p.state))return;
        ContentValues v=new ContentValues();v.put("local_id",message.id);v.put("pending",encode(message));
        v.put("state",message.params!=null && "delete".equals(message.params.get("ghost_cloud_kind"))?DELETING:SENDING);update(hash,v);
    }
    public void error(String hash,String error) {
        Photo p=get(hash);if(p==null || DONE.equals(p.state)||DELETED.equals(p.state))return;
        ContentValues v=new ContentValues();v.put("state",ERROR);v.put("error",error);v.put("attempts",p.attempts+1);
        long delay=GhostCloudPolicy.retryDelay(p.attempts,error);v.put("retry",p.attempts>=7?Long.MAX_VALUE:System.currentTimeMillis()+delay);update(hash,v);
        if(error!=null && (error.startsWith("FLOOD_")||error.startsWith("SLOWMODE_WAIT_")))preferences.edit().putLong("flood",System.currentTimeMillis()+delay).commit();
    }
    public void retryErrors() {
        getWritableDatabase().execSQL("UPDATE photos SET retry=0,attempts=0,state=CASE WHEN server_id>0 THEN 'deleting' ELSE 'queued' END WHERE state='error'");
        galleryRevision.incrementAndGet();
        // Server flood-wait is never cleared by the retry button.
    }
    public void remote(TLRPC.Message message,JSONObject meta,boolean deleted) throws Exception {
        if(meta.optLong("owner")!=owner||message.id<=0)return;
        String hash=meta.getString("hash"); if(!hash.matches("[0-9a-f]{64}"))return;
        Photo existing=get(hash);if(existing!=null && existing.event>message.id){
            if(!deleted&&existing.message==null&&existing.id==message.id){ContentValues old=new ContentValues();old.put("message",encode(message));update(hash,old);}return;
        }
        ContentValues v=new ContentValues();v.put("hash",hash);v.put("event",message.id);v.put("state",deleted?DELETING:DONE);
        v.put("error",(String)null);v.put("retry",0);v.put("local_id",0);v.putNull("pending");
        if(deleted) {
            v.put("server_id",meta.getInt("target"));v.put("pending",encode(message));
            v.put("name",meta.optString("name"));v.put("mime",meta.optString("mime"));v.put("size",meta.optLong("size"));v.put("taken",meta.optLong("taken"));
            if(existing!=null && DELETED.equals(existing.state))v.put("state",DELETED);
        } else {
            v.put("server_id",message.id);v.put("message",encode(message));v.put("name",meta.getString("name"));v.put("mime",meta.getString("mime"));
            v.put("size",meta.getLong("size"));v.put("taken",meta.getLong("taken"));
        }
        if(existing==null)getWritableDatabase().insertWithOnConflict("photos",null,v,SQLiteDatabase.CONFLICT_IGNORE);else update(hash,v);
        galleryRevision.incrementAndGet();
    }
    public static byte[] encode(TLRPC.Message message) {
        SerializedData data=new SerializedData();message.serializeToStream(data);byte[] bytes=data.toByteArray();data.cleanup();return bytes;
    }
    public TLRPC.Message decode(byte[] bytes) {
        if(bytes==null)return null;
        try {SerializedData data=new SerializedData(bytes);TLRPC.Message m=TLRPC.Message.TLdeserialize(data,data.readInt32(true),true);m.readAttachPath(data,owner);data.cleanup();return m;}
        catch(Exception error){FileLog.e(error);return null;}
    }
}
