package com.exteragram.messenger.plugins.xposed;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.InvocationTargetException;
import org.telegram.messenger.FileLog;

public final class PyMethodReplacement extends XC_MethodReplacement {
    private final String pluginId;
    private final PyObject callback;

    public PyMethodReplacement(String pluginId, PyObject callback) { this(pluginId, callback, 50); }

    public PyMethodReplacement(String pluginId, PyObject callback, int priority) {
        super(priority);
        this.pluginId = pluginId;
        this.callback = callback;
    }

    @Override protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
        if (PluginsController.getInstance().isPluginActive(pluginId)) {
            try {
                PyObject result = callback.callAttr(PluginsConstants.Xposed.REPLACE_HOOKED_METHOD, param);
                return result == null ? null : result.toJava(Object.class);
            } catch (RuntimeException error) {
                FileLog.e(error);
            }
        }
        try {
            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
        } catch (InvocationTargetException error) {
            throw error.getTargetException();
        }
    }
}
