package org.telegram.messenger;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

/** Uses Android's permitted physical-network routing only for Yandex Music. */
public final class GhostYandexMusicNetwork {
    public static final String AUTH_PROCESS = ":yandex_music_auth";
    public static final String NO_DIRECT_NETWORK = "Нет прямого подключения. Включите Wi-Fi или мобильный интернет. Если VPN запрещает обход, разрешите его в настройках VPN.";
    private GhostYandexMusicNetwork() { }

    public static boolean isAuthProcess(Context context) {
        String process=null;
        if(Build.VERSION.SDK_INT>=28) process=Application.getProcessName();
        else {
            ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<ActivityManager.RunningAppProcessInfo> processes=manager==null?null:manager.getRunningAppProcesses();
            if(processes!=null)for(ActivityManager.RunningAppProcessInfo info:processes)
                if(info.pid==android.os.Process.myPid()){process=info.processName;break;}
        }
        return (context.getPackageName()+AUTH_PROCESS).equals(process);
    }

    static int rank(NetworkCapabilities capabilities) {
        if(capabilities==null||capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ||!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ||!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ||!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))return -1;
        int rank=capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?30:
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?20:
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?10:-1;
        if(rank<0)return -1;
        return rank+(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?100:0);
    }

    public static Network physicalNetwork(Context context)throws IOException {
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(manager==null)throw new IOException(NO_DIRECT_NETWORK);
        try {
            Network best=null;int bestRank=-1;
            Network active=manager.getActiveNetwork();
            for(Network candidate:manager.getAllNetworks()){
                int value=rank(manager.getNetworkCapabilities(candidate));
                if(value>=0&&candidate.equals(active))value+=5;
                if(value>bestRank){best=candidate;bestRank=value;}
            }
            if(best!=null)return best;
        }catch(SecurityException error){throw new IOException(NO_DIRECT_NETWORK);}
        throw new IOException(NO_DIRECT_NETWORK);
    }

    public static HttpURLConnection open(Network network,URL url)throws IOException {
        if(network==null)throw new IOException(NO_DIRECT_NETWORK);
        try{return (HttpURLConnection)network.openConnection(url,Proxy.NO_PROXY);}
        catch(SecurityException error){throw new IOException(NO_DIRECT_NETWORK);}
    }

    /** Process binding is restricted to the dedicated OAuth process, never the messaging process. */
    public static void bindAuthProcess(Context context)throws IOException {
        if(!isAuthProcess(context))throw new IOException("Неверный процесс авторизации");
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        try{if(manager==null||!manager.bindProcessToNetwork(physicalNetwork(context)))throw new IOException(NO_DIRECT_NETWORK);}
        catch(SecurityException error){throw new IOException(NO_DIRECT_NETWORK);}
    }
    public static void defaultAuthNetwork(Context context)throws IOException {
        if(!isAuthProcess(context))throw new IOException("Неверный процесс авторизации");
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(manager==null||!manager.bindProcessToNetwork(null))throw new IOException("Не удалось открыть обычное подключение для Яндекс ID");
    }
}
