package com.exteragram.messenger.plugins.hooks;

import com.chaquo.python.PyObject;
import java.util.Map;
import java.util.UUID;
import org.telegram.messenger.ApplicationLoader;
import org.ghostgram.messenger.plugins.PluginMenuBridge;

public final class MenuItemRecord {
    public final String conditionString,iconName,itemId,menuType,pluginId,subtext,text;
    public final int iconResId,priority;
    public final PyObject onClickCallback;
    public MenuItemRecord(String pluginId,PyObject data) {
        this.pluginId=pluginId;menuType=value(data,"menu_type");text=value(data,"text");
        onClickCallback=data.callAttr("get","on_click");
        String id=value(data,"item_id");itemId=id==null||id.isEmpty()?UUID.randomUUID().toString():id;
        iconName=value(data,"icon");conditionString=value(data,"condition");subtext=value(data,"subtext");
        PyObject order=data.callAttr("get","priority");priority=order==null?0:order.toInt();
        int icon=0;try{if(iconName!=null)icon=ApplicationLoader.applicationContext.getResources().getIdentifier(iconName,"drawable",ApplicationLoader.applicationContext.getPackageName());}catch(Throwable ignored){}
        iconResId=icon;
        if(menuType==null||menuType.isEmpty()||text==null||text.isEmpty()||onClickCallback==null)throw new IllegalArgumentException("Missing menu type, text or callback");
    }
    private static String value(PyObject data,String key){PyObject value=data.callAttr("get",key);return value==null?null:value.toString();}
    public boolean checkCondition(Map<String,Object> context){return PluginMenuBridge.matches(conditionString,context);}
    @Override public boolean equals(Object value){return value instanceof MenuItemRecord&&itemId.equals(((MenuItemRecord)value).itemId)&&pluginId.equals(((MenuItemRecord)value).pluginId);}
    @Override public int hashCode(){return java.util.Objects.hash(itemId,pluginId);}
}
