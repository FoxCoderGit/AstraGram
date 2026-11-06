package com.exteragram.messenger.plugins;

import android.content.SharedPreferences;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.models.*;
import com.exteragram.messenger.plugins.hooks.*;
import com.exteragram.messenger.plugins.utils.MenuContextBuilder;
import org.ghostgram.messenger.plugins.PluginHost;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import de.robv.android.xposed.XC_MethodHook;

/** Reference SDK host. Python owns plugin instances, Java owns public host records. */
public final class PluginsController implements PluginsHooks {
    private static final class Holder { static final PluginsController INSTANCE = new PluginsController(); }
    public final ConcurrentHashMap<String, Plugin> plugins = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, List<SettingItem>> settings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<HookRecord>> hooks = new ConcurrentHashMap<>();
    public final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("plugin_settings", 0);
    public final File pluginsDir = new File(ApplicationLoader.applicationContext.getFilesDir(), "plugins");
    public PyObject pluginManagerClass, devServerClass;
    public Python python;
    public boolean isInitialized;
    private PluginsController() { pluginsDir.mkdirs(); }
    public static PluginsController getInstance() { return Holder.INSTANCE; }
    public void init() { PluginHost.initialize(ApplicationLoader.applicationContext); }
    public void shutdown() { PluginHost.shutdown(); plugins.clear(); settings.clear(); hooks.clear(); python=null; pluginManagerClass=devServerClass=null; isInitialized=false; }
    public void restart() { Utilities.pluginsQueue.postRunnable(() -> { shutdown(); init(); }); }
    public Python getPython() { return python; }
    public void executeScriptsOnAppEvent(String event) { PluginHost.onAppEvent(event); }
    public static boolean isPlugin(File file) { return file!=null && file.getName().toLowerCase(Locale.ROOT).endsWith(".plugin"); }
    public static boolean isPlugin(MessageObject object) { return object!=null && object.getDocument()!=null && FileLoader.getDocumentFileName(object.getDocument()).toLowerCase(Locale.ROOT).endsWith(".plugin"); }
    public boolean isPlugin(Object path) { return String.valueOf(path).toLowerCase(Locale.ROOT).endsWith(".plugin"); }
    public static String fetchParameterValue(String path,String key) {
        java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("^"+java.util.regex.Pattern.quote(key)+"\\s*=\\s*\"([^\"]*)\"$");
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(path), java.nio.charset.StandardCharsets.UTF_8))) {
            String line; while((line=reader.readLine())!=null){java.util.regex.Matcher match=pattern.matcher(line.trim());if(match.matches())return match.group(1);}
        }catch(Exception e){FileLog.e(e);}return null;
    }
    public String getPluginPath(String id) { return new File(pluginsDir,id+".py").getAbsolutePath(); }
    public void showInstallDialog(org.telegram.ui.ActionBar.BaseFragment fragment,String path,boolean unused){PluginBridge.showPluginInstallDialog(fragment,path,false,this::refresh);}
    public void showInstallDialog(org.telegram.ui.ActionBar.BaseFragment fragment,MessageObject message){PluginBridge.showPluginInstallDialog(fragment,FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner).getAbsolutePath(),false,this::refresh);}
    public void loadPluginFromFile(String path,Utilities.Callback<String> callback) {
        Utilities.pluginsQueue.postRunnable(() -> { boolean success=PluginHost.installFile(path); refresh(); complete(callback,success?null:PluginHost.getLastInstallError()); });
    }
    public void loadPluginFromFile(String path,Object unused,Utilities.Callback<String> callback) { loadPluginFromFile(path,callback); }
    public void setPluginEnabled(String id,boolean enabled,Utilities.Callback<String> callback) {
        Utilities.pluginsQueue.postRunnable(() -> { try { manager().callAttr("set_plugin_enabled",id,enabled); refresh(); if(enabled)loadPluginSettings(id);else invalidatePluginSettings(id); complete(callback,null); } catch(Throwable error){refresh();complete(callback,error.getMessage());} });
    }
    public void deletePlugin(String id,Utilities.Callback<String> callback) {
        Utilities.pluginsQueue.postRunnable(() -> { try { manager().callAttr("delete_plugin",id); plugins.remove(id);settings.remove(id);setPluginPinned(id,false);refresh();complete(callback,null); }catch(Throwable error){complete(callback,error.getMessage());} });
    }
    private void complete(Utilities.Callback<String> callback,String error) { if(callback!=null)AndroidUtilities.runOnUIThread(() -> callback.run(error)); }
    private PyObject manager() { return Python.getInstance().getModule("plugins_manager").get("PluginsManager"); }
    public PluginValidationResult validatePluginFromFile(String path) {
        try { PyObject result=manager().callAttr("validate_plugin_from_file",path); return new PluginValidationResult(fromPython(result),null); }
        catch(Throwable error){return new PluginValidationResult(null,error.getMessage());}
    }
    private Plugin fromPython(PyObject data) {
        return new Plugin(string(data,"id"),string(data,"name"),string(data,"author"),string(data,"icon"),string(data,"description"),string(data,"min_version"),string(data,"version"));
    }
    private static String string(PyObject object,String key) { PyObject value=object.callAttr("get",key);return value==null?null:value.toString(); }
    public void refresh() {
        if(!PluginHost.isInitialized())return;
        try {
            JSONArray array=new JSONArray(PluginHost.getPluginsJson());Set<String> ids=new HashSet<>();
            for(int i=0;i<array.length();i++) { JSONObject item=array.getJSONObject(i);String id=item.getString("id");ids.add(id);
                Plugin plugin=new Plugin(id,item.optString("name",id),jsonString(item,"author"),jsonString(item,"icon"),jsonString(item,"description"),jsonString(item,"min_version"),jsonString(item,"version"));
                plugin.setErrorMessage(item.optString("error"));plugin.setEnabled(item.optBoolean("enabled"));plugins.put(id,plugin);
            }
            plugins.keySet().retainAll(ids);org.ghostgram.messenger.plugins.PluginMenuBridge.notifyChanged();
        }catch(Throwable error){FileLog.e(error);}
    }
    public List<SettingItem> getPluginSettingsList(String id){return settings.get(id);}
    public boolean hasPluginSettings(String id){List<SettingItem> list=settings.get(id);return list!=null&&!list.isEmpty();}
    public void loadPluginSettings(){for(String id:plugins.keySet())loadPluginSettings(id);}
    public void loadPluginSettings(String id){Utilities.pluginsQueue.postRunnable(() -> {try{PyObject result=manager().callAttr("load_settings",id);if(result==null)settings.remove(id);else settings.put(id,parsePySettingDefinitions(result.asList()));}catch(Throwable error){settings.remove(id);FileLog.e(error);}});}
    public void invalidatePluginSettings(String id){settings.remove(id);}
    public List<SettingItem> parsePySettingDefinitions(List<PyObject> definitions){
        List<SettingItem> result=new ArrayList<>();
        for(PyObject obj:definitions)try{
            String type=attr(obj,"type"),key=attr(obj,"key"),text=attr(obj,"text"),subtext=attr(obj,"subtext"),icon=attr(obj,"icon");PyObject value=obj.get("default"),change=obj.get("on_change");
            if("header".equals(type)&&text!=null)result.add(new HeaderSetting(text));
            else if("divider".equals(type))result.add(new DividerSetting(text));
            else if("switch".equals(type)&&key!=null&&text!=null&&value!=null)result.add(new SwitchSetting(key,text,value.toBoolean(),subtext,icon,change));
            else if("input".equals(type)&&key!=null&&text!=null)result.add(new InputSetting(key,text,value==null?"":value.toString(),subtext,icon,change));
            else if("selector".equals(type)&&key!=null&&text!=null&&value!=null&&obj.get("items")!=null){List<PyObject> list=obj.get("items").asList();if(!list.isEmpty()){String[] items=new String[list.size()];for(int i=0;i<items.length;i++)items[i]=list.get(i).toString();result.add(new SelectorSetting(key,text,value.toInt(),items,icon,change));}}
            else if("text".equals(type)&&text!=null)result.add(new TextSetting(text,subtext,icon,bool(obj,"accent"),bool(obj,"red"),obj.get("on_click"),obj.get("create_sub_fragment")));
        }catch(Throwable error){FileLog.e(error);}return result;
    }
    private static String attr(PyObject obj,String key){PyObject value=obj.get(key);return value==null?null:value.toString();}
    private static String jsonString(JSONObject object,String key){return object.isNull(key)?null:object.optString(key,null);}
    private static boolean bool(PyObject obj,String key){PyObject value=obj.get(key);return value!=null&&value.toBoolean();}
    private String key(String id,String key){return "plugin_setting_"+id+"_"+key;}
    public boolean getPluginSettingBoolean(String id,String key,boolean fallback){return preferences.getBoolean(key(id,key),fallback);}
    public int getPluginSettingInt(String id,String key,int fallback){return preferences.getInt(key(id,key),fallback);}
    public String getPluginSettingString(String id,String key,String fallback){return preferences.getString(key(id,key),fallback);}
    public void setPluginSettingBoolean(String id,String key,boolean value){preferences.edit().putBoolean(key(id,key),value).apply();loadPluginSettings(id);}
    public void setPluginSettingInt(String id,String key,int value){preferences.edit().putInt(key(id,key),value).apply();loadPluginSettings(id);}
    public void setPluginSettingString(String id,String key,String value){preferences.edit().putString(key(id,key),value).apply();loadPluginSettings(id);}
    public void clearPluginSettingsPreferences(String id){SharedPreferences.Editor edit=preferences.edit();for(String key:preferences.getAll().keySet())if(key.startsWith("plugin_setting_"+id+"_"))edit.remove(key);edit.apply();settings.remove(id);}
    public boolean hasPluginSettingsPreferences(String id){for(String key:preferences.getAll().keySet())if(key.startsWith("plugin_setting_"+id+"_"))return true;return false;}
    public static boolean isPluginPinned(String id){return ExteraConfig.pinnedPlugins.contains(id);}
    public static void setPluginPinned(String id,boolean value){Set<String> pins=new HashSet<>(ExteraConfig.pinnedPlugins);if(value)pins.add(id);else pins.remove(id);ExteraConfig.pinnedPlugins=pins;ExteraConfig.preferences.edit().putStringSet("pinnedPlugins",pins).apply();}
    public void checkDevServer(){if(!PluginHost.isInitialized())return;devServerClass=Python.getInstance().getModule("dev_server").get("DevServer");devServerClass.callAttr(ExteraConfig.pluginsDevMode?"start_server":"stop_server");}
    public void addEventHook(String id,String name,boolean substring,int priority){List<HookRecord> list=hooks.computeIfAbsent(id,key -> new CopyOnWriteArrayList<>());EventHookRecord record=new EventHookRecord(name,substring,priority);if(!list.contains(record))list.add(record);runtimeCall("register_event_hook",id,name,substring,priority);}
    public void removeEventHook(String id,String name){List<HookRecord> list=hooks.get(id);if(list!=null)list.removeIf(record -> record instanceof EventHookRecord&&name.equals(((EventHookRecord)record).getHookName()));runtimeCall("remove_event_hook",id,name);}
    public void addXposedHook(String id,XC_MethodHook.Unhook hook){hooks.computeIfAbsent(id,key -> new CopyOnWriteArrayList<>()).add(new XposedHookRecord(hook));runtimeCall("invalidate_hooks");}
    public void removeXposedHook(String id,XC_MethodHook.Unhook hook){List<HookRecord> list=hooks.get(id);if(list!=null)list.removeIf(record -> record instanceof XposedHookRecord&&record.matches(hook));hook.unhook();runtimeCall("invalidate_hooks");}
    public void removeHooksByPluginId(String id){List<HookRecord> list=hooks.remove(id);if(list!=null)for(HookRecord hook:list)try{hook.cleanup();}catch(Throwable error){FileLog.e(error);}runtimeCall("clear_event_hooks",id);}
    private void runtimeCall(String name,Object... args){if(Python.isStarted())try{Python.getInstance().getModule("plugin_runtime").callAttr(name,args);}catch(Throwable error){FileLog.e(error);}}
    public String addMenuItem(String id,PyObject data){PyObject result=Python.getInstance().getModule("plugin_runtime").callAttr("add_menu_data",id,data);return result==null?null:result.toString();}
    public boolean removeMenuItem(String id,String item){return Python.getInstance().getModule("plugin_runtime").callAttr("remove_menu_item",id,item).toBoolean();}
    public void removeMenuItemsByPluginId(String id){Python.getInstance().getModule("plugin_runtime").callAttr("remove_menu_items",id);}
    public List<MenuItemRecord> getMenuItemsForLocation(String location,MenuContextBuilder builder){return getMenuItemsForLocation(location,builder==null?null:builder.build());}
    public List<MenuItemRecord> getMenuItemsForLocation(String location,Map<String,Object> context){
        List<MenuItemRecord> result=new ArrayList<>();if(!PluginHost.isInitialized()||ExteraConfig.pluginsSafeMode)return result;
        try{for(PyObject data:Python.getInstance().getModule("plugin_runtime").callAttr("menu_records",location).asList()){
            MenuItemRecord record=new MenuItemRecord(string(data,"plugin_id"),data);if(record.checkCondition(context))result.add(record);
        }}catch(Throwable error){FileLog.e(error);}return result;
    }
    @Override public TLObject executePreRequestHook(String name,int account,TLObject request){return PluginBridge.transformRequest(account,request);}
    @Override public PluginsHooks.PostRequestResult executePostRequestHook(String name,int account,TLObject response,TLRPC.TL_error error){Object[] result=PluginHost.transformResponse(account,name,response,error);return new PluginsHooks.PostRequestResult((TLObject)result[1],(TLRPC.TL_error)result[2]);}
    @Override public TLRPC.Update executeUpdateHook(String name,int account,TLRPC.Update update){return PluginBridge.transformUpdate(account,update);}
    @Override public TLRPC.Updates executeUpdatesHook(String name,int account,TLRPC.Updates updates){return PluginBridge.transformUpdates(account,updates);}
    @Override public SendMessagesHelper.SendMessageParams executeSendMessageHook(int account,SendMessagesHelper.SendMessageParams params){return PluginBridge.transformSendMessage(account,params);}
    public static class PluginValidationResult {public Plugin plugin;public String error;public PluginValidationResult(Plugin plugin,String error){this.plugin=plugin;this.error=error;}}
}
