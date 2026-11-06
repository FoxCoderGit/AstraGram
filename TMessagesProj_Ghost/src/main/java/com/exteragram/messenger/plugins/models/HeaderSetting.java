package com.exteragram.messenger.plugins.models;

import com.exteragram.messenger.plugins.PluginsConstants;

/* JADX INFO: loaded from: classes.dex */
public class HeaderSetting extends SettingItem {
    public String text;

    public HeaderSetting(String str) {
        super(PluginsConstants.Settings.TYPE_HEADER, null);
        this.text = str;
    }
}
