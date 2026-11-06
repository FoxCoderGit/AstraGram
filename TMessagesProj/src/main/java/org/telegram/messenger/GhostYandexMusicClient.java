package org.telegram.messenger;

import android.media.MediaMetadataRetriever;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Network;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct account-authorized API transport, following YMMBFA's track/search model. */
public final class GhostYandexMusicClient {
    private static final String API="https://api.music.yandex.net";
    private static final long MAX_AUDIO=128L*1024*1024;
    private static final ExecutorService WORK=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"GhostYandexMusic");t.setDaemon(true);return t;});
    private static final ExecutorService COVERS=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"GhostYandexCovers");t.setDaemon(true);return t;});
    public interface Callback<T>{void done(T value,String error);}
    public interface Progress{void update(long bytes,long total);}
    private interface Task<T>{T run(Operation operation)throws Exception;}
    public static final class Operation {
        final AtomicBoolean cancelled=new AtomicBoolean();volatile HttpURLConnection connection;Future<?> future;Network network;
        boolean routeChosen;GhostYandexMusicRelay.Profile relayProfile;
        public void cancel(){cancelled.set(true);if(connection!=null)connection.disconnect();if(future!=null)future.cancel(true);}
        void check()throws Exception{if(cancelled.get()||Thread.currentThread().isInterrupted())throw new Exception("Отменено");}
    }
    public static final class Track {
        public final String id,title,artist,cover;public final long durationMs;public final boolean available;
        Track(JSONObject json)throws Exception{
            id=GhostYandexMusicProtocol.trackId(json.getString("id"));title=json.optString("title","Трек");
            JSONArray artists=json.optJSONArray("artists");StringBuilder names=new StringBuilder();
            if(artists!=null)for(int i=0;i<artists.length();i++){if(names.length()>0)names.append(", ");names.append(artists.getJSONObject(i).optString("name"));}
            artist=names.length()==0?"Неизвестный исполнитель":names.toString();durationMs=json.optLong("durationMs");available=json.optBoolean("available",true)||json.optBoolean("availableForPremiumUsers",false);
            String value=json.optString("coverUri","").replace("%%","200x200");
            if(!value.isEmpty()&&!value.startsWith("https://"))value="https://"+value.replaceFirst("^https?://","");
            cover=GhostYandexMusicProtocol.isYandexHttps(value)?value:null;
        }
    }
    public static final class Audio {public final Track track;public final File file;public final long durationMs;Audio(Track t,File f,long d){track=t;file=f;durationMs=d;}}
    public static final class LikedPage {
        public final ArrayList<Track> tracks;public final int nextOffset;public final boolean more;
        LikedPage(ArrayList<Track> tracks,int nextOffset,boolean more){this.tracks=tracks;this.nextOffset=nextOffset;this.more=more;}
    }
    private static final class Likes {
        final String access;final ArrayList<String> ids;
        final java.util.concurrent.ConcurrentHashMap<String,Track> tracks=new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Set<String> fetched=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        Likes(String access,ArrayList<String> ids){this.access=access;this.ids=ids;}
    }
    private volatile Likes likes;
    private volatile long likesEpoch;
    private final android.util.LruCache<String,Bitmap> covers=new android.util.LruCache<String,Bitmap>(3*1024*1024){
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private final int account;private final long owner;public final GhostYandexMusicSession session;
    public GhostYandexMusicClient(int account){this.account=account;owner=UserConfig.getInstance(account).getClientUserId();session=new GhostYandexMusicSession(owner);}
    public boolean authorized(){return owner==UserConfig.getInstance(account).getClientUserId()&&session.token()!=null;}
    private String token()throws Exception{if(owner!=UserConfig.getInstance(account).getClientUserId())throw new Exception("Аккаунт Telegram изменился");String token=session.token();if(token==null)throw new Exception("Войдите в Яндекс Музыку");return token;}
    private <T> Operation submit(Task<T> task,Callback<T> callback){
        return submit(WORK,task,callback);
    }
    private <T> Operation submit(ExecutorService executor,Task<T> task,Callback<T> callback){
        Operation operation=new Operation();operation.future=executor.submit(()->{T value=null;String error=null;try{operation.check();value=task.run(operation);}catch(Exception e){
            if(!operation.cancelled.get())android.util.Log.w("GhostYandexMusic","Request failed: "+e.getClass().getSimpleName());
            error=e instanceof MusicError||e instanceof GhostYandexMusicRelay.PairingError?e.getMessage():e instanceof java.io.IOException?(operation.relayProfile!=null?"Не удалось подключиться к RU-серверу. Проверьте интернет и доступность сервера.":"Не удалось подключиться напрямую. Проверьте интернет и разрешение обхода VPN или подключите RU-сервер."):"Не удалось связаться с Яндекс Музыкой. Повторите попытку.";
        }finally{if(operation.connection!=null){operation.connection.disconnect();operation.connection=null;}}
            final T result=value;final String problem=error;AndroidUtilities.runOnUIThread(()->{if(!operation.cancelled.get())callback.done(result,problem);});});return operation;
    }
    private static final class MusicError extends Exception{MusicError(String text){super(text);}}
    public Operation signIn(String token,Callback<String> callback){return submit(op->{
        JSONObject result=json(API+"/account/status",token,op).getJSONObject("result");JSONObject account=result.getJSONObject("account");
        if(account.optLong("uid")==0)throw new MusicError("Яндекс не подтвердил вход");
        String name=account.optString("displayName",account.optString("login","Яндекс"));
        op.check();if(owner!=UserConfig.getInstance(this.account).getClientUserId())throw new MusicError("Аккаунт Telegram изменился");
        session.save(token,name,account.optLong("uid"));invalidateLikes();return name;
    },callback);}
    static ArrayList<String> likedIds(JSONObject response)throws Exception {
        JSONArray entries=response.getJSONObject("result").getJSONObject("library").getJSONArray("tracks");
        java.util.LinkedHashMap<String,String> ids=new java.util.LinkedHashMap<>();
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.optJSONObject(i);if(entry==null)continue;
            try{String id=GhostYandexMusicProtocol.fullTrackId(entry.getString("id"),entry.optString("albumId",null));
                String key=GhostYandexMusicProtocol.trackId(id);if(!ids.containsKey(key))ids.put(key,id);
            }catch(Exception ignored){}
        }
        return new ArrayList<>(ids.values());
    }
    public synchronized void invalidateLikes(){likesEpoch++;likes=null;}
    public void clearCaches(){invalidateLikes();covers.evictAll();}
    public Operation liked(String query,int offset,Callback<LikedPage> callback){return submit(op->{
        String access=token();long epoch=likesEpoch;Likes library=likes;
        if(library==null||!library.access.equals(access)){
            long uid=session.uid();
            if(uid<=0){JSONObject account=json(API+"/account/status",access,op).getJSONObject("result").getJSONObject("account");uid=account.optLong("uid");
                if(uid<=0)throw new MusicError("Яндекс не подтвердил аккаунт");op.check();if(!session.updateUid(access,account.optString("displayName",account.optString("login","Яндекс")),uid))throw new MusicError("Аккаунт Яндекса изменился. Повторите запрос.");}
            library=new Likes(access,likedIds(json(API+"/users/"+uid+"/likes/tracks?if-modified-since-revision=0",access,op)));
            synchronized(this){op.check();if(epoch!=likesEpoch)throw new MusicError("Список обновлён. Повторите запрос.");likes=library;}
        }
        ArrayList<Track> result=new ArrayList<>();int cursor=Math.min(Math.max(0,offset),library.ids.size());
        while(cursor<library.ids.size()&&result.size()<50){
            op.check();int end=Math.min(cursor+50,library.ids.size());StringBuilder missing=new StringBuilder();
            for(int i=cursor;i<end;i++){String id=library.ids.get(i);if(!library.fetched.contains(id)){if(missing.length()>0)missing.append(',');missing.append(id);}}
            if(missing.length()>0){
                byte[] form=("track-ids="+URLEncoder.encode(missing.toString(),"UTF-8")+"&with-positions=true").getBytes(StandardCharsets.UTF_8);
                JSONArray metadata=new JSONObject(new String(bytes(API+"/tracks",access,op,4*1024*1024,form),StandardCharsets.UTF_8)).getJSONArray("result");
                for(int i=0;i<metadata.length();i++){try{Track track=new Track(metadata.getJSONObject(i));library.tracks.put(track.id,track);}catch(Exception ignored){}}
                for(int i=cursor;i<end;i++)library.fetched.add(library.ids.get(i));
            }
            for(int i=cursor;i<end&&result.size()<50;i++){
                Track track=library.tracks.get(GhostYandexMusicProtocol.trackId(library.ids.get(i)));cursor=i+1;
                if(track!=null&&GhostYandexMusicProtocol.matchesTrack(query,track.title,track.artist))result.add(track);
            }
        }
        op.check();return new LikedPage(result,cursor,cursor<library.ids.size());
    },callback);}
    public Operation cover(String url,Callback<Bitmap> callback){return submit(COVERS,op->{
        token();Bitmap cached=covers.get(url);if(cached!=null)return cached;
        byte[] data=bytes(url,null,op,2*1024*1024);BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);
        if(options.outWidth<=0||options.outHeight<=0||options.outWidth>4096||options.outHeight>4096)throw new MusicError("Неподдерживаемая обложка");
        options.inJustDecodeBounds=false;options.inSampleSize=1;while(options.outWidth/options.inSampleSize>256||options.outHeight/options.inSampleSize>256)options.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length,options);if(bitmap==null)throw new MusicError("Не удалось открыть обложку");op.check();covers.put(url,bitmap);return bitmap;
    },callback);}
    public Operation search(String query,int page,Callback<ArrayList<Track>> callback){return submit(op->{
        String access=token();JSONObject result=json(API+"/search?type=track&page="+Math.max(0,page)+"&text="+URLEncoder.encode(query,"UTF-8"),access,op).getJSONObject("result");
        JSONObject tracks=result.optJSONObject("tracks");JSONArray list=tracks==null?null:tracks.optJSONArray("results");ArrayList<Track> answer=new ArrayList<>();
        if(list!=null)for(int i=0;i<list.length();i++){try{answer.add(new Track(list.getJSONObject(i)));}catch(Exception ignored){}}
        return answer;
    },callback);}
    public Operation download(Track track,Progress progress,Callback<Audio> callback){return submit(op->{
        String access=token();if(!track.available)throw new MusicError("Этот трек недоступен для вашего аккаунта");
        File directory=new File(ApplicationLoader.applicationContext.getCacheDir(),"yandex_music/"+owner);
        if(!directory.isDirectory()&&!directory.mkdirs())throw new MusicError("Недостаточно места для загрузки");
        File file=new File(directory,track.id+".mp3");
        if(file.isFile()){long duration=duration(file);if(GhostYandexMusicProtocol.completeAudio(track.durationMs,duration))return new Audio(track,file,duration);file.delete();}
        JSONArray variants=json(API+"/tracks/"+track.id+"/download-info",access,op).getJSONArray("result");JSONObject choice=null;
        for(int i=0;i<variants.length();i++){JSONObject candidate=variants.getJSONObject(i);
            if(!"mp3".equalsIgnoreCase(candidate.optString("codec"))||candidate.optBoolean("preview",false))continue;
            if(choice==null||candidate.optInt("bitrateInKbps")>choice.optInt("bitrateInKbps"))choice=candidate;
        }
        if(choice==null)throw new MusicError("Яндекс не предоставил доступный MP3-файл. Проверьте доступ к треку и подписку.");
        String link=choice.optString("directLink","");
        if(link.isEmpty())link=mp3Url(bytes(choice.getString("downloadInfoUrl"),null,op,1024*1024));
        File temporary=File.createTempFile(track.id+"_",".part",directory);boolean complete=false;
        try{
            HttpURLConnection connection=open(link,null,op);long total=connection.getContentLengthLong();
            if(total>MAX_AUDIO){connection.disconnect();op.connection=null;throw new MusicError("Трек слишком большой для этой загрузки");}
            long copied=0,lastProgress=0;
            try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(temporary)){
                byte[] buffer=new byte[65536];int count;
                while((count=input.read(buffer))!=-1){op.check();copied+=count;if(copied>MAX_AUDIO)throw new MusicError("Трек слишком большой для этой загрузки");output.write(buffer,0,count);
                    long now=System.currentTimeMillis();if(now-lastProgress>250){lastProgress=now;long current=copied;AndroidUtilities.runOnUIThread(()->{if(!op.cancelled.get())progress.update(current,total);});}
                }
                output.getFD().sync();
            }finally{connection.disconnect();op.connection=null;}
            op.check();byte[] header=new byte[3];try(FileInputStream input=new FileInputStream(temporary)){if(input.read(header)!=3)throw new MusicError("Аудиофайл пуст");}
            if(!GhostYandexMusicProtocol.mp3Header(header))throw new MusicError("Яндекс вернул защищённый или неподдерживаемый формат вместо MP3");
            long duration=duration(temporary);
            if(!GhostYandexMusicProtocol.completeAudio(track.durationMs,duration))throw new MusicError("Яндекс вернул фрагмент или защищённый формат, а не полный аудиофайл");
            if(total>0&&copied!=total)throw new MusicError("Загрузка прервалась. Повторите попытку.");
            if(!temporary.renameTo(file))throw new MusicError("Не удалось сохранить трек");complete=true;return new Audio(track,file,duration);
        }finally{if(!complete)temporary.delete();}
    },callback);}
    private JSONObject json(String url,String access,Operation op)throws Exception{
        JSONObject result=new JSONObject(new String(bytes(url,access,op,4*1024*1024),StandardCharsets.UTF_8));
        if(result.has("error"))throw new MusicError("Яндекс отклонил запрос. Проверьте вход и доступ к Музыке.");return result;
    }
    private HttpURLConnection open(String value,String access,Operation op)throws Exception{
        return open(value,access,op,null);
    }
    private HttpURLConnection open(String value,String access,Operation op,byte[] form)throws Exception{
        boolean renewed=false;
        for(int redirects=0;redirects<5;redirects++){
            op.check();if(!GhostYandexMusicProtocol.isYandexHttps(value))throw new MusicError("Яндекс вернул неподдерживаемый адрес загрузки");
            URL url=new URL(value);if(form!=null&&!"api.music.yandex.net".equalsIgnoreCase(url.getHost()))throw new MusicError("Яндекс вернул неподдерживаемый адрес API");
            if(!op.routeChosen){op.relayProfile=GhostYandexMusicRelay.enabled()?GhostYandexMusicRelay.ensure(op):null;if(op.relayProfile==null)op.network=GhostYandexMusicNetwork.physicalNetwork(ApplicationLoader.applicationContext);op.routeChosen=true;}
            HttpURLConnection connection=op.relayProfile!=null?GhostYandexMusicRelay.open(url,op.relayProfile):GhostYandexMusicNetwork.open(op.network,url);op.connection=connection;
            connection.setConnectTimeout(20000);connection.setReadTimeout(25000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","AstraGram-YandexMusic/1.0");connection.setRequestProperty("Accept-Language","ru");
            if(access!=null&&"api.music.yandex.net".equalsIgnoreCase(url.getHost()))connection.setRequestProperty("Authorization","OAuth "+access);
            if(form!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");connection.setFixedLengthStreamingMode(form.length);
                try(java.io.OutputStream output=connection.getOutputStream()){output.write(form);}}
            int status=connection.getResponseCode();
            if(op.relayProfile!=null&&connection.getHeaderField("X-Ghost-Music-Error")!=null){String error=connection.getHeaderField("X-Ghost-Music-Error");connection.disconnect();
                if("relay-auth".equals(error)&&!renewed){renewed=true;GhostYandexMusicRelay.invalidate(op.relayProfile);op.relayProfile=GhostYandexMusicRelay.ensure(op);redirects--;continue;}
                throw new MusicError(GhostYandexMusicRelay.error(error));}
            if(status>=300&&status<400){String next=connection.getHeaderField("Location");connection.disconnect();value=new URL(url,next).toString();continue;}
            if(status==401){connection.disconnect();session.clearIfToken(access);throw new MusicError("Вход в Яндекс истёк. Войдите снова.");}
            if(status==403){connection.disconnect();throw new MusicError("Нет доступа к треку. Проверьте аккаунт и подписку Яндекс Плюс.");}
            if(status<200||status>=300){connection.disconnect();throw new MusicError("Яндекс Музыка временно недоступна ("+status+"). Попробуйте позже.");}
            return connection;
        }
        throw new MusicError("Не удалось открыть загрузку Яндекса");
    }
    private byte[] bytes(String url,String access,Operation op,int max)throws Exception{
        return bytes(url,access,op,max,null);
    }
    private byte[] bytes(String url,String access,Operation op,int max,byte[] form)throws Exception{
        HttpURLConnection connection=open(url,access,op,form);
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1){op.check();if(out.size()+count>max)throw new MusicError("Ответ Яндекса слишком большой");out.write(buffer,0,count);}return out.toByteArray();
        }finally{connection.disconnect();op.connection=null;}
    }
    private static String mp3Url(byte[] xml)throws Exception{
        // Android's bundled DOM parser does not implement Xerces security features.
        // Pull parsing keeps DTD/entity processing disabled and rejects declarations.
        XmlPullParser parser=android.util.Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(xml),"UTF-8");
        java.util.Map<String,String> fields=new java.util.HashMap<>();
        for(int event=parser.nextToken();event!=XmlPullParser.END_DOCUMENT;event=parser.nextToken()){
            if(event==XmlPullParser.DOCDECL||event==XmlPullParser.ENTITY_REF)throw new MusicError("Неподдерживаемый ответ Яндекса");
            if(event==XmlPullParser.START_TAG){String name=parser.getName();
                if("host".equals(name)||"path".equals(name)||"ts".equals(name)||"s".equals(name)){
                    if(fields.containsKey(name))throw new MusicError("Неподдерживаемый ответ Яндекса");
                    fields.put(name,parser.nextText());
                }
            }
        }
        if(fields.size()!=4)throw new MusicError("Неполный ответ Яндекса");
        String host=fields.get("host"),path=fields.get("path"),ts=fields.get("ts"),ticket=fields.get("s");
        if(!path.startsWith("/")||!GhostYandexMusicProtocol.downloadTimestamp(ts))throw new MusicError("Яндекс вернул неподдерживаемый формат");
        // Legacy unencrypted MP3 transport ticket, as used by yandex-music-api.
        // No encrypted downloads or DRM keys are processed by this client.
        byte[] digest=MessageDigest.getInstance("MD5").digest(("XGRlBW9FXlekgbPrRHuSiA"+path.substring(1)+ticket).getBytes(StandardCharsets.UTF_8));
        StringBuilder sign=new StringBuilder();for(byte b:digest)sign.append(String.format(Locale.US,"%02x",b&255));
        String result="https://"+host+"/get-mp3/"+sign+"/"+ts+path;
        if(!GhostYandexMusicProtocol.isYandexHttps(result))throw new MusicError("Яндекс вернул неподдерживаемый адрес");return result;
    }
    private static long duration(File file){MediaMetadataRetriever reader=new MediaMetadataRetriever();try{reader.setDataSource(file.getAbsolutePath());return Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));}catch(Exception ignored){return 0;}finally{try{reader.release();}catch(Exception ignored){}}}
    public MessageObject attachment(Audio audio){
        TLRPC.TL_message message=new TLRPC.TL_message();message.out=true;message.id=UserConfig.getInstance(account).getNewMessageId();UserConfig.getInstance(account).saveConfig(false);
        message.peer_id=new TLRPC.TL_peerUser();message.from_id=new TLRPC.TL_peerUser();message.peer_id.user_id=message.from_id.user_id=owner;
        message.date=(int)(System.currentTimeMillis()/1000);message.message="";message.attachPath=audio.file.getAbsolutePath();
        message.flags|=TLRPC.MESSAGE_FLAG_HAS_MEDIA|TLRPC.MESSAGE_FLAG_HAS_FROM_ID;message.media=new TLRPC.TL_messageMediaDocument();message.media.flags|=3;
        TLRPC.TL_document document=new TLRPC.TL_document();message.media.document=document;document.file_reference=new byte[0];document.date=message.date;document.mime_type="audio/mpeg";document.size=audio.file.length();
        TLRPC.TL_documentAttributeAudio metadata=new TLRPC.TL_documentAttributeAudio();metadata.duration=audio.durationMs/1000d;metadata.title=audio.track.title;metadata.performer=audio.track.artist;metadata.flags|=3;document.attributes.add(metadata);
        TLRPC.TL_documentAttributeFilename filename=new TLRPC.TL_documentAttributeFilename();filename.file_name=GhostYandexMusicProtocol.fileName(audio.track.artist,audio.track.title);document.attributes.add(filename);
        return new MessageObject(account,message,false,true);
    }
}
