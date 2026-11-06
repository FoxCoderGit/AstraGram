package org.ghostgram.messenger.plugins;

import com.chaquo.python.PyObject;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

/** Bridges ExteraGram-compatible Python hook callbacks to AliuHook/LSPlant. */
public final class PluginHookBridge {
    private PluginHookBridge() {
    }

    public static XC_MethodHook.Unhook hook(Member method, PyObject callback) {
        return hook(method, callback, 50);
    }

    public static XC_MethodHook.Unhook hook(Member method, PyObject callback, int priority) {
        if (method == null || callback == null) {
            throw new IllegalArgumentException("method and callback are required");
        }
        XC_MethodHook hook = isReplacement(callback)
                ? new PythonMethodReplacement(callback, priority)
                : new PythonMethodHook(callback, priority);
        return XposedBridge.hookMethod(method, hook);
    }

    private static boolean isReplacement(PyObject callback) {
        try {
            return callback.callAttr("is_replacement").toBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isAvailable() {
        try {
            return XposedBridge.disableHiddenApiRestrictions();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class PythonMethodHook extends XC_MethodHook {
        private final PyObject callback;

        private PythonMethodHook(PyObject callback, int priority) {
            super(priority);
            this.callback = callback;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            call("before_hooked_method", param);
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            call("after_hooked_method", param);
        }

        private void call(String name, MethodHookParam param) {
            callback.callAttr(name, param);
        }
    }

    /** An XC_MethodReplacement skips the original method after the Python callback returns. */
    private static final class PythonMethodReplacement extends XC_MethodReplacement {
        private final PyObject callback;

        private PythonMethodReplacement(PyObject callback, int priority) {
            super(priority);
            this.callback = callback;
        }

        @Override
        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                PyObject result = callback.callAttr("replace_hooked_method", param);
                return result == null ? null : result.toJava(Object.class);
        }
    }

    public static final class HookHandle {
        private XC_MethodHook.Unhook unhook;

        private HookHandle(XC_MethodHook.Unhook unhook) {
            this.unhook = unhook;
        }

        public synchronized void unhook() {
            if (unhook != null) {
                unhook.unhook();
                unhook = null;
            }
        }
    }
}
