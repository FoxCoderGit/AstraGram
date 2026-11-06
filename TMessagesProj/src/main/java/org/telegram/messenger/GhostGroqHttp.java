package org.telegram.messenger;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Streaming Groq transport. Never redirects an API key or retries a paid request. */
public final class GhostGroqHttp {
    public static final long MAX_FILE_BYTES=25_000_000;
    public static final class Response {
        public final int status;
        public final String body;
        public final Map<String,String> headers;
        Response(int status,String body,Map<String,String> headers){this.status=status;this.body=body;this.headers=headers;}
    }
    private GhostGroqHttp() { }
    public static Response request(URL endpoint,Proxy proxy,String key,String model,File audio,String filename)throws IOException{
        if(key==null||!key.matches("[A-Za-z0-9_\\-]{20,512}"))throw new IOException("Invalid API key format");
        if(!model.equals("whisper-large-v3")&&!model.equals("whisper-large-v3-turbo"))throw new IOException("Unsupported Groq model");
        if(audio!=null&&(!audio.isFile()||audio.length()==0||audio.length()>MAX_FILE_BYTES))throw new IOException("Audio must be between 1 byte and 25 MB");
        if(audio!=null&&!Arrays.asList("audio.ogg","audio.wav","audio.m4a","audio.mp3","audio.flac","audio.webm").contains(filename))throw new IOException("Unsupported audio format");
        HttpURLConnection connection=(HttpURLConnection)endpoint.openConnection(proxy);
        try{
            connection.setConnectTimeout(15000);connection.setReadTimeout(60000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization","Bearer "+key);connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("Accept-Encoding","identity");
            if(audio!=null){
                String boundary="GhostGroq"+UUID.randomUUID().toString().replace("-","");
                String prefix=field(boundary,"model",model)+field(boundary,"language","ru")+field(boundary,"response_format","json")
                    +"--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: application/octet-stream\r\n\r\n";
                byte[] head=prefix.getBytes(StandardCharsets.UTF_8),tail=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII);
                connection.setRequestMethod("POST");connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
                long length=audio.length();connection.setFixedLengthStreamingMode(head.length+length+tail.length);
                try(OutputStream output=connection.getOutputStream();InputStream input=new FileInputStream(audio)){
                    output.write(head);byte[] bytes=new byte[65536];int n;long copied=0;
                    while((n=input.read(bytes))!=-1){if(copied+n>length)throw new IOException("Audio file changed during upload");output.write(bytes,0,n);copied+=n;}
                    if(copied!=length)throw new IOException("Audio file changed during upload");output.write(tail);
                }
            }
            int status=connection.getResponseCode();Map<String,String> headers=new HashMap<>();
            for(Map.Entry<String,List<String>> entry:connection.getHeaderFields().entrySet()){
                String name=entry.getKey();if(name==null||entry.getValue().isEmpty())continue;name=name.toLowerCase(Locale.ROOT);
                if(name.startsWith("x-ratelimit-")||name.equals("retry-after"))headers.put(name,entry.getValue().get(0));
            }
            // Error bodies may echo sensitive input. Status and rate headers are sufficient.
            if(status<200||status>=300)return new Response(status,"",headers);
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] bytes=new byte[8192];int n;
                while((n=input.read(bytes))!=-1){if(out.size()+n>1024*1024)throw new IOException("Groq response too large");out.write(bytes,0,n);}
                return new Response(status,out.toString("UTF-8"),headers);
            }
        }finally{connection.disconnect();}
    }
    private static String field(String boundary,String name,String value){return "--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n";}
}
