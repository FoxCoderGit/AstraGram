package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.GhostYandexMusicNetwork;
import org.telegram.messenger.GhostYandexMusicProtocol;

/** Non-exported OAuth screen in its own network-bound process. No Telegram initialization. */
public final class GhostYandexMusicAuthActivity extends Activity {
    public static final String STATE="music_state", CALLBACK="music_callback", ERROR="music_error", USE_RELAY="music_relay";
    private static boolean suffixInitialized;
    private WebView web;
    private String state;
    private boolean completed;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        state=getIntent().getStringExtra(STATE);
        if(state==null||!state.matches("[A-Za-z0-9-]{16,100}")){finish();return;}
        try {
            if(getIntent().getBooleanExtra(USE_RELAY,false))GhostYandexMusicNetwork.defaultAuthNetwork(this);
            else GhostYandexMusicNetwork.bindAuthProcess(this);
            if(Build.VERSION.SDK_INT>=28&&!suffixInitialized){WebView.setDataDirectorySuffix("ghost_yandex_music_auth");suffixInitialized=true;}
            createLogin();
        }catch(Exception failure){fail(GhostYandexMusicNetwork.NO_DIRECT_NETWORK);}
    }

    @SuppressWarnings("SetJavaScriptEnabled") private void createLogin() {
        LinearLayout root=new LinearLayout(this);root.setFitsSystemWindows(true);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        TextView header=new TextView(this);header.setText(getIntent().getBooleanExtra(USE_RELAY,false)?"‹  Вход в Яндекс":"‹  Вход в Яндекс · без VPN");header.setTextSize(16);header.setTextColor(Color.BLACK);
        header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(16),0,dp(12),0);header.setOnClickListener(v->finish());root.addView(header,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView host=new TextView(this);host.setText("oauth.yandex.ru");host.setTextColor(0xff555555);host.setPadding(dp(16),0,dp(16),dp(8));root.addView(host,new LinearLayout.LayoutParams(-1,-2));
        web=new WebView(this);WebView.setWebContentsDebuggingEnabled(false);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);settings.setSaveFormData(false);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return request.isForMainFrame()&&navigate(request.getUrl().toString());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return navigate(url);}
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){if(!navigate(url)){String name=Uri.parse(url).getHost();host.setText(name==null?"Вход в Яндекс":name);}}
            @Override public void onPageFinished(WebView view,String url){navigate(url);}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,android.net.http.SslError error){handler.cancel();fail("Не удалось проверить защищённое соединение с Яндексом");}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())fail("Страница Яндекса недоступна через прямую сеть. Проверьте интернет и разрешение обхода VPN.");}
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1f));setContentView(root);
        web.loadUrl("https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d&force_confirm=yes&state="+state);
    }
    private boolean navigate(String url) {
        if(completed)return true;
        if(GhostYandexMusicProtocol.callbackToken(url,state)!=null){completed=true;setResult(RESULT_OK,new Intent().putExtra(CALLBACK,url));finish();return true;}
        Uri address=Uri.parse(url);String fragment=address.getFragment();
        if("music.yandex.ru".equalsIgnoreCase(address.getHost())&&fragment!=null&&(fragment.contains("access_token=")||fragment.contains("error="))){fail("Не удалось подтвердить вход. Попробуйте снова.");return true;}
        return !GhostYandexMusicProtocol.isYandexHttps(url);
    }
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private void fail(String message){if(completed)return;completed=true;setResult(RESULT_CANCELED,new Intent().putExtra(ERROR,message));finish();}
    @Override protected void onDestroy(){completed=true;if(web!=null){web.stopLoading();web.destroy();web=null;}super.onDestroy();}
}
