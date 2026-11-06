package org.telegram.messenger;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Bounded, resumable download. A model becomes active only after its pinned hash matches. */
public final class GhostModelDownload {
    private GhostModelDownload() { }
    public static void download(URL source, Proxy proxy, File stage, File target, long size, String sha,
                                BooleanSupplier canceled, LongConsumer progress) throws Exception {
        File parent=target.getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create model folder");
        if(stage.length()>size&&!stage.delete())throw new IOException("Cannot reset partial model");
        long offset=stage.length();
        if(offset<size){
            URL url=source;HttpURLConnection connection=null;
            try {
                for(int redirects=0;;redirects++){
                    check(canceled);
                    connection=(HttpURLConnection)url.openConnection(proxy);
                    if("93.88.204.2".equals(url.getHost())&&url.getPort()==9443)GhostLicenseBridge.configureLicenseConnection((javax.net.ssl.HttpsURLConnection)connection);
                    connection.setConnectTimeout(15000);connection.setReadTimeout(20000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("Accept-Encoding","identity");
                    if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
                    int status=connection.getResponseCode();
                    if(status==301||status==302||status==303||status==307||status==308){
                        String location=connection.getHeaderField("Location");
                        if(location==null||redirects>=5)throw new IOException("Invalid download redirect");
                        URL next=new URL(url,location);
                        if(!next.getProtocol().equals("https")&&!(source.getProtocol().equals("http")&&next.getProtocol().equals("http")))throw new IOException("Insecure model redirect");
                        connection.disconnect();connection=null;url=next;continue;
                    }
                    if(status==200)offset=0;
                    else if(status!=206||!validRange(connection.getHeaderField("Content-Range"),offset,size))throw new IOException("HTTP "+status);
                    long length=connection.getContentLengthLong();
                    if(length>=0&&length!=size-offset)throw new IOException("Unexpected model size");
                    check(canceled);progress.accept(offset);
                    try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(stage,offset>0)){
                        byte[] bytes=new byte[131072];long received=offset;int n;
                        while((n=input.read(bytes))!=-1){
                            check(canceled);
                            if(received+n>size)throw new IOException("Model exceeds expected size");
                            output.write(bytes,0,n);received+=n;progress.accept(received);
                        }
                        output.getFD().sync();
                        if(received!=size)throw new IOException("Incomplete model download");
                    }
                    break;
                }
            } finally {if(connection!=null)connection.disconnect();}
        }
        check(canceled);
        if(stage.length()!=size)throw new IOException("Incomplete model");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(stage)){
            byte[] bytes=new byte[131072];int n;
            while((n=input.read(bytes))!=-1){check(canceled);hash.update(bytes,0,n);}
        }
        StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(java.util.Locale.US,"%02x",b&255));
        if(!hex.toString().equals(sha)){stage.delete();throw new IOException("Model integrity check failed");}
        check(canceled);
        if(!stage.renameTo(target))throw new IOException("Cannot activate model");
    }
    private static void check(BooleanSupplier canceled)throws InterruptedIOException{if(canceled.getAsBoolean())throw new InterruptedIOException("Download canceled");}
    private static boolean validRange(String value,long offset,long size){
        if(value==null)return false;
        return value.equals("bytes "+offset+"-"+(size-1)+"/"+size);
    }
}
