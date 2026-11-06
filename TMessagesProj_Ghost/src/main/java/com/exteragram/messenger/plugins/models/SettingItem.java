package com.exteragram.messenger.plugins.models;

/* JADX INFO: loaded from: classes.dex */
public abstract class SettingItem {
    public String icon;
    public String type;

    protected SettingItem(String str, String str2) {
        this.type = str;
        this.icon = str2;
    }
}
