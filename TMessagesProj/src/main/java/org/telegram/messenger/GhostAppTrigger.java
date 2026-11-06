package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.GhostTriggerActivity;

public final class GhostAppTrigger {
    public static boolean enabled(){return MessagesController.getGlobalMainSettings().getBoolean("ghost_trigger_enabled",false);}
    public static void setEnabled(boolean value){MessagesController.getGlobalMainSettings().edit().putBoolean("ghost_trigger_enabled",value).apply();}
    public static String selected(){return MessagesController.getGlobalMainSettings().getString("ghost_trigger_package","");}
    public static void select(String name){MessagesController.getGlobalMainSettings().edit().putString("ghost_trigger_package",name).apply();}
    public static void launch(BaseFragment fragment){
        if(selected().isEmpty()){fragment.presentFragment(new GhostTriggerActivity());return;}
        try{
            Context context=fragment.getParentActivity();
            Intent intent=context.getPackageManager().getLaunchIntentForPackage(selected());
            if(intent==null)throw new IllegalStateException("Application unavailable");
            context.startActivity(intent);
        }catch(Exception error){
            if(fragment.getParentActivity()!=null)fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(),fragment.getResourceProvider())
                    .setTitle("Приложение недоступно").setMessage("Выбранное приложение удалено или сейчас не может быть запущено.")
                    .setPositiveButton("Выбрать другое",(d,w)->fragment.presentFragment(new GhostTriggerActivity()))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel),null).create());
        }
    }
    private GhostAppTrigger(){}
}
