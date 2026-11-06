import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.net.Proxy;
import java.net.URL;

/** Public-endpoint checks using Java TLS APIs and the exact relay CA. No account data or credentials. */
public final class GhostYandexRelayTlsTest {
    private static HttpsURLConnection open(String path,SSLSocketFactory sockets)throws Exception{
        HttpsURLConnection connection=(HttpsURLConnection)new URL("https://77.232.40.63:8443"+path).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);
        if(sockets!=null)connection.setSSLSocketFactory(sockets);
        return connection;
    }
    public static void main(String[] args)throws Exception{
        KeyStore anchors=KeyStore.getInstance(KeyStore.getDefaultType());anchors.load(null);
        try(InputStream input=Files.newInputStream(Paths.get(args[0]))){anchors.setCertificateEntry("relay",CertificateFactory.getInstance("X.509").generateCertificate(input));}
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(anchors);
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,trust.getTrustManagers(),null);
        HttpsURLConnection health=open("/health",tls.getSocketFactory());
        try{if(health.getResponseCode()!=200)throw new AssertionError("Pinned HTTPS health failed");}finally{health.disconnect();}
        HttpsURLConnection untrusted=open("/health",null);boolean rejected=false;
        try{untrusted.getResponseCode();}catch(SSLHandshakeException expected){rejected=true;}finally{untrusted.disconnect();}
        if(!rejected)throw new AssertionError("Unknown relay CA was trusted by the default TLS configuration");
        HttpsURLConnection denied=open("/relay",tls.getSocketFactory());denied.setRequestProperty("X-Ghost-Upstream-Url","https://api.music.yandex.net/account/status");
        try{if(denied.getResponseCode()!=401||!"relay-auth".equals(denied.getHeaderField("X-Ghost-Music-Error")))throw new AssertionError("Relay is not closed to unpaired devices");}finally{denied.disconnect();}
        System.out.println("Relay public Java TLS: 3 checks passed.");
    }
}
