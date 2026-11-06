from dataclasses import dataclass
from enum import Enum
from abc import ABC, abstractmethod


class HookStrategy(Enum):
    CANCEL = "CANCEL"
    MODIFY = "MODIFY"
    DEFAULT = "DEFAULT"
    MODIFY_FINAL = "MODIFY_FINAL"
    NONE = "DEFAULT"


@dataclass
class HookResult:
    strategy: HookStrategy = HookStrategy.DEFAULT
    request: object = None
    response: object = None
    update: object = None
    updates: object = None
    error: object = None
    params: object = None


@dataclass
class PluginMetadata:
    id: str
    name: str
    description: str
    author: str
    version: str
    icon: str
    min_version: str


class PluginError(Exception):
    def __init__(self, message, plugin_id=None):
        super().__init__(message)
        self.plugin_id = plugin_id


class MenuItemType(Enum):
    MESSAGE_CONTEXT_MENU = "message_context_menu"
    DRAWER_MENU = "drawer_menu"
    CHAT_ACTION_MENU = "chat_action_menu"
    PROFILE_ACTION_MENU = "profile_action_menu"
    MAIN_MENU = "drawer_menu"
    USER_PROFILE_MENU = "profile_action_menu"


class AppEvent(Enum):
    START = "app_start"
    STOP = "app_stop"
    PAUSE = "app_pause"
    RESUME = "app_resume"


@dataclass
class MenuItemData:
    menu_type: MenuItemType
    text: str
    on_click: object
    item_id: str = None
    icon: str = None
    subtext: str = None
    condition: str = None
    priority: int = 0


class XposedHook(ABC):
    pass


class MethodHook(XposedHook):
    def is_replacement(self):
        return False

    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        pass


class MethodReplacement(XposedHook):
    def is_replacement(self):
        return True

    @abstractmethod
    def replace_hooked_method(self, param):
        pass


class BasePlugin:
    def __init__(self):
        self._plugin_id = ""
        self.name = self.description = self.author = self.version = self.min_version = ""
        self.icon = self.error_message = None
        self.enabled = self.initialized = False
        self._request_hooks = set()
        self._menu_items = []
        self._hook_options = {}
        self._java_hooks = []
        self._send_priority = 0

    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def on_app_event(self, event_type):
        pass

    def create_settings(self):
        return []

    def pre_request_hook(self, request_name, account, request): return HookResult()
    def post_request_hook(self, request_name, account, response, error): return HookResult()
    def on_update_hook(self, update_name, account, update): return HookResult()
    def on_updates_hook(self, container_name, account, updates): return HookResult()
    def on_send_message_hook(self, account, params): return HookResult()

    def add_hook(self, request_name, match_substring=False, priority=0):
        if hasattr(request_name, "getSimpleName"):
            request_name = request_name.getSimpleName()
        self.__dict__.setdefault('_request_hooks', set()).add(str(request_name))
        self.__dict__.setdefault('_hook_options', {}).setdefault((str(request_name), bool(match_substring)), int(priority))
        controller = self._java_controller()
        if controller is not None: controller.addEventHook(self.id, str(request_name), bool(match_substring), int(priority))
        self._invalidate_hooks()

    def add_on_send_message_hook(self, priority=0):
        """Compatibility marker used by current exteraGram plugins."""
        self.add_hook("send_message_hook", priority=priority)

    def remove_hook(self, name):
        self.__dict__.setdefault('_request_hooks', set()).discard(str(name))
        self.__dict__.setdefault('_hook_options', {})
        for key in list(self._hook_options):
            if key[0] == str(name): self._hook_options.pop(key)
        controller = self._java_controller()
        if controller is not None: controller.removeEventHook(self.id, str(name))
        self._invalidate_hooks()

    def _invalidate_hooks(self):
        import plugin_runtime
        plugin_runtime.invalidate_hooks()

    @staticmethod
    def _java_controller():
        try:
            from com.exteragram.messenger.plugins import PluginsController
            return PluginsController.getInstance()
        except ImportError: return None

    def client(self, account=None):
        from client_utils import get_client
        return get_client(account)

    @property
    def logger(self):
        from log_utils import Logger
        if not hasattr(self, '_logger'): self._logger = Logger(self._plugin_id)
        return self._logger

    @logger.setter
    def logger(self, value): self._logger = value

    def add_menu_item(self, item):
        import plugin_runtime
        return plugin_runtime.add_menu_item(self, item)

    def remove_menu_item(self, item_id):
        import plugin_runtime
        return plugin_runtime.remove_menu_item(self.id, item_id)

    def get_setting(self, key, default=None):
        from plugin_runtime import get_setting
        return get_setting(self._plugin_id, key, default)

    def set_setting(self, key, value, reload_settings=False):
        from plugin_runtime import set_setting
        set_setting(self._plugin_id, key, value)
        if reload_settings: self.reload_settings()

    def export_settings(self):
        from plugin_runtime import export_settings
        return export_settings(self._plugin_id)

    def import_settings(self, settings, reload_settings=True):
        from plugin_runtime import import_settings
        return import_settings(self._plugin_id, settings)

    def reload_settings(self):
        import plugin_runtime
        record = plugin_runtime._plugins.get(self._plugin_id)
        if record: record.pop('settings_cache', None)
        return True

    @property
    def id(self):
        return self._plugin_id

    @id.setter
    def id(self, value):
        self._plugin_id = value

    def unhook_method(self, hook):
        if hook is not None and hasattr(hook, "unhook"):
            controller = self._java_controller()
            if controller is not None: controller.removeXposedHook(self.id, hook)
            else: hook.unhook()
            if hook in getattr(self, '_java_hooks', []): self._java_hooks.remove(hook)
            self._invalidate_hooks()

    def log(self, value):
        from plugin_runtime import log
        log(self._plugin_id, value)

    def hook_method(self, method, callback=None, priority=None, *, before=None, after=None,
                    before_filters=None, after_filters=None):
        from hook_utils import hook_method
        from plugin_runtime import is_safe_mode
        if is_safe_mode(): return None
        if callback is None:
            if before is None and after is None: raise TypeError("xposed_hook is required")
            callback = BaseHook(self, before=before, after=after,
                                before_filters=before_filters, after_filters=after_filters)
        handle = hook_method(self, method, callback, priority)
        if handle is not None:
            self.__dict__.setdefault('_java_hooks', []).append(handle)
            controller = self._java_controller()
            if controller is not None: controller.addXposedHook(self.id, handle)
        self._invalidate_hooks()
        return handle

    def hook_all_methods(self, target, method_name, callback=None, priority=None, **kwargs):
        """Hook every overload of a Java method, as ExteraGram plugins expect."""
        require_match = kwargs.pop('require_match', False)
        if target is None:
            raise ValueError("Java hook target is missing: " + str(method_name))
        try:
            methods = target.getDeclaredMethods()
        except Exception:
            try:
                methods = target.getClass().getDeclaredMethods()
            except Exception as error:
                raise RuntimeError("Cannot inspect Java hook target: " + str(method_name)) from error
        hooks = []
        for method in methods:
            try:
                if str(method.getName()) == str(method_name):
                    method.setAccessible(True)
                    hooks.append(self.hook_method(method, callback, priority, **kwargs))
            except Exception:
                for hook in hooks: hook.unhook()
                raise
        if require_match and not hooks:
            raise LookupError("Java method not found: " + str(method_name))
        return hooks

    def hook_all_constructors(self, target, callback=None, priority=None, **kwargs):
        return [self.hook_method(member, callback, priority, **kwargs)
                for member in target.getDeclaredConstructors()]

    def _cleanup_hooks(self):
        for handle in getattr(self, '_java_hooks', []):
            try: handle.unhook()
            except Exception as error: self.log(error)
        self.__dict__.setdefault('_java_hooks', []).clear()
        controller = self._java_controller()
        if controller is not None:
            controller.removeHooksByPluginId(self.id)
            controller.invalidatePluginSettings(self.id)
        import plugin_runtime
        plugin_runtime.remove_menu_items(self.id)
        self.__dict__.setdefault('_menu_items', []).clear()
        self.__dict__.setdefault('_request_hooks', set()).clear()
        self.__dict__.setdefault('_hook_options', {}).clear()
        self._invalidate_hooks()


def hook_filters(*filters):
    def decorate(callback):
        callback.__hook_filters__ = filters
        return callback
    return decorate


class BaseHook(MethodHook):
    def __init__(self, plugin=None, *, before=None, after=None, before_filters=None, after_filters=None):
        self.plugin, self.before, self.after = plugin, before, after
        self.before_filters, self.after_filters = before_filters or [], after_filters or []

    def before_hooked_method(self, param):
        from hook_utils import matches_filters
        if self.before and matches_filters(self.before_filters, param): self.before(param)

    def after_hooked_method(self, param):
        from hook_utils import matches_filters
        if self.after and matches_filters(self.after_filters, param): self.after(param)


from hook_utils import HookFilter
