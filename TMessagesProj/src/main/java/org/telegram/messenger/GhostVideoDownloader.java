package org.telegram.messenger;

import android.content.Context;
import android.os.StatFs;
import android.media.MediaMetadataRetriever;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.ffmpeg.FFmpeg;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original yt-dlp, FFmpeg and QuickJS run locally; no download gateway or shared cookies. */
public final class GhostVideoDownloader {
    /*
     * Quality is limited by the short side, rather than encoded height.  The
     * former height-only selector excluded normal portrait TikTok clips (for
     * example 720x1280) before yt-dlp could download them.
     */
    public static final String FORMAT="bv[height>=360][height<=720][width<=1280]+ba/bv[width>=360][width<=720][height<=1280]+ba/b[height>=360][height<=720][width<=1280]/b[width>=360][width<=720][height<=1280]";
    private static final DispatchQueue queue=new DispatchQueue("ghostVideoDownload");
    private static final Set<String> active=Collections.synchronizedSet(new HashSet<>());
    public interface Listener { void progress(String status); void ready(File video); void failed(String message); }
    public static final class Job {
        private final String id=UUID.randomUUID().toString();
        private final AtomicBoolean cancelled=new AtomicBoolean();
        private volatile Process process;
        public void cancel(){cancelled.set(true);Process running=process;if(running!=null)running.destroy();}
    }
    public static void supports(String url,java.util.function.Consumer<Boolean> callback){
        Utilities.pluginsQueue.postRunnable(()->{
            boolean result=false;
            try{
                synchronized(Python.class){if(!Python.isStarted())Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));}
                result=Python.getInstance().getModule("ghost_video").callAttr("supported",url).toBoolean();
            }catch(Throwable error){FileLog.e(error);}
            boolean supported=result;AndroidUtilities.runOnUIThread(()->callback.accept(supported));
        });
    }
    private static synchronized void initialize() throws Exception {
        Context context=ApplicationLoader.applicationContext;
        YoutubeDL.getInstance().init(context);FFmpeg.getInstance().init(context);
        File bundled=new File(context.getNoBackupFilesDir(),"youtubedl-android/yt-dlp/yt-dlp");
        String hash="e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3";
        if(!hash.equals(MessagesController.getGlobalMainSettings().getString("ghost_ytdlp_asset",""))||!bundled.isFile()){
            File stage=new File(bundled.getParentFile(),"ghost-dlp.tmp");
            try(InputStream in=context.getResources().openRawResource(R.raw.ghost_ytdlp);OutputStream out=new FileOutputStream(stage)){
                byte[] bytes=new byte[65536];int count;while((count=in.read(bytes))!=-1)out.write(bytes,0,count);
            }
            if(!stage.renameTo(bundled))throw new IOException("Не удалось подготовить yt-dlp");
            MessagesController.getGlobalMainSettings().edit().putString("ghost_ytdlp_asset",hash).apply();
        }
    }
    private static YoutubeDLRequest request(String url){
        YoutubeDLRequest request=new YoutubeDLRequest(url);
        request.addOption("--no-playlist");request.addOption("--no-warnings");
        request.addOption("--socket-timeout",20);request.addOption("--retries",2);
        request.addOption("--no-check-formats");
        return request;
    }
    /** Chaquopy exports PYTHONPATH for Python 3.11. The external Python 3.12 must never inherit it. */
    private static String execute(YoutubeDLRequest request,Job job,java.util.function.Consumer<String> progress) throws Exception {
        Context context=ApplicationLoader.applicationContext;
        File nativeDir=new File(context.getApplicationInfo().nativeLibraryDir);
        File base=new File(context.getNoBackupFilesDir(),"youtubedl-android");
        File packages=new File(base,"packages");
        request.addOption("--ffmpeg-location",new File(nativeDir,"libffmpeg.so").getPath());
        request.addOption("--js-runtimes","quickjs:"+new File(nativeDir,"libqjs.so").getPath());
        request.addOption("--no-cache-dir");
        ArrayList<String> command=new ArrayList<>();
        command.add(new File(nativeDir,"libpython.so").getPath());
        command.add(new File(base,"yt-dlp/yt-dlp").getPath());
        command.addAll(request.buildCommand());
        ProcessBuilder builder=new ProcessBuilder(command);
        Map<String,String> environment=builder.environment();
        environment.keySet().removeIf(key->key.startsWith("PYTHON"));
        environment.put("PYTHONHOME",new File(packages,"python/usr").getPath());
        environment.put("PYTHONNOUSERSITE","1");
        environment.put("LD_LIBRARY_PATH",new File(packages,"python/usr/lib").getPath()+":"+new File(packages,"ffmpeg/usr/lib").getPath());
        environment.put("SSL_CERT_FILE",new File(packages,"python/usr/etc/tls/cert.pem").getPath());
        environment.put("HOME",new File(packages,"python/usr").getPath());
        environment.put("TMPDIR",context.getCacheDir().getPath());
        environment.put("PATH",environment.getOrDefault("PATH","/system/bin")+":"+nativeDir.getPath());
        if(job.cancelled.get())throw new InterruptedIOException("Cancelled");
        Process process=builder.start();job.process=process;
        if(job.cancelled.get())process.destroy();
        StringBuilder errors=new StringBuilder(),output=new StringBuilder();
        Thread errorReader=new Thread(()->{
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getErrorStream(),java.nio.charset.StandardCharsets.UTF_8))){
                String line;while((line=reader.readLine())!=null){if(errors.length()<65536)errors.append(line).append('\n');if(progress!=null)progress.accept(line);}
            }catch(IOException ignored){}
        },"ghostVideoStderr");errorReader.start();
        try{
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){
                String line;while((line=reader.readLine())!=null){if(progress!=null)progress.accept(line);else{if(output.length()>16*1024*1024)throw new IOException("Video metadata is too large");output.append(line).append('\n');}}
            }
            int result=process.waitFor();errorReader.join();
            if(job.cancelled.get())throw new InterruptedIOException("Cancelled");
            if(result!=0)throw new IOException(errors.length()==0?"yt-dlp exit "+result:errors.toString());
            return output.toString();
        }finally{process.destroy();job.process=null;}
    }
    public static Job download(String url,int account,Listener listener){
        Job job=new Job();
        if(!active.add(url)){AndroidUtilities.runOnUIThread(()->listener.failed("Эта ссылка уже загружается"));return job;}
        queue.postRunnable(()->{
            File directory=null;
            try{
                if(job.cancelled.get())return;
                publish(job,listener,"Подготовка yt-dlp…");initialize();
                if(job.cancelled.get())return;
                directory=new File(ApplicationLoader.applicationContext.getCacheDir(),"ghost-video/"+job.id);
                if(!directory.mkdirs())throw new IOException("Не удалось создать папку загрузки");
                if(new StatFs(directory.getPath()).getAvailableBytes()<128L*1024*1024)throw new IOException("Недостаточно свободного места для видео");
                MessagesController controller=MessagesController.getInstance(account);
                long limit=(long)(UserConfig.getInstance(account).isPremium()?controller.uploadMaxFilePartsPremium:controller.uploadMaxFileParts)*512*1024;
                long free=new StatFs(directory.getPath()).getAvailableBytes();
                long maximum=Math.min(limit,(free-64L*1024*1024)/3);
                String format=FORMAT;
                YoutubeDLRequest metadata=request(url);metadata.addOption("-f",format);metadata.addOption("-S","res:720,vcodec:h264,acodec:aac,ext:mp4:m4a");metadata.addOption("--dump-single-json");metadata.addOption("--skip-download");
                JSONObject info=new JSONObject(execute(metadata,job,null));
                if(job.cancelled.get())return;
                long size=info.optLong("filesize",info.optLong("filesize_approx",0));
                if(size>maximum)throw new IOException("Видео слишком большое для отправки или свободной памяти");
                String title=info.optString("title","Видео");
                YoutubeDLRequest download=request(url);download.addOption("-f",format);
                download.addOption("-S","res:720,vcodec:h264,acodec:aac,ext:mp4:m4a");
                download.addOption("--max-filesize",Long.toString(maximum));
                download.addOption("--merge-output-format","mp4");download.addOption("--recode-video","mp4");
                download.addOption("--postprocessor-args","VideoConvertor+ffmpeg_o:-c:v libx264 -c:a aac -movflags +faststart");
                download.addOption("--newline");download.addOption("--progress");download.addOption("--no-mtime");
                download.addOption("-o",new File(directory,"video.%(ext)s").getPath());
                download.addOption("--print","before_dl:Название: %(title)s");
                download.addOption("--no-simulate");
                final long[] lastUpdate={0};
                execute(download,job,line->{
                    if(job.cancelled.get())return;
                    long now=android.os.SystemClock.uptimeMillis();
                    if(now-lastUpdate[0]>180){lastUpdate[0]=now;String state=line==null?"":line;
                        if(state.contains("[Merger]")||state.contains("[VideoConvertor]"))state="Обработка и объединение видео…";
                        publish(job,listener,title+"\n"+state);
                    }
                });
                if(job.cancelled.get())return;
                File video=new File(directory,"video.mp4");
                if(!video.isFile()||video.length()==0)throw new IOException("Сервис не вернул совместимое MP4-видео");
                if(video.length()>limit)throw new IOException("Видео превышает ограничение Telegram");
                MediaMetadataRetriever retriever=new MediaMetadataRetriever();
                try{
                    retriever.setDataSource(video.getPath());
                    int width=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    int height=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    // Portrait videos use width as the short side (TikTok commonly returns 720x1280).
                    int shortSide=Math.min(width,height);
                    int longSide=Math.max(width,height);
                    if(shortSide<360||shortSide>720||longSide>1280)throw new IOException("Нет подходящего качества: короткая сторона должна быть от 360 до 720 px");
                }finally{retriever.release();}
                File ready=video;directory=null;
                AndroidUtilities.runOnUIThread(()->{if(!job.cancelled.get())listener.ready(ready);else deleteJob(ready.getParentFile());});
            }catch(Throwable error){
                if(!job.cancelled.get()){
                    FileLog.e("yt-dlp download failed",error);
                    String raw=error.getMessage()==null?"":error.getMessage();
                    String message=raw.contains("Requested format")?"Нет подходящего качества: короткая сторона должна быть от 360 до 720 px. Можно отправить обычную ссылку."
                            :raw.contains("Sign in")||raw.contains("login")||raw.contains("cookies")?"Сервис требует авторизацию. Можно отправить обычную ссылку."
                            :raw.contains("Недостаточно")||raw.contains("слишком")||raw.contains("превышает")?raw
                            :"Не удалось загрузить видео. Проверьте сеть и доступность ссылки; исходную ссылку можно отправить обычным способом.";
                    AndroidUtilities.runOnUIThread(()->listener.failed(message));
                }
            }finally{active.remove(url);if(directory!=null)deleteJob(directory);}
        });
        return job;
    }
    private static void publish(Job job,Listener listener,String text){AndroidUtilities.runOnUIThread(()->{if(!job.cancelled.get())listener.progress(text);});}
    private static void deleteJob(File directory){
        File root=new File(ApplicationLoader.applicationContext.getCacheDir(),"ghost-video");
        try{if(!directory.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))return;File[] files=directory.listFiles();if(files!=null)for(File file:files)if(file.isFile())file.delete();directory.delete();}catch(Exception e){FileLog.e(e);}
    }
    public static void discard(File video){queue.postRunnable(()->deleteJob(video.getParentFile()));}
    private GhostVideoDownloader(){}
}
