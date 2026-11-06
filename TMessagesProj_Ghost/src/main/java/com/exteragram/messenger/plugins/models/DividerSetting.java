package com.exteragram.messenger.plugins.models;

import com.exteragram.messenger.plugins.PluginsConstants;

/* JADX INFO: loaded from: classes.dex */
public class DividerSetting extends SettingItem {
    public String text;

    public DividerSetting(String str) {
        super(PluginsConstants.Settings.TYPE_DIVIDER, null);
        this.text = str;
    }
}
