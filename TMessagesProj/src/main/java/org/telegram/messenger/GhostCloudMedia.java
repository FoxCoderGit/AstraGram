package org.telegram.messenger;

import android.Manifest;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

public final class GhostCloudMedia {
    public static final class Source {
        public String uri,name,mime,folder,title,path,revision;
        public long size,taken,added;
        public boolean camera,video;
    }
    private GhostCloudMedia(){ }
    public static boolean permission() {
        return ApplicationLoader.applicationContext.checkSelfPermission(Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }
    public static boolean permission(boolean video) {
        if(!permission())return false;
        return !video||Build.VERSION.SDK_INT<33||ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)==PackageManager.PERMISSION_GRANTED;
    }
    public static boolean originalPermission() {return Build.VERSION.SDK_INT<29 || ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    public static ArrayList<Source> query() {
        ArrayList<Source> result=new ArrayList<>();
        String pathColumn=Build.VERSION.SDK_INT>=29?MediaStore.MediaColumns.RELATIVE_PATH:MediaStore.MediaColumns.DATA;
        ArrayList<String> fields=new ArrayList<>(java.util.Arrays.asList(MediaStore.Images.Media._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.MIME_TYPE,MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,MediaStore.MediaColumns.DATE_ADDED,MediaStore.MediaColumns.DATE_MODIFIED,MediaStore.Images.Media.BUCKET_ID,MediaStore.Images.Media.BUCKET_DISPLAY_NAME,pathColumn));
        if(Build.VERSION.SDK_INT>=30)fields.add(MediaStore.MediaColumns.GENERATION_MODIFIED);
        String[] projection=fields.toArray(new String[0]);
        ArrayList<String> volumes=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=29)volumes.addAll(MediaStore.getExternalVolumeNames(ApplicationLoader.applicationContext));else volumes.add("external");
        for(String volume:volumes) for(boolean video:new boolean[]{false,true}) {
            if(video&&Build.VERSION.SDK_INT>=33&&ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)!=PackageManager.PERMISSION_GRANTED)continue;
            String version=Build.VERSION.SDK_INT>=30?MediaStore.getVersion(ApplicationLoader.applicationContext,volume):"";
            Uri base=video?(Build.VERSION.SDK_INT>=29?MediaStore.Video.Media.getContentUri(volume):MediaStore.Video.Media.EXTERNAL_CONTENT_URI):(Build.VERSION.SDK_INT>=29?MediaStore.Images.Media.getContentUri(volume):MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            try(Cursor c=ApplicationLoader.applicationContext.getContentResolver().query(base,projection,MediaStore.MediaColumns.SIZE+">0",null,null)) {
                if(c==null)continue;
                while(c.moveToNext()) {
                    Source s=new Source();s.uri=ContentUris.withAppendedId(base,c.getLong(0)).toString();s.name=c.getString(1);s.mime=c.getString(2);s.size=c.getLong(3);
                    s.added=c.getLong(5)*1000;s.taken=c.getLong(4);if(s.taken<=0)s.taken=s.added;
                    s.folder=volume+":"+c.getString(7);s.title=c.getString(8);s.path=c.getString(9);
                    s.video=base.toString().contains("video");s.camera=s.path!=null && ("/"+s.path.toLowerCase(Locale.ROOT)).contains("/dcim/camera/");
                    if(!s.camera && s.path==null)s.camera="camera".equalsIgnoreCase(s.title)||"Камера".equals(s.title);
                    s.revision=Build.VERSION.SDK_INT>=30?version+":"+c.getLong(10)+":"+c.getLong(6)+":"+s.size+":"+s.taken:null;
                    if(s.name!=null && s.mime!=null && s.mime.startsWith(video?"video/":"image/"))result.add(s);
                }
            }
        }
        return result;
    }
    public static boolean selected(Source source,GhostCloudStore.Config config) {
        if(source.video&&!config.video)return false;
        return (config.camera&&source.camera||config.folders.contains(source.folder)) && (config.period==2 || (config.period==0?source.added:source.taken)>=config.since);
    }
    public static LinkedHashMap<String,String> folders(ArrayList<Source> sources) {
        LinkedHashMap<String,String> result=new LinkedHashMap<>();
        for(Source s:sources)result.put(s.folder,s.title+" · "+(s.path==null?s.folder:s.path));return result;
    }
    public static InputStream open(String uri) throws Exception {
        Uri source=Uri.parse(uri);
        if(Build.VERSION.SDK_INT>=29)source=MediaStore.setRequireOriginal(source);
        InputStream input=ApplicationLoader.applicationContext.getContentResolver().openInputStream(source);
        if(input==null)throw new java.io.IOException("Фотография недоступна");return input;
    }
    public static String hash(String uri) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=open(uri)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
        return hex(digest.digest());
    }
    public static String findOriginal(GhostCloudStore store,GhostCloudStore.Photo photo){
        GhostCloudStore.Photo fresh=store.get(photo.hash);
        String preferred=fresh!=null?fresh.uri:photo.uri;
        // Reuse a verified hash only for the exact MediaStore generation. Avoid
        // rereading a large video on every swipe while still detecting later edits.
        if(preferred!=null&&Build.VERSION.SDK_INT>=30&&originalPermission())try{
            Uri uri=Uri.parse(preferred);
            if("content".equals(uri.getScheme())&&"media".equals(uri.getAuthority()))try(Cursor c=ApplicationLoader.applicationContext.getContentResolver().query(uri,new String[]{MediaStore.MediaColumns.SIZE,MediaStore.Images.Media.DATE_TAKEN,MediaStore.MediaColumns.DATE_ADDED,MediaStore.MediaColumns.DATE_MODIFIED,MediaStore.MediaColumns.GENERATION_MODIFIED},null,null,null)){
                if(c!=null&&c.moveToFirst()&&c.getLong(0)==photo.size){long taken=c.getLong(1);if(taken<=0)taken=c.getLong(2)*1000;
                    String revision=MediaStore.getVersion(ApplicationLoader.applicationContext,MediaStore.getVolumeName(uri))+":"+c.getLong(4)+":"+c.getLong(3)+":"+c.getLong(0)+":"+taken;
                    if(photo.hash.equals(store.seen(preferred,revision)))return preferred;
                }
            }
        }catch(Exception ignored){}
        try{if(preferred!=null&&hash(preferred).equals(photo.hash))return preferred;}catch(Exception ignored){}
        if(!permission())return null;
        try{for(Source source:query()){
            if(source.size!=photo.size||!source.mime.equals(photo.mime)||source.uri.equals(preferred))continue;
            try{
                String digest=source.revision==null?null:store.seen(source.uri,source.revision);
                if(digest==null){digest=hash(source.uri);if(source.revision!=null)store.seen(source.uri,source.revision,digest);}
                if(photo.hash.equals(digest)){android.content.ContentValues values=new android.content.ContentValues();values.put("uri",source.uri);store.update(photo.hash,values);return source.uri;}
            }catch(Exception ignored){}
        }}catch(Exception ignored){}
        return null;
    }
    /** Removes only byte-identical temporary replicas in Telegram's cache/media directories. */
    public static void releaseReplicas(int account,GhostCloudStore store,GhostCloudStore.Photo photo,org.telegram.tgnet.TLRPC.Document document){
        if(document==null||findOriginal(store,photo)==null)return;
        try{
            java.util.HashSet<String> candidates=new java.util.HashSet<>();
            if(photo.stage!=null)candidates.add(photo.stage);
            for(boolean cached:new boolean[]{false,true})candidates.add(FileLoader.getInstance(account).getPathToAttach(document,cached).getAbsolutePath());
            for(String path:candidates){File file=new File(path);if(!file.isFile()||file.length()!=photo.size)continue;
                String canonical=file.getCanonicalPath();boolean temporary=photo.stage!=null&&canonical.startsWith(new File(ApplicationLoader.applicationContext.getCacheDir(),"cloud_"+store.owner).getCanonicalPath()+File.separator);
                if(file.getName().equals(FileLoader.getAttachFileName(document)))for(int directory:new int[]{FileLoader.MEDIA_DIR_CACHE,FileLoader.MEDIA_DIR_DOCUMENT,FileLoader.MEDIA_DIR_VIDEO}){
                    File root=FileLoader.getDirectory(directory);if(root!=null&&canonical.startsWith(root.getCanonicalPath()+File.separator))temporary=true;
                }
                if(temporary&&hashFile(file).equals(photo.hash))file.delete();
            }
        }catch(Exception error){FileLog.e(error);}
    }
    public static File stage(GhostCloudStore store,GhostCloudStore.Photo photo) throws Exception {
        File folder=new File(ApplicationLoader.applicationContext.getCacheDir(),"cloud_"+store.owner);
        if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException("Не удалось создать папку загрузки");
        File output=new File(folder,photo.hash+".original"),partial=new File(folder,photo.hash+".partial");
        if(output.isFile() && output.length()==photo.size && hashFile(output).equals(photo.hash))return output;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=open(photo.uri);FileOutputStream out=new FileOutputStream(partial)) {
            byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){digest.update(buffer,0,n);out.write(buffer,0,n);}
            out.getFD().sync();
        } catch(Exception error){partial.delete();throw error;}
        if(partial.length()!=photo.size || !hex(digest.digest()).equals(photo.hash)){partial.delete();throw new java.io.IOException("Фотография изменилась. Повторите сканирование.");}
        if(output.exists()&&!output.delete())throw new java.io.IOException("Не удалось заменить временный файл");
        if(!partial.renameTo(output))throw new java.io.IOException("Не удалось подготовить оригинал");return output;
    }
    private static String hashFile(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new java.io.FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}return hex(digest.digest());
    }
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
}
