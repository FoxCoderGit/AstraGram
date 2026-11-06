package org.telegram.messenger;

import android.os.SystemClock;
import java.io.File;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.utils.proxy.ProxySettings;

/** The only network entry point is the explicit positive confirmation button. */
public final class GhostWhisperDownload {
    private static final String URL_MODEL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin";
    private static final DispatchQueue queue=new DispatchQueue("whisperModelDownload");
    private static boolean downloading;
    private GhostWhisperDownload() { }
    public static String[] labels(){
        String[] items=GhostSpeechModels.LABELS.clone();
        for(int engine:new int[]{GhostSpeechModels.VOSK,GhostSpeechModels.SMALL,GhostSpeechModels.TURBO})items[engine]+=GhostSpeechModels.available(engine)?" · установлена":" · скачать "+AndroidUtilities.formatFileSize(GhostSpeechModels.size(engine));
        return items;
    }
    public static void request(BaseFragment fragment,Runnable changed){
        request(fragment,GhostSpeechModels.TURBO,changed);
    }
    public static void offer(){
        BaseFragment fragment=org.telegram.ui.LaunchActivity.getLastFragment();
        if(fragment!=null)request(fragment,GhostSpeechModels.resolve(),()->{});
    }
    public static void request(BaseFragment fragment,int engine,Runnable changed){
        if(fragment.getParentActivity()==null)return;
        if(engine!=GhostSpeechModels.VOSK&&engine!=GhostSpeechModels.SMALL&&engine!=GhostSpeechModels.TURBO)return;
        if(GhostSpeechModels.available(engine)){GhostSpeechModels.choose(engine);changed.run();return;}
        if(downloading){AlertsCreator.showSimpleAlert(fragment,"Модель уже скачивается.");return;}
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(),fragment.getResourceProvider())
            .setTitle("Скачать "+GhostSpeechModels.LABELS[engine]+"?")
            .setMessage("Размер загрузки: "+AndroidUtilities.formatFileSize(GhostSpeechModels.size(engine))+".\n"+(engine==GhostSpeechModels.TURBO?"Эта модель требует много оперативной памяти.\n":"")+"Модель будет загружена через текущую сеть только после подтверждения. После установки распознавание работает локально.")
            .setPositiveButton("Скачать",(dialog,which)->begin(fragment,engine,changed))
            .setNeutralButton(engine==GhostSpeechModels.TURBO?"Выбрать файл":null,(dialog,which)->LocalWhisper.importModel(fragment.getParentActivity()))
            .setNegativeButton("Отмена",null).create());
    }
    private static void begin(BaseFragment fragment,int engine,Runnable changed){
        if(downloading||fragment.getParentActivity()==null)return;
        final Proxy proxy;
        if(SharedConfig.isProxyEnabled()){
            ProxySettings settings=SharedConfig.currentProxy.settings;
            if(settings.getType()!=ProxySettings.Type.SOCKS5||!settings.getUser().isEmpty()){
                AlertsCreator.showSimpleAlert(fragment,"Для скачивания модели нужен SOCKS5 без авторизации или VPN. MTProto-прокси не передаёт HTTPS-файлы. Также можно выбрать уже скачанный файл модели.");return;
            }
            proxy=new Proxy(Proxy.Type.SOCKS,new InetSocketAddress(settings.getAddress(),settings.getPort()));
        }else proxy=Proxy.NO_PROXY;
        downloading=true;
        AtomicBoolean canceled=new AtomicBoolean();
        AlertDialog progress=new AlertDialog(fragment.getParentActivity(),2,fragment.getResourceProvider());
        progress.setTitle(GhostSpeechModels.LABELS[engine]);progress.setMessage("Загрузка модели");progress.setProgress(0);
        progress.setButton(AlertDialog.BUTTON_NEGATIVE,"Отмена",(dialog,which)->canceled.set(true));
        if(fragment.showDialog(progress,dialog->canceled.set(true))==null){downloading=false;return;}
        queue.postRunnable(()->{
            String failure=null;
            try{
                File target=GhostSpeechModels.archive(engine);
                File stage=new File(target.getParentFile(),target.getName()+".download");
                long size=GhostSpeechModels.size(engine);long[] update={0};
                GhostModelDownload.download(new URL(GhostSpeechModels.downloadUrl(engine)),proxy,stage,target,size,GhostSpeechModels.hash(engine),canceled::get,received->{
                    long now=SystemClock.elapsedRealtime();if(now-update[0]<200&&received<size)return;update[0]=now;
                    AndroidUtilities.runOnUIThread(()->{if(canceled.get())return;progress.setProgress((int)(received*100/size));progress.setMessage(received==size?"Проверка модели…":AndroidUtilities.formatFileSize(received)+" / "+AndroidUtilities.formatFileSize(size));});
                });
                if(engine==GhostSpeechModels.VOSK)GhostSpeechModels.prepare(engine);
            }catch(Exception error){if(!canceled.get()){FileLog.e("Whisper model download failed",error);failure=error.getMessage();}}
            final String error=failure;
            AndroidUtilities.runOnUIThread(()->{
                downloading=false;boolean aborted=canceled.get();progress.dismiss();
                if(aborted)return;
                if(error!=null){if(fragment.getParentActivity()!=null)AlertsCreator.showSimpleAlert(fragment,"Не удалось скачать модель. Частичная загрузка сохранена; повторите выбор модели, чтобы продолжить.\n\n"+error);return;}
                GhostSpeechModels.choose(engine);changed.run();
            });
        });
    }
}
