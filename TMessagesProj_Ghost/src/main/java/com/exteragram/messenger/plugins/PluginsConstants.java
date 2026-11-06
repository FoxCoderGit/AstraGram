package com.exteragram.messenger.plugins;

/* JADX INFO: loaded from: classes.dex */
public final class PluginsConstants {
    public static final String APP_PAUSE = "app_pause";
    public static final String APP_RESUME = "app_resume";
    public static final String APP_START = "app_start";
    public static final String APP_STOP = "app_stop";
    public static final String CREATE_SETTINGS = "create_settings";
    public static final String DELETE_PLUGIN = "delete_plugin";
    public static final String ERROR = "error";
    public static final String EXECUTE_EVENT = "execute_event";
    public static final String INIT = "init";
    public static final String LOAD_PLUGIN_FROM_FILE = "load_plugin_from_file";
    public static final String LOAD_SETTINGS = "load_settings";
    public static final String ON_APP_EVENT = "on_app_event";
    public static final String ON_PLUGIN_LOAD = "on_plugin_load";
    public static final String ON_PLUGIN_UNLOAD = "on_plugin_unload";
    public static final String ON_SEND_MESSAGE_HOOK = "execute_on_send_message_hook";
    public static final String ON_UPDATES_HOOK = "execute_on_updates_hook";
    public static final String ON_UPDATE_HOOK = "execute_on_update_hook";
    public static final String PARAMS = "params";
    public static final String PLUGINS = "plugins";
    public static final String PLUGINS_EXT = ".plugin";
    public static final String PLUGINS_MANAGER = "plugins_manager";
    public static final String PLUGINS_MANAGER_CLASS = "PluginsManager";
    public static final String PLUGIN_AUTHOR = "author";
    public static final String PLUGIN_DESCRIPTION = "description";
    public static final String PLUGIN_ENABLED = "enabled";
    public static final String PLUGIN_ERROR_MESSAGE = "error_message";
    public static final String PLUGIN_ICON = "icon";
    public static final String PLUGIN_ID = "id";
    public static final String PLUGIN_MIN_VERSION = "min_version";
    public static final String PLUGIN_NAME = "name";
    public static final String PLUGIN_VERSION = "version";
    public static final String POST_REQUEST_HOOK = "execute_post_request_hook";
    public static final String PRE_REQUEST_HOOK = "execute_pre_request_hook";
    public static final String REQUEST = "request";
    public static final String RESPONSE = "response";
    public static final String SEND_MESSAGE_HOOK = "send_message_hook";
    public static final String SET_PLUGIN_ENABLED = "set_plugin_enabled";
    public static final String SHUTDOWN = "shutdown";
    public static final String STRATEGY = "strategy";
    public static final String UPDATE = "update";
    public static final String UPDATES = "updates";
    public static final String VALIDATE_PLUGIN_FROM_FILE = "validate_plugin_from_file";

    public static final class DevServer {
        public static final String CLASS = "DevServer";
        public static final String MODULE = "dev_server";
        public static final String START_SERVER = "start_server";
        public static final String STOP_SERVER = "stop_server";
    }

    public static class MenuItemProperties {
        public static final String CONDITION = "condition";
        public static final String ICON = "icon";
        public static final String ITEM_ID = "item_id";
        public static final String MENU_TYPE = "menu_type";
        public static final String ON_CLICK = "on_click";
        public static final String PRIORITY = "priority";
        public static final String SUBTEXT = "subtext";
        public static final String TEXT = "text";
    }

    public static class MenuItemTypes {
        public static final String CHAT_ACTION_MENU = "chat_action_menu";
        public static final String DRAWER_MENU = "drawer_menu";
        public static final String MESSAGE_CONTEXT_MENU = "message_context_menu";
        public static final String PROFILE_ACTION_MENU = "profile_action_menu";
    }

    public static final class Settings {
        public static final String ACCENT = "accent";
        public static final String CREATE_SUB_FRAGMENT = "create_sub_fragment";
        public static final String DEFAULT = "default";
        public static final String ICON = "icon";
        public static final String ITEMS = "items";
        public static final String KEY = "key";
        public static final String ON_CHANGE = "on_change";
        public static final String ON_CLICK = "on_click";
        public static final String RED = "red";
        public static final String SUBTEXT = "subtext";
        public static final String TEXT = "text";
        public static final String TYPE = "type";
        public static final String TYPE_DIVIDER = "divider";
        public static final String TYPE_HEADER = "header";
        public static final String TYPE_INPUT = "input";
        public static final String TYPE_SELECTOR = "selector";
        public static final String TYPE_SWITCH = "switch";
        public static final String TYPE_TEXT = "text";
    }

    public static final class Strategy {
        public static final String CANCEL = "CANCEL";
        public static final String DEFAULT = "DEFAULT";
        public static final String MODIFY = "MODIFY";
        public static final String MODIFY_FINAL = "MODIFY_FINAL";
    }

    public static final class Xposed {
        public static final String AFTER_HOOKED_METHOD = "after_hooked_method";
        public static final String BEFORE_HOOKED_METHOD = "before_hooked_method";
        public static final String REPLACE_HOOKED_METHOD = "replace_hooked_method";
    }

    private PluginsConstants() {
    }
}
