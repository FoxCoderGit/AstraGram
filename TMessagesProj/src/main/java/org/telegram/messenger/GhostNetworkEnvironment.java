package org.telegram.messenger;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

public final class GhostNetworkEnvironment {
    public static boolean vpnActive(Context context) {
        try {
            ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            for(Network network:manager.getAllNetworks()) {
                NetworkCapabilities capabilities=manager.getNetworkCapabilities(network);
                if(capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return true;
            }
        } catch(Exception ignored) {}
        return false;
    }
    private GhostNetworkEnvironment() {}
}
