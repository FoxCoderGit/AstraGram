package org.telegram.messenger;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.BiConsumer;

/** Export only a downloaded, indexed cloud document; never relax generic URI guards. */
public final class GhostCloudExport {
    private GhostCloudExport(){ }
    public static void save(File file,GhostCloudStore.Photo photo,BiConsumer<Uri,String> callback) {
        Utilities.globalQueue.postRunnable(()->{
            Uri destination=null;File legacy=null;boolean legacyCreated=false;String error=null;
            try {
                if(!file.isFile()||file.length()!=photo.size)throw new java.io.IOException("Оригинал загружен не полностью");
                if(photo.name==null||!new File(photo.name).getName().equals(photo.name)||photo.name.equals(".")||photo.name.equals(".."))throw new java.io.IOException("Некорректное имя файла");
                ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,photo.name);values.put(MediaStore.MediaColumns.MIME_TYPE,photo.mime);values.put(MediaStore.Images.Media.DATE_TAKEN,photo.taken);
                if(Build.VERSION.SDK_INT>=29) {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/AstraGram Cloud/");values.put(MediaStore.MediaColumns.IS_PENDING,1);
                    Uri collection=photo.mime!=null&&photo.mime.startsWith("video/")?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    destination=ApplicationLoader.applicationContext.getContentResolver().insert(collection,values);
                    if(destination==null)throw new java.io.IOException("Не удалось создать фотографию в галерее");
                    try(OutputStream output=ApplicationLoader.applicationContext.getContentResolver().openOutputStream(destination)) {
                        if(output==null)throw new java.io.IOException("Галерея недоступна");copy(file,output,photo.hash);
                    }
                    ContentValues complete=new ContentValues();complete.put(MediaStore.MediaColumns.IS_PENDING,0);ApplicationLoader.applicationContext.getContentResolver().update(destination,complete,null,null);
                } else {
                    File folder=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"AstraGram Cloud");
                    if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException("Папка галереи недоступна");
                    legacy=new File(folder,photo.name);
                    int collision=0;
                    while(!legacy.createNewFile()){legacy=new File(folder,(++collision)+"-"+photo.name);}
                    legacyCreated=true;
                    try(FileOutputStream output=new FileOutputStream(legacy)){copy(file,output,photo.hash);output.getFD().sync();}
                    legacy.setLastModified(photo.taken);
                    final File saved=legacy;
                    android.media.MediaScannerConnection.scanFile(ApplicationLoader.applicationContext,new String[]{saved.getAbsolutePath()},new String[]{photo.mime},(path,uri)->AndroidUtilities.runOnUIThread(()->callback.accept(uri,null)));
                    return;
                }
            }catch(Exception failure){error=failure.getMessage();if(destination!=null)try{ApplicationLoader.applicationContext.getContentResolver().delete(destination,null,null);}catch(Exception ignored){}if(legacyCreated)legacy.delete();}
            final Uri value=destination;final String failure=error;
            AndroidUtilities.runOnUIThread(()->callback.accept(value,failure));
        });
    }
    private static void copy(File source,OutputStream output,String hash)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(source)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){digest.update(buffer,0,count);output.write(buffer,0,count);}}
        StringBuilder value=new StringBuilder();for(byte b:digest.digest())value.append(String.format(Locale.ROOT,"%02x",b&255));
        if(!value.toString().equals(hash))throw new java.io.IOException("Проверка оригинала не прошла. Скачайте файл заново.");
    }
}
