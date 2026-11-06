package com.exteragram.messenger.plugins.hooks;

import java.util.Objects;

/* JADX INFO: loaded from: classes.dex */
public class EventHookRecord implements HookRecord {
    private final String hookName;
    private final boolean matchSubstring;
    private final int priority;

    @Override // com.exteragram.messenger.plugins.hooks.HookRecord
    public void cleanup() {
    }

    public EventHookRecord(String str, boolean z, int i) {
        this.hookName = str;
        this.matchSubstring = z;
        this.priority = i;
    }

    public String getHookName() {
        return this.hookName;
    }

    public int getPriority() {
        return this.priority;
    }

    @Override // com.exteragram.messenger.plugins.hooks.HookRecord
    public boolean matches(Object obj) {
        if (obj instanceof String) {
            String str = (String) obj;
            if (this.matchSubstring) {
                String str2 = this.hookName;
                return (str2 == null || str2.isEmpty() || !str.contains(this.hookName)) ? false : true;
            }
            String str3 = this.hookName;
            if (str3 != null && str3.equals(str)) {
                return true;
            }
        }
        return false;
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj != null && getClass() == obj.getClass()) {
            EventHookRecord eventHookRecord = (EventHookRecord) obj;
            if (this.matchSubstring == eventHookRecord.matchSubstring && this.hookName.equals(eventHookRecord.hookName)) {
                return true;
            }
        }
        return false;
    }

    public int hashCode() {
        return Objects.hash(this.hookName, Boolean.valueOf(this.matchSubstring));
    }
}
