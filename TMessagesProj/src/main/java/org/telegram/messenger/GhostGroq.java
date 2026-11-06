package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;
import org.telegram.utils.proxy.ProxySettings;

/** Explicitly selected cloud ASR; credentials remain encrypted on this device. */
public final class GhostGroq {
    public static final String[] MODELS={"whisper-large-v3-turbo","whisper-large-v3"};
    public static final String[] MODEL_LABELS={"Whisper Large v3 Turbo","Whisper Large v3"};
    private static final String ROOT="https://api.groq.com/openai/v1/",ALIAS="ghostgram.groq.api-key";
    private GhostGroq() { }
    private static SharedPreferences prefs(){return ApplicationLoader.applicationContext.getSharedPreferences("ghost_groq",Context.MODE_PRIVATE);}
    public static boolean configured(){return !prefs().getString("key","").isEmpty();}
    public static String model(){String value=prefs().getString("model",MODELS[0]);return Arrays.asList(MODELS).contains(value)?value:MODELS[0];}
    public static boolean direct(){return prefs().getBoolean("direct",false);}
    private static SecretKey secret()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        SecretKey key=(SecretKey)store.getKey(ALIAS,null);if(key!=null)return key;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public static String key()throws Exception{
        String stored=prefs().getString("key","");if(stored.isEmpty())throw new IOException("Сначала укажите API-ключ Groq в настройках распознавания.");
        String[] parts=stored.split(":",-1);if(parts.length!=2)throw new IOException("Введите API-ключ Groq заново.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,secret(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    public static void save(String key,String model,boolean direct)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret());
        String encrypted=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs().edit().putString("key",encrypted).putString("model",model).putBoolean("direct",direct).commit())throw new IOException("Не удалось сохранить настройки Groq.");
    }
    public static void forget(){prefs().edit().clear().apply();if(GhostSpeechModels.choice()==GhostSpeechModels.GROQ)GhostSpeechModels.choose(GhostSpeechModels.TELEGRAM);}
    private static Proxy proxy(boolean direct)throws IOException{
        if(direct||!SharedConfig.isProxyEnabled())return Proxy.NO_PROXY;
        ProxySettings settings=SharedConfig.currentProxy.settings;
        if(settings.getType()!=ProxySettings.Type.SOCKS5||!settings.getUser().isEmpty())throw new IOException("Groq использует HTTPS. Для него нужен SOCKS5 без авторизации. Либо выберите в настройках Groq системную сеть / VPN.");
        return new Proxy(Proxy.Type.SOCKS,new InetSocketAddress(settings.getAddress(),settings.getPort()));
    }
    public static void validate(String key,String model,boolean direct)throws Exception{
        if(key==null||!key.matches("[A-Za-z0-9_\\-]{20,512}"))throw new IOException("Проверьте формат API-ключа Groq.");
        if(!Arrays.asList(MODELS).contains(model))throw new IOException("Выберите поддерживаемую модель Groq.");
        GhostGroqHttp.Response response=GhostGroqHttp.request(new URL(ROOT+"models/"+model),proxy(direct),key,model,null,null);
        requireSuccess(response);
        if(!model.equals(new JSONObject(response.body).optString("id")))throw new IOException("Groq не подтвердил доступность выбранной модели.");
    }
    public static String transcribe(File source)throws Exception{
        String key=key(),model=model();boolean direct=direct();checkWait(key,model);
        File audio=audioTrack(source);
        try{
            GhostGroqHttp.Response response=GhostGroqHttp.request(new URL(ROOT+"audio/transcriptions"),proxy(direct),key,model,audio,filename(audio));
            capture(key,model,response);requireSuccess(response);
            String text=new JSONObject(response.body).optString("text","").trim();return text.isEmpty()?"ERROR:NO_SPEECH":text;
        }finally{if(!audio.equals(source))audio.delete();}
    }
    public static void probe(String key,String model,boolean direct)throws Exception{
        checkWait(key,model);
        File test=File.createTempFile("groq-quota-",".wav",ApplicationLoader.applicationContext.getCacheDir());
        try{
            // 0.1 seconds of generated silence, never a recording or message from the device.
            ByteBuffer wav=ByteBuffer.allocate(44+3200).order(ByteOrder.LITTLE_ENDIAN);
            wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+3200).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(3200);
            try(FileOutputStream out=new FileOutputStream(test)){out.write(wav.array());}
            GhostGroqHttp.Response response=GhostGroqHttp.request(new URL(ROOT+"audio/transcriptions"),proxy(direct),key,model,test,"audio.wav");
            capture(key,model,response);requireSuccess(response);
        }finally{test.delete();}
    }
    private static void requireSuccess(GhostGroqHttp.Response r)throws IOException{
        if(r.status>=200&&r.status<300)return;
        if(r.status==401)throw new IOException("Groq: API-ключ недействителен или отозван.");
        if(r.status==403)throw new IOException("Groq: доступ запрещён. Проверьте доступность сервиса, регион и разрешения ключа.");
        if(r.status==429)throw new IOException("Groq: квота исчерпана. Остаток лимитов и время повторной попытки — в настройках Groq.");
        if(r.status==413)throw new IOException("Groq: аудиофайл слишком большой (максимум 25 МБ).");
        if(r.status==400||r.status==422)throw new IOException("Groq: не удалось обработать аудио. Проверьте файл и выбранную модель.");
        throw new IOException("Groq: ошибка сервиса HTTP "+r.status+". Повторите позже.");
    }
    private static String quotaKey(String key,String model)throws Exception{return "quota_"+GhostSpeechModels.hex(MessageDigest.getInstance("SHA-256").digest((key+"\n"+model).getBytes(StandardCharsets.UTF_8)));}
    private static void capture(String key,String model,GhostGroqHttp.Response r)throws Exception{
        if(r.status!=429&&(r.status<200||r.status>=300))return;
        long now=System.currentTimeMillis();JSONObject data=new JSONObject();data.put("at",now).put("status",r.status);
        for(String name:new String[]{"x-ratelimit-limit-requests","x-ratelimit-remaining-requests"}){
            String value=r.headers.get(name);if(value!=null&&value.matches("[0-9]{1,15}"))data.put(name,Long.parseLong(value));
        }
        long reset=duration(r.headers.get("x-ratelimit-reset-requests"));if(reset>0)data.put("reset",now+reset);
        String retry=r.headers.get("retry-after");if(retry!=null){try{double seconds=Double.parseDouble(retry);if(seconds>0&&seconds<=604800)data.put("retry",now+(long)(seconds*1000));}catch(NumberFormatException ignored){}}
        prefs().edit().putString(quotaKey(key,model),data.toString()).apply();
    }
    private static long duration(String value){
        if(value==null||value.length()>100)return 0;
        Matcher m=Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|d|h|m|s)").matcher(value);double total=0;int end=0;
        while(m.find()){if(m.start()!=end)return 0;double n=Double.parseDouble(m.group(1));String unit=m.group(2);total+=n*(unit.equals("d")?86400000:unit.equals("h")?3600000:unit.equals("m")?60000:unit.equals("s")?1000:1);end=m.end();}
        return end==value.length()&&total<=604800000?(long)total:0;
    }
    private static JSONObject quota(String key,String model)throws Exception{return new JSONObject(prefs().getString(quotaKey(key,model),"{}"));}
    private static void checkWait(String key,String model)throws Exception{
        long wait=quota(key,model).optLong("retry")-System.currentTimeMillis();
        if(wait>0)throw new IOException("Groq: повторите через "+(wait+999)/1000+" с. Сервер ограничил частоту запросов.");
    }
    public static String quotaText(String key,String model)throws Exception{
        JSONObject q=quota(key,model);long at=q.optLong("at"),now=System.currentTimeMillis();StringBuilder text=new StringBuilder(model+"\n\n");
        if(at==0)text.append("Данные о квоте ещё не получены. Они появятся после расшифровки или проверки ниже.");
        else{
            text.append("Данные сервера на ").append(date(at)).append("\n");
            if(q.has("x-ratelimit-remaining-requests"))text.append("Осталось запросов за сутки: ").append(q.getLong("x-ratelimit-remaining-requests")).append(q.has("x-ratelimit-limit-requests")?" / "+q.getLong("x-ratelimit-limit-requests"):"").append("\n");
            else text.append("Groq не передал остаток запросов.\n");
            if(q.has("reset"))text.append(q.getLong("reset")>now?"Сброс лимита: ":"Данные требуют обновления. Прошлое время сброса: ").append(date(q.getLong("reset"))).append("\n");
            if(q.optLong("retry")>now)text.append("Следующая попытка: ").append(date(q.getLong("retry"))).append("\n");
        }
        text.append("\nОстаток часов аудио и денежный баланс Groq не сообщает через этот API. Полные лимиты доступны в кабинете. Квота общая для организации, поэтому другие приложения тоже могут её расходовать.\n\n«Проверить» отправит 0,1 с тестовой тишины. Groq учитывает минимум 10 с аудио. Ваши сообщения не используются.");return text.toString();
    }
    private static String date(long value){return new SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(new Date(value));}
    private static String filename(File file)throws IOException{
        byte[] header=new byte[12];int n;try(InputStream in=new FileInputStream(file)){n=in.read(header);}
        String h=new String(header,0,Math.max(0,n),StandardCharsets.ISO_8859_1);
        if(h.startsWith("OggS"))return "audio.ogg";if(h.startsWith("RIFF"))return "audio.wav";if(h.startsWith("fLaC"))return "audio.flac";
        if(h.length()>=8&&h.substring(4,8).equals("ftyp"))return "audio.m4a";
        if(h.startsWith("ID3")||n>=2&&(header[0]&255)==255&&(header[1]&224)==224)return "audio.mp3";
        throw new IOException("Groq: формат аудио не поддерживается.");
    }
    private static File audioTrack(File source)throws Exception{
        String format=filename(source);if(!format.equals("audio.m4a"))return source;
        MediaExtractor extractor=new MediaExtractor();File result=null;MediaMuxer muxer=null;
        try{
            extractor.setDataSource(source.getAbsolutePath());int track=-1;MediaFormat audio=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat candidate=extractor.getTrackFormat(i);String mime=candidate.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;audio=candidate;break;}}
            if(track<0)throw new IOException("Groq: в видеосообщении нет аудиодорожки.");
            result=File.createTempFile("groq-audio-",".m4a",ApplicationLoader.applicationContext.getCacheDir());muxer=new MediaMuxer(result.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int output=muxer.addTrack(audio);muxer.start();extractor.selectTrack(track);
            int capacity=audio.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)?audio.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE):262144;
            ByteBuffer buffer=ByteBuffer.allocate(Math.max(262144,Math.min(capacity,2*1024*1024)));MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long total=0;
            while(true){buffer.clear();int count=extractor.readSampleData(buffer,0);if(count<0)break;total+=count;if(total>GhostGroqHttp.MAX_FILE_BYTES)throw new IOException("Groq: аудиодорожка больше 25 МБ.");info.set(0,count,extractor.getSampleTime(),extractor.getSampleFlags()&MediaCodec.BUFFER_FLAG_KEY_FRAME);muxer.writeSampleData(output,buffer,info);extractor.advance();}
            muxer.stop();muxer.release();muxer=null;File completed=result;result=null;return completed;
        }finally{extractor.release();if(muxer!=null)try{muxer.release();}catch(Exception ignored){}if(result!=null)result.delete();}
    }
}
