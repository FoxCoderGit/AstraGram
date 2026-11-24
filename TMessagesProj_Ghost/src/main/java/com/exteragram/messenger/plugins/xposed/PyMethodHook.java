package com.exteragram.messenger.plugins.xposed;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import de.robv.android.xposed.XC_MethodHook;
import java.util.ArrayList;
import org.telegram.messenger.FileLog;

public final class PyMethodHook extends XC_MethodHook {
    private final String pluginId;
    private final PyObject callback;
    private ArrayList<?> beforeFilters = new ArrayList<>();
    private ArrayList<?> afterFilters = new ArrayList<>();

    public PyMethodHook(String pluginId, PyObject callback) { this(pluginId, callback, 50); }

    public PyMethodHook(String pluginId, PyObject callback, int priority) {
        super(priority);
        this.pluginId = pluginId;
        this.callback = callback;
    }

    public void setBeforeHookedFilters(ArrayList<?> filters) { beforeFilters = filters; }
    public void setAfterHookedFilters(ArrayList<?> filters) { afterFilters = filters; }
    public ArrayList<?> getBeforeHookedFilters() { return beforeFilters; }
    public ArrayList<?> getAfterHookedFilters() { return afterFilters; }

    @Override protected void beforeHookedMethod(MethodHookParam param) {
        invoke(PluginsConstants.Xposed.BEFORE_HOOKED_METHOD, param, beforeFilters);
    }

    @Override protected void afterHookedMethod(MethodHookParam param) {
        invoke(PluginsConstants.Xposed.AFTER_HOOKED_METHOD, param, afterFilters);
    }

    private void invoke(String name, MethodHookParam param, ArrayList<?> filters) {
        if (!PluginsController.getInstance().isPluginActive(pluginId)) return;
        try {
            PyObject method = callback.get(name);
            if (method != null && (filters.isEmpty() || Python.getInstance().getModule("hook_utils")
                        .callAttr("matches_filters", filters, param).toBoolean())) {
                method.call(param);
            }
        } catch (RuntimeException error) {
            FileLog.e(error);
        }
    }
}
