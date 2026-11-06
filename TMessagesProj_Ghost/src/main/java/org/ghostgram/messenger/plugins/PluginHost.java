package org.ghostgram.messenger.plugins;

import android.content.Context;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.PluginsController;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.telegram.messenger.FileLog;

public final class PluginHost {
    private static final Object lock = new Object();
    private static volatile boolean initialized;
    private static PyObject runtime;
    private static volatile boolean recoveryPending;
    private static String recoveryCulprit;

    private PluginHost() {
    }

    public static void initialize(Context context) {
        if (!org.telegram.messenger.GhostLicenseBridge.allowed()) return;
        if (!ExteraConfig.pluginsEngine) return;
        if (initialized) {
            return;
        }
        synchronized (lock) {
            if (initialized) {
                return;
            }
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(context.getApplicationContext()));
                }
                runtime = Python.getInstance().getModule("plugin_runtime");
                PluginsController controller = PluginsController.getInstance();
                boolean crashed = controller.preferences.getBoolean("had_crash", false);
                String culprit = controller.preferences.getString("crashed_plugin_id", null);
                controller.preferences.edit().remove("had_crash").remove("crashed_plugin_id").apply();
                ExteraConfig.setPluginsSafeMode(crashed && culprit == null);
                if (crashed && culprit != null) ExteraConfig.preferences.edit().putBoolean("plugin_enabled_" + culprit, false).apply();
                if(crashed){recoveryPending=true;recoveryCulprit=culprit;showRecoveryNotice();}
                runtime.callAttr("initialize", context.getApplicationContext());
                initialized = true;
                controller.python = Python.getInstance();
                controller.pluginManagerClass = controller.python.getModule("plugins_manager").get("PluginsManager");
                controller.isInitialized = true;
                controller.refresh();
                controller.loadPluginSettings();
                controller.checkDevServer();
            } catch (Throwable error) {
                FileLog.e("Plugin engine initialization failed", error);
            }
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void shutdown() {
        synchronized (lock) {
            if (initialized && runtime != null) try { runtime.callAttr("shutdown"); } catch (Throwable error) { FileLog.e(error); }
            initialized = false;
            runtime = null;
        }
    }

    public static void setEngineEnabled(boolean enabled) {
        ExteraConfig.setPluginsEngine(enabled);
        if (enabled) initialize(org.telegram.messenger.ApplicationLoader.applicationContext); else PluginsController.getInstance().shutdown();
    }
    public static void setDevMode(boolean enabled) { ExteraConfig.setPluginsDevMode(enabled); PluginsController.getInstance().checkDevServer(); }
    public static void setPinned(String id,boolean value) { PluginsController.setPluginPinned(id,value); }
    public static void clearSafeMode() { ExteraConfig.setPluginsSafeMode(false); PluginsController.getInstance().restart(); }
    public static String getEngineStateJson() {
        return "{\"enabled\":" + ExteraConfig.pluginsEngine + ",\"dev_mode\":" + ExteraConfig.pluginsDevMode + ",\"safe_mode\":" + ExteraConfig.pluginsSafeMode + "}";
    }
    public static void recordCrash(Throwable error) {
        if (!ExteraConfig.pluginsEngine) return;
        try {
            java.io.StringWriter text = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(text));
            String culprit = null;
            for (String id : PluginsController.getInstance().plugins.keySet()) if (text.toString().contains(id)) { culprit = id; break; }
            PluginsController.getInstance().preferences.edit().putBoolean("had_crash", true).putString("crashed_plugin_id", culprit).commit();
        } catch (Throwable ignored) { }
    }

    public static String getPluginsJson() {
        PyObject result = call("plugins_json");
        return result == null ? "[]" : result.toString();
    }

    public static boolean installFile(String path) {
        PyObject result = call("install_file", path);
        return result != null && result.toBoolean();
    }
    public static String validateFile(String path) {
        if(!initialized)return "{\"error\":\"Plugin engine is disabled\"}";
        synchronized(lock){try{return runtime.callAttr("validate_file",path).toString();}catch(Throwable error){org.json.JSONObject result=new org.json.JSONObject();try{result.put("error",error.getMessage());}catch(Exception ignored){}return result.toString();}}
    }

    public static String getLastInstallError() {
        PyObject result = call("last_install_error");
        return result == null ? "" : result.toString();
    }

    public static void setLastInstallError(String error) {
        call("set_last_install_error", error == null ? "" : error);
    }

    public static boolean setEnabled(String pluginId, boolean enabled) {
        PyObject result = call("set_enabled", pluginId, enabled);
        return result != null && result.toBoolean();
    }

    public static boolean removePlugin(String pluginId) {
        PyObject result = call("remove_plugin", pluginId);
        return result != null && result.toBoolean();
    }

    public static void reloadAll() {
        call("reload_all");
    }

    public static String getSettingsJson(String pluginId) {
        PyObject result = call("settings_json", pluginId);
        return result == null ? "[]" : result.toString();
    }
    public static String getSettingsPageJson(String id,String path) { PyObject result=call("settings_page_json",id,path);return result==null?"[]":result.toString(); }
    public static boolean setSettingPageJson(String id,String path,String key,String value) { PyObject result=call("set_setting_page_json",id,path,key,value);return result!=null&&result.toBoolean(); }
    public static boolean invokeSettingPage(String id,String path,int index,Object view) { PyObject result=call("invoke_setting_page",id,path,index,view);return result!=null&&result.toBoolean(); }

    public static String getSettingSubmenuJson(String pluginId, int index) {
        PyObject result = call("submenu_settings_json", pluginId, index);
        return result == null ? "[]" : result.toString();
    }

    public static boolean setSettingJson(String pluginId, String key, String valueJson) {
        PyObject result = call("set_setting_json", pluginId, key, valueJson);
        return result != null && result.toBoolean();
    }

    public static boolean invokeSetting(String pluginId, int index) {
        PyObject result = call("invoke_setting", pluginId, index);
        return result != null && result.toBoolean();
    }

    public static boolean invokeSubmenuSetting(String pluginId, int parentIndex, int index) {
        PyObject result = call("invoke_submenu_setting", pluginId, parentIndex, index);
        return result != null && result.toBoolean();
    }

    public static boolean setCustomReaction(String pluginId, int index, String reaction) {
        PyObject result = call("set_custom_reaction", pluginId, index, reaction);
        return result != null && result.toBoolean();
    }

    public static String getMenuItemsJson(int menuType) {
        PyObject result = call("menu_items_json", menuType);
        return result == null ? "[]" : result.toString();
    }
    public static String getMenuItemsJson(int menuType, Object context) {
        PyObject result = call("menu_items_json", menuType, context);
        return result == null ? "[]" : result.toString();
    }

    public static boolean invokeMenu(String pluginId, String itemId, Object context) {
        PyObject result = call("invoke_menu", pluginId, itemId, context);
        return result != null && result.toBoolean();
    }

    public static boolean beforeSendMessage(int account, Object params) {
        PyObject result = call("dispatch_send", account, params);
        return result != null && result.toBoolean();
    }

    public static android.view.View customSettingView(String pluginId, int parentIndex, int index, Context context, Object list, int account, int guid, Object resources, Object adapter) {
        PyObject result = call("custom_setting_view", pluginId, parentIndex, index, context, list, account, guid, resources, adapter);
        return result == null ? null : result.toJava(android.view.View.class);
    }

    public static Object transformSendMessage(int account, Object params) {
        return transform("transform_send", params, account, params);
    }

    public static Object transformRequest(int account, Object request) {
        return transform("transform_pre_request", request, account, request);
    }

    public static Object[] transformResponse(int account, Object request, Object response, Object error) {
        try {
            PyObject result = call("transform_post_request", account, request, response, error);
            if(result==null)return new Object[]{false,response,error};
            Object[] converted=result.toJava(Object[].class);
            if(converted.length<3 || converted[1]!=null&&!(converted[1] instanceof org.telegram.tgnet.TLObject)
                || converted[2]!=null&&!(converted[2] instanceof org.telegram.tgnet.TLRPC.TL_error))return new Object[]{false,response,error};
            return new Object[]{false,converted[1],converted[2]};
        } catch(Throwable failure){FileLog.e(failure);return new Object[]{false,response,error};}
    }

    public static Object transformUpdates(int account, Object updates) {
        return transform("transform_updates", updates, account, updates);
    }
    public static Object transformUpdate(int account, Object update) { return transform("transform_update", update, account, update); }

    private static Object transform(String method, Object original, Object... args) {
        if (!initialized || runtime == null || !ExteraConfig.pluginsEngine || ExteraConfig.pluginsSafeMode) return original;
        synchronized (lock) {
            try { PyObject result = runtime.callAttr(method, args); return result == null ? null : result.toJava(Object.class); }
            catch (Throwable error) { FileLog.e("Plugin transform failed: " + method, error); return original; }
        }
    }

    public static boolean beforeRequest(int account, Object request) {
        PyObject result = call("dispatch_pre_request", account, request);
        return result != null && result.toBoolean();
    }

    public static void afterRequest(int account, Object request, Object response, Object error) {
        call("dispatch_post_request", account, request, response, error);
    }

    public static void onUpdates(int account, Object updates) {
        call("dispatch_update", account, updates);
    }

    public static void onAppEvent(String event) {
        if(recoveryPending&&("RESUME".equals(event)||"app_resume".equals(event)))showRecoveryNotice();
        call("dispatch_app_event", event);
    }
    private static void showRecoveryNotice() {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{
            if(!recoveryPending)return;
            org.telegram.ui.ActionBar.BaseFragment fragment=org.telegram.ui.LaunchActivity.getSafeLastFragment();
            if(fragment==null||fragment.getParentActivity()==null)return;
            recoveryPending=false;
            fragment.showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity())
                .setTitle("Восстановление плагинов")
                .setMessage(recoveryCulprit==null?"После сбоя плагины запущены в безопасном режиме. Перехваты и пункты меню отключены.":"После сбоя отключён плагин «"+recoveryCulprit+"». Его можно проверить в разделе «Плагины».")
                .setPositiveButton("OK",null)
                .setNegativeButton("Выключить движок",(dialog,which)->org.telegram.messenger.Utilities.pluginsQueue.postRunnable(()->setEngineEnabled(false)))
                .setNeutralButton("Перезапустить",(dialog,which)->org.telegram.messenger.Utilities.pluginsQueue.postRunnable(PluginHost::clearSafeMode)).create());
        },800);
    }

    private static PyObject call(String method, Object... args) {
        if (!org.telegram.messenger.GhostLicenseBridge.allowed()) return null;
        PyObject local = runtime;
        if (!initialized || local == null) {
            return null;
        }
        synchronized (lock) {
            try {
                return local.callAttr(method, args);
            } catch (PyException error) {
                FileLog.e("Plugin engine call failed: " + method + "\n" + error.getMessage());
            } catch (Throwable error) {
                FileLog.e("Plugin engine call failed: " + method, error);
            }
        }
        return null;
    }
}
