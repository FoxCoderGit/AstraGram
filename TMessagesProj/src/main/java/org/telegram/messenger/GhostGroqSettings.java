package org.telegram.messenger;

import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;

/** Telegram-native key, model and quota dialogs. No private audio is used for validation. */
public final class GhostGroqSettings {
    private static final DispatchQueue queue=new DispatchQueue("groqSettings");
    private GhostGroqSettings() { }
    public static void show(BaseFragment fragment,Runnable changed){
        if(fragment.getParentActivity()==null)return;
        int[] selected={Math.max(0,Arrays.asList(GhostGroq.MODELS).indexOf(GhostGroq.model()))};
        boolean[] direct={GhostGroq.direct()};AtomicBoolean closed=new AtomicBoolean();AlertDialog[] child={null};
        LinearLayout body=new LinearLayout(fragment.getParentActivity());body.setOrientation(LinearLayout.VERTICAL);
        TextView notice=new TextView(fragment.getParentActivity());notice.setText("Онлайн-расшифровка на русском: аудио будет передаваться Groq после нажатия кнопки расшифровки. Для кружков передаётся только звук. API-ключ хранится зашифрованным на этом устройстве.");
        notice.setTextSize(14);notice.setTextColor(Theme.getColor(Theme.key_dialogTextGray,fragment.getResourceProvider()));notice.setPadding(AndroidUtilities.dp(24),AndroidUtilities.dp(8),AndroidUtilities.dp(24),AndroidUtilities.dp(8));body.addView(notice);
        EditTextBoldCursor input=new EditTextBoldCursor(fragment.getParentActivity());input.setSingleLine(true);input.setTextSize(16);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack,fragment.getResourceProvider()));input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint,fragment.getResourceProvider()));
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        input.setHint(GhostGroq.configured()?"Новый API-ключ (можно оставить пустым)":"API-ключ Groq · gsk_…");
        LinearLayout.LayoutParams editLayout=new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(52));editLayout.setMargins(AndroidUtilities.dp(24),0,AndroidUtilities.dp(24),AndroidUtilities.dp(8));body.addView(input,editLayout);
        TextSettingsCell model=new TextSettingsCell(fragment.getParentActivity(),fragment.getResourceProvider());model.setTextAndValue("Модель",GhostGroq.MODEL_LABELS[selected[0]],true);body.addView(model);
        TextSettingsCell connection=new TextSettingsCell(fragment.getParentActivity(),fragment.getResourceProvider());connection.setTextAndValue("Подключение",direct[0]?"Системная сеть / VPN":"Как в Telegram",true);body.addView(connection);
        TextSettingsCell quota=new TextSettingsCell(fragment.getParentActivity(),fragment.getResourceProvider());quota.setTextAndValue("Доступная квота","Открыть",false);body.addView(quota);
        model.setOnClickListener(v->{child[0]=new AlertDialog.Builder(fragment.getParentActivity(),fragment.getResourceProvider()).setTitle("Модель Groq").setItems(GhostGroq.MODEL_LABELS,(d,which)->{selected[0]=which;model.setTextAndValue("Модель",GhostGroq.MODEL_LABELS[which],true);}).create();child[0].show();});
        connection.setOnClickListener(v->{child[0]=new AlertDialog.Builder(fragment.getParentActivity(),fragment.getResourceProvider()).setTitle("Подключение к Groq")
            .setItems(new String[]{"Как в Telegram · учитывать SOCKS5","Системная сеть / VPN"},(d,which)->{direct[0]=which==1;connection.setTextAndValue("Подключение",direct[0]?"Системная сеть / VPN":"Как в Telegram",true);}).create();child[0].show();});
        quota.setOnClickListener(v->{String typed=input.getText().toString().trim();showQuota(fragment,typed,GhostGroq.MODELS[selected[0]],direct[0],child,closed);});
        TextView error=new TextView(fragment.getParentActivity());error.setTextSize(14);error.setTextColor(Theme.getColor(Theme.key_text_RedRegular,fragment.getResourceProvider()));error.setPadding(AndroidUtilities.dp(24),0,AndroidUtilities.dp(24),AndroidUtilities.dp(8));body.addView(error);
        boolean existed=GhostGroq.configured();
        AlertDialog dialog=new AlertDialog.Builder(fragment.getParentActivity(),fragment.getResourceProvider()).setTitle("Groq · распознавание речи").setView(body)
            .setPositiveButton("Проверить и включить",null).setNegativeButton("Отмена",null)
            .setNeutralButton(existed?"Удалить ключ":"Получить ключ",(d,which)->{if(existed){GhostGroq.forget();changed.run();}else Browser.openUrl(fragment.getParentActivity(),"https://console.groq.com/keys");}).create();
        if(fragment.showDialog(dialog,d->{closed.set(true);if(child[0]!=null)child[0].dismiss();AndroidUtilities.hideKeyboard(input);})==null)return;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String typed=input.getText().toString().trim(),modelId=GhostGroq.MODELS[selected[0]];boolean useDirect=direct[0];
            if(typed.isEmpty()&&!GhostGroq.configured()){error.setText("Введите API-ключ Groq.");input.requestFocus();return;}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);input.setEnabled(false);model.setEnabled(false);connection.setEnabled(false);quota.setEnabled(false);error.setText("Проверка ключа и модели…");
            queue.postRunnable(()->{
                String problem=null;
                try{String key=typed.isEmpty()?GhostGroq.key():typed;GhostGroq.validate(key,modelId,useDirect);if(!closed.get())GhostGroq.save(key,modelId,useDirect);}
                catch(Exception e){problem=friendly(e);}
                final String failure=problem;
                AndroidUtilities.runOnUIThread(()->{
                    if(closed.get())return;
                    if(failure==null){GhostSpeechModels.choose(GhostSpeechModels.GROQ);dialog.dismiss();changed.run();}
                    else{error.setText(failure);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);input.setEnabled(true);model.setEnabled(true);connection.setEnabled(true);quota.setEnabled(true);}
                });
            });
        });
    }
    private static void showQuota(BaseFragment fragment,String typed,String model,boolean direct,AlertDialog[] child,AtomicBoolean closed){
        queue.postRunnable(()->{
            String key=null,message;
            try{key=typed.isEmpty()?GhostGroq.key():typed;message=GhostGroq.quotaText(key,model);}
            catch(Exception e){message=friendly(e);}
            final String credential=key,text=message;
            AndroidUtilities.runOnUIThread(()->{
                if(closed.get()||fragment.getParentActivity()==null)return;
                AlertDialog window=new AlertDialog.Builder(fragment.getParentActivity(),fragment.getResourceProvider()).setTitle("Доступная квота Groq").setMessage(text)
                    .setPositiveButton("Проверить",null).setNegativeButton("Закрыть",null)
                    .setNeutralButton("Кабинет Groq",(d,w)->Browser.openUrl(fragment.getParentActivity(),"https://console.groq.com/settings/limits")).create();
                child[0]=window;window.show();window.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(credential!=null);
                window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                    window.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);window.setMessage("Проверка квоты тестовой тишиной…");
                    queue.postRunnable(()->{
                        String error=null,result;
                        try{GhostGroq.probe(credential,model,direct);}catch(Exception e){error=friendly(e);}
                        try{result=GhostGroq.quotaText(credential,model);}catch(Exception e){result="Нет данных о квоте.";}
                        final String updated=(error==null?"":error+"\n\n")+result;
                        AndroidUtilities.runOnUIThread(()->{if(!window.isShowing())return;window.setMessage(updated);window.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);});
                    });
                });
            });
        });
    }
    static String friendly(Exception e){
        if(e instanceof java.net.SocketTimeoutException)return "Groq: истекло время ожидания. Проверьте подключение и повторите попытку.";
        if(e instanceof java.net.UnknownHostException||e instanceof java.net.ConnectException)return "Groq: нет соединения. Проверьте сеть, прокси или VPN.";
        if(e instanceof java.io.IOException&&e.getMessage()!=null&&e.getMessage().length()<500)return e.getMessage();
        return "Groq: не удалось выполнить запрос. Проверьте ключ и подключение.";
    }
}
