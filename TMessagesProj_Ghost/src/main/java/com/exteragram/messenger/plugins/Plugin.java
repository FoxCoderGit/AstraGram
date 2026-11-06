package com.exteragram.messenger.plugins;

import android.text.TextUtils;
import com.exteragram.messenger.ExteraConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/* JADX INFO: loaded from: classes.dex */
public class Plugin {
    private String author;
    private String description;
    private String errorMessage;
    private final String id;
    private int index;
    private boolean isEnabled;
    private final String minVersion;
    private final String name;
    private String pack;
    private String version;

    public Plugin(String str, String str2, String str3, String str4, String str5, String str6, String str7) {
        this.pack = null;
        this.index = -1;
        this.errorMessage = null;
        this.id = str;
        this.name = str2;
        this.author = str3;
        this.description = str5;
        this.minVersion = str6;
        this.version = str7;
        setIcon(str4);
    }

    public Plugin(String str, String str2) {
        this.pack = null;
        this.index = -1;
        this.errorMessage = null;
        this.id = str;
        this.name = str2;
        this.version = "1.0";
        this.minVersion = "11.12.1";
        this.description = "";
        this.author = "";
        this.isEnabled = ExteraConfig.preferences.getBoolean("plugin_enabled_" + str, false);
    }

    private static boolean isIconValid(String str) {
        return str != null && str.matches("^[a-zA-Z][a-zA-Z0-9_]*/\\d+$");
    }

    public String getId() {
        return this.id;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(String str) {
        this.description = str;
    }

    public String getAuthor() {
        return this.author;
    }

    public void setAuthor(String str) {
        this.author = str;
    }

    public String getName() {
        return this.name;
    }

    public boolean isEnabled() {
        return !hasError() && this.isEnabled;
    }

    public void setEnabled(boolean z) {
        if (!hasError()) {
            this.isEnabled = z;
        } else {
            this.isEnabled = false;
        }
    }

    public String getErrorMessage() {
        return this.errorMessage;
    }

    public void setErrorMessage(String str) {
        this.errorMessage = str;
        if (hasError()) {
            this.isEnabled = false;
        }
    }

    public boolean hasError() {
        return !TextUtils.isEmpty(this.errorMessage);
    }

    public void setIcon(String str) {
        if (isIconValid(str)) {
            int iLastIndexOf = str.lastIndexOf(47);
            this.pack = str.substring(0, iLastIndexOf);
            this.index = Integer.parseInt(str.substring(iLastIndexOf + 1));
        }
    }

    public String getPack() {
        return this.pack;
    }

    public int getIndex() {
        return this.index;
    }

    public String getVersion() {
        return this.version;
    }

    public void setVersion(String str) {
        this.version = str;
    }

    public String getMinVersion() {
        return this.minVersion;
    }

    public boolean equals(Object obj) {
        if (obj instanceof Plugin) {
            return ((Plugin) obj).id.equals(this.id);
        }
        return super.equals(obj);
    }

    public int hashCode() {
        return this.id.hashCode();
    }
}
