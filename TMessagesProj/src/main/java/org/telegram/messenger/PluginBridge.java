package org.telegram.messenger;

import android.net.Uri;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

public final class PluginBridge {
    private static final String HOST_CLASS = "org.ghostgram.messenger.plugins.PluginHost";
    private static volatile Class<?> hostClass;
    private static final java.util.WeakHashMap<org.telegram.ui.ActionBar.ActionBarMenuItem,MenuBinding> boundMenus=new java.util.WeakHashMap<>();
    private static final class MenuBinding {
        int type; java.util.Map<String,Object> context;
        final java.util.ArrayList<android.view.View> views=new java.util.ArrayList<>();
    }

    public static java.util.Map<String,Object> menuContext(org.telegram.ui.ActionBar.BaseFragment fragment,Object... values) {
        java.util.Map<String,Object> result=new java.util.HashMap<>();
        if(fragment!=null){result.put("fragment",fragment);result.put("account",fragment.getCurrentAccount());if(fragment.getParentActivity()!=null)result.put("context",fragment.getParentActivity());}
        for(int i=0;i+1<values.length;i+=2)if(values[i]!=null&&values[i+1]!=null){String key=String.valueOf(values[i]);Object value=values[i+1];result.put(key,value);if(value instanceof TLRPC.User&&"user".equals(key))result.put("userId",((TLRPC.User)value).id);if(value instanceof TLRPC.Chat&&"chat".equals(key))result.put("chatId",((TLRPC.Chat)value).id);}
        return result;
    }
    public static int menuIcon(String name) {
        if(name==null||name.isEmpty())return 0;
        try{return ApplicationLoader.applicationContext.getResources().getIdentifier(name,"drawable",ApplicationLoader.applicationContext.getPackageName());}catch(Throwable ignored){return 0;}
    }
    public static void bindActionMenu(org.telegram.ui.ActionBar.ActionBarMenuItem menu,int type,java.util.Map<String,Object> context) {
        if(menu==null)return;
        MenuBinding binding=boundMenus.get(menu);if(binding==null){binding=new MenuBinding();boundMenus.put(menu,binding);}
        for(android.view.View view:binding.views)if(view.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)view.getParent()).removeView(view);
        binding.views.clear();binding.type=type;binding.context=context;
        try{org.json.JSONArray items=new org.json.JSONArray(getMenuItemsJson(type,context));for(int i=0;i<items.length();i++){
            org.json.JSONObject item=items.getJSONObject(i);String plugin=item.getString("plugin_id"),id=item.getString("item_id");
            org.telegram.ui.ActionBar.ActionBarMenuSubItem row=menu.addSubItem(59000+i,menuIcon(item.optString("icon")),item.getString("text"));
            row.setOnClickListener(view->{menu.closeSubMenu();invokeMenu(plugin,id,context);});binding.views.add(row);
        }}catch(Throwable error){FileLog.e(error);}
    }
    public static void refreshBoundMenus() {
        java.util.ArrayList<org.telegram.ui.ActionBar.ActionBarMenuItem> menus=new java.util.ArrayList<>(boundMenus.keySet());
        for(org.telegram.ui.ActionBar.ActionBarMenuItem menu:menus){MenuBinding binding=boundMenus.get(menu);if(binding!=null)bindActionMenu(menu,binding.type,binding.context);}
    }

    private PluginBridge() {
    }

    public static void initialize() {
        if (!GhostLicenseBridge.allowed()) return;
        invoke("initialize", new Class[]{android.content.Context.class}, ApplicationLoader.applicationContext);
    }

    public static String getPluginsJson() {
        Object result = invoke("getPluginsJson", new Class[0]);
        return result instanceof String ? (String) result : "[]";
    }

    public static boolean install(Uri uri) {
        String path=preparePluginImport(uri);
        return path!=null&&installPrepared(path,true);
    }
    public static String preparePluginImport(Uri uri) {
        File directory = new File(ApplicationLoader.applicationContext.getCacheDir(), "plugin-import");
        if (!directory.exists() && !directory.mkdirs()) {
            setLastInstallError("Cannot create the plugin storage directory");
            return null;
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) {
            name = "plugin_" + System.currentTimeMillis() + ".plugin";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        // Document providers often expose an opaque ID as the final URI segment
        // and omit the original extension. The runtime validates the UTF-8 source
        // and metadata after copying, so preserve install support for those URIs.
        if (!name.endsWith(".plugin") && !name.endsWith(".py")) {
            name += ".plugin";
        }
        File target = new File(directory, System.nanoTime() + "_" + name);
        try (InputStream input = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) {
                setLastInstallError("The selected document provider returned no data");
                target.delete();
                return null;
            }
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        } catch (Exception error) {
            FileLog.e(error);
            setLastInstallError("Cannot read the selected plugin: " + error.getMessage());
            target.delete();
            return null;
        }
        return target.getAbsolutePath();
    }
    public static boolean installPrepared(String path,boolean temporary) {
        try { return Boolean.TRUE.equals(invoke("installFile",new Class[]{String.class},path)); }
        finally { if(temporary)new File(path).delete(); }
    }
    public static void showPluginInstallDialog(org.telegram.ui.ActionBar.BaseFragment fragment,String path,boolean temporary,Runnable finished) {
        Utilities.pluginsQueue.postRunnable(()->{
            Object value=invoke("validateFile",new Class[]{String.class},path);
            AndroidUtilities.runOnUIThread(()->{
                java.util.concurrent.atomic.AtomicBoolean installing=new java.util.concurrent.atomic.AtomicBoolean();
                try{
                    org.json.JSONObject metadata=new org.json.JSONObject(value instanceof String?(String)value:"{\"error\":\"Plugin validation failed\"}");
                    if(fragment.getParentActivity()==null){if(temporary)new File(path).delete();return;}
                    if(metadata.has("error")){if(temporary)new File(path).delete();fragment.showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity()).setTitle("Ошибка плагина").setMessage(metadata.optString("error")).setPositiveButton("OK",null).create());return;}
                    String id=metadata.getString("id");boolean update=new File(ApplicationLoader.applicationContext.getFilesDir(),"plugins/"+id+".py").exists();
                    org.telegram.ui.ActionBar.AlertDialog dialog=new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity()).setTitle(update?"Обновить плагин":"Установить плагин")
                        .setMessage(metadata.optString("name")+"\n"+metadata.optString("author")+" · "+metadata.optString("version")+"\n\n"+metadata.optString("description"))
                        .setPositiveButton(update?"Обновить":"Установить",(alert,which)->{
                            installing.set(true);Utilities.pluginsQueue.postRunnable(()->{
                                boolean success=installPrepared(path,temporary);String error=getLastInstallError();
                                AndroidUtilities.runOnUIThread(()->{if(finished!=null)finished.run();if(!success&&fragment.getParentActivity()!=null)fragment.showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity()).setTitle("Ошибка плагина").setMessage(error).setPositiveButton("OK",null).create());});
                            });
                        }).setNegativeButton("Отмена",null).create();
                    dialog.setOnDismissListener(alert->{if(temporary&&!installing.get())new File(path).delete();});fragment.showDialog(dialog);
                }catch(Throwable error){if(temporary)new File(path).delete();FileLog.e(error);}
            });
        });
    }

    public static String getLastInstallError() {
        Object result = invoke("getLastInstallError", new Class[0]);
        return result instanceof String ? (String) result : "";
    }

    private static void setLastInstallError(String error) {
        invoke("setLastInstallError", new Class[]{String.class}, error);
    }

    public static boolean setEnabled(String pluginId, boolean enabled) {
        Object result = invoke("setEnabled", new Class[]{String.class, boolean.class}, pluginId, enabled);
        return result instanceof Boolean && (Boolean) result;
    }

    public static boolean remove(String pluginId) {
        Object result = invoke("removePlugin", new Class[]{String.class}, pluginId);
        return result instanceof Boolean && (Boolean) result;
    }

    public static void reloadAll() {
        invoke("reloadAll", new Class[0]);
    }

    public static String getSettingsJson(String pluginId) {
        Object result = invoke("getSettingsJson", new Class[]{String.class}, pluginId);
        return result instanceof String ? (String) result : "[]";
    }
    public static String getSettingsPageJson(String id,String path) { Object result=invoke("getSettingsPageJson",new Class[]{String.class,String.class},id,path);return result instanceof String?(String)result:"[]"; }
    public static boolean setSettingPageJson(String id,String path,String key,String value) { Object result=invoke("setSettingPageJson",new Class[]{String.class,String.class,String.class,String.class},id,path,key,value);return Boolean.TRUE.equals(result); }
    public static void invokeSettingPage(String id,String path,int index,android.view.View view) { invoke("invokeSettingPage",new Class[]{String.class,String.class,int.class,Object.class},id,path,index,view); }

    public static String getSettingSubmenuJson(String pluginId, int index) {
        Object result = invoke("getSettingSubmenuJson", new Class[]{String.class, int.class}, pluginId, index);
        return result instanceof String ? (String) result : "[]";
    }

    public static boolean setSettingJson(String pluginId, String key, String valueJson) {
        Object result = invoke("setSettingJson", new Class[]{String.class, String.class, String.class}, pluginId, key, valueJson);
        return result instanceof Boolean && (Boolean) result;
    }

    public static boolean invokeSetting(String pluginId, int index) {
        Object result = invoke("invokeSetting", new Class[]{String.class, int.class}, pluginId, index);
        return result instanceof Boolean && (Boolean) result;
    }

    public static boolean invokeSubmenuSetting(String pluginId, int parentIndex, int index) {
        Object result = invoke("invokeSubmenuSetting", new Class[]{String.class, int.class, int.class},
                pluginId, parentIndex, index);
        return result instanceof Boolean && (Boolean) result;
    }

    public static boolean setCustomReaction(String pluginId, int index, String reaction) {
        Object result = invoke("setCustomReaction", new Class[]{String.class, int.class, String.class},
                pluginId, index, reaction);
        return result instanceof Boolean && (Boolean) result;
    }

    public static String getMenuItemsJson(int menuType) {
        Object result = invoke("getMenuItemsJson", new Class[]{int.class}, menuType);
        return result instanceof String ? (String) result : "[]";
    }
    public static String getMenuItemsJson(int menuType, java.util.Map<String,Object> context) {
        Object result = invoke("getMenuItemsJson", new Class[]{int.class, Object.class}, menuType, context);
        return result instanceof String ? (String) result : "[]";
    }
    public static String getEngineStateJson() { Object result=invoke("getEngineStateJson",new Class[0]);return result instanceof String?(String)result:"{}"; }
    public static void setEngineEnabled(boolean value) { invoke("setEngineEnabled",new Class[]{boolean.class},value); }
    public static void setDevMode(boolean value) { invoke("setDevMode",new Class[]{boolean.class},value); }
    public static void clearSafeMode() { invoke("clearSafeMode",new Class[0]); }
    public static void setPinned(String id,boolean value) { invoke("setPinned",new Class[]{String.class,boolean.class},id,value); }
    public static void exportPlugin(android.app.Activity activity,String id,boolean open) {
        try {
            File directory=new File(ApplicationLoader.applicationContext.getFilesDir(),"plugins");
            File source=new File(directory,id+".py");
            if(!source.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator))throw new IllegalArgumentException("Invalid plugin path");
            File temp=new File(ApplicationLoader.applicationContext.getFilesDir(),"temp");temp.mkdirs();File copy=new File(temp,id+".plugin");
            try(java.io.InputStream input=new java.io.FileInputStream(source);java.io.OutputStream output=new FileOutputStream(copy)){byte[] buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);}
            android.net.Uri uri=androidx.core.content.FileProvider.getUriForFile(activity,ApplicationLoader.getApplicationId()+".provider",copy);
            android.content.Intent intent=new android.content.Intent(open?android.content.Intent.ACTION_VIEW:android.content.Intent.ACTION_SEND);
            if(open)intent.setDataAndType(uri,"text/plain");else{intent.setType("application/x-plugin");intent.putExtra(android.content.Intent.EXTRA_STREAM,uri);}
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);activity.startActivity(android.content.Intent.createChooser(intent,id));
        }catch(Throwable error){FileLog.e(error);android.widget.Toast.makeText(activity,error.getMessage(),android.widget.Toast.LENGTH_LONG).show();}
    }

    public static boolean invokeMenu(String pluginId, String itemId, Object context) {
        Object result = invoke("invokeMenu", new Class[]{String.class, String.class, Object.class}, pluginId, itemId, context);
        return result instanceof Boolean && (Boolean) result;
    }

    public static boolean beforeSendMessage(int account, Object params) {
        Object result = invoke("beforeSendMessage", new Class[]{int.class, Object.class}, account, params);
        return result instanceof Boolean && (Boolean) result;
    }

    public static android.view.View customSettingView(String pluginId, int parentIndex, int index, android.content.Context context, Object list, int account, int guid, Object resources, Object adapter) {
        Object result = invoke("customSettingView", new Class[]{String.class, int.class, int.class, android.content.Context.class, Object.class, int.class, int.class, Object.class, Object.class}, pluginId, parentIndex, index, context, list, account, guid, resources, adapter);
        return result instanceof android.view.View ? (android.view.View) result : null;
    }

    public static SendMessagesHelper.SendMessageParams transformSendMessage(int account, SendMessagesHelper.SendMessageParams params) {
        if (!hasHost()) return params;
        Object result = invoke("transformSendMessage", new Class[]{int.class, Object.class}, account, params);
        return result == null ? null : result instanceof SendMessagesHelper.SendMessageParams ? (SendMessagesHelper.SendMessageParams) result : params;
    }

    public static TLObject transformRequest(int account, TLObject request) {
        if (!hasHost()) return request;
        Object result = invoke("transformRequest", new Class[]{int.class, Object.class}, account, request);
        return result == null ? null : result instanceof TLObject ? (TLObject) result : request;
    }

    public static Object[] transformResponse(int account, TLObject request, TLObject response, TLRPC.TL_error error) {
        return transformResponse(account, request.getClass().getSimpleName(), response, error);
    }
    public static Object[] transformResponse(int account, String requestName, TLObject response, TLRPC.TL_error error) {
        Object result = invoke("transformResponse", new Class[]{int.class, Object.class, Object.class, Object.class}, account, requestName, response, error);
        return result instanceof Object[] ? (Object[]) result : new Object[]{false, response, error};
    }
    public static TLRPC.Update transformUpdate(int account, TLRPC.Update update) {
        if (!hasHost()) return update;
        Object result=invoke("transformUpdate",new Class[]{int.class,Object.class},account,update);
        return result==null?null:result instanceof TLRPC.Update?(TLRPC.Update)result:update;
    }

    public static TLRPC.Updates transformUpdates(int account, TLRPC.Updates updates) {
        if (!hasHost()) return updates;
        Object result = invoke("transformUpdates", new Class[]{int.class, Object.class}, account, updates);
        return result == null ? null : result instanceof TLRPC.Updates ? (TLRPC.Updates) result : updates;
    }

    private static boolean hasHost() {
        if (!GhostLicenseBridge.allowed()) return false;
        try { Class.forName(HOST_CLASS); return true; }
        catch (ClassNotFoundException error) { return false; }
    }

    public static boolean beforeRequest(int account, TLObject request) {
        Object result = invoke("beforeRequest", new Class[]{int.class, Object.class}, account, request);
        return result instanceof Boolean && (Boolean) result;
    }

    public static void afterRequest(int account, TLObject request, TLObject response, TLRPC.TL_error error) {
        invoke("afterRequest", new Class[]{int.class, Object.class, Object.class, Object.class}, account, request, response, error);
    }

    public static void onUpdates(int account, TLRPC.Updates updates) {
        invoke("onUpdates", new Class[]{int.class, Object.class}, account, updates);
    }

    public static void onAppEvent(String event) {
        invoke("onAppEvent", new Class[]{String.class}, event);
    }

    private static Object invoke(String name, Class<?>[] parameterTypes, Object... args) {
        if (!GhostLicenseBridge.allowed()) return null;
        try {
            Class<?> local = hostClass;
            if (local == null) {
                local = Class.forName(HOST_CLASS);
                hostClass = local;
            }
            Method method = local.getMethod(name, parameterTypes);
            return method.invoke(null, args);
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Throwable error) {
            FileLog.e("Plugin bridge call failed: " + name, error);
            return null;
        }
    }
}
