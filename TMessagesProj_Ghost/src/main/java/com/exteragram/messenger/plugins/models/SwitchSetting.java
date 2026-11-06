package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;

/* JADX INFO: loaded from: classes.dex */
public class SwitchSetting extends SettingItem {
    public boolean defaultValue;
    public String key;
    public PyObject onChangeCallback;
    public String subtext;
    public String text;

    public SwitchSetting(String str, String str2, boolean z, String str3, String str4, PyObject pyObject) {
        super(PluginsConstants.Settings.TYPE_SWITCH, str4);
        this.key = str;
        this.text = str2;
        this.defaultValue = z;
        this.subtext = str3;
        this.onChangeCallback = pyObject;
    }
}
