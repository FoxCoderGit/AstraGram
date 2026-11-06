"""Plugin lifecycle and callback semantics of the exteraGram 11.12.1 SDK."""
import ast
import importlib.util
import os
import shutil
import sys
import tempfile
import traceback
from packaging.version import parse, InvalidVersion
from base_plugin import (BasePlugin, AppEvent, HookResult, HookStrategy, PluginError,
                         PluginMetadata, XposedHook, MenuItemData, MenuItemType)


class PluginsManager:
    _plugins = {}
    _plugins_dir = None

    @classmethod
    def _publish(cls, instance):
        import plugin_runtime
        plugin_runtime.sync_record(instance)

    @staticmethod
    def _save_enabled(plugin_id, enabled):
        from com.exteragram.messenger import ExteraConfig
        ExteraConfig.preferences.edit().putBoolean("plugin_enabled_" + plugin_id, bool(enabled)).apply()

    @staticmethod
    def _read_enabled(plugin_id):
        from com.exteragram.messenger import ExteraConfig
        return bool(ExteraConfig.preferences.getBoolean("plugin_enabled_" + plugin_id, False))

    @staticmethod
    def _parse_metadata(content):
        values = {}
        for node in ast.iter_child_nodes(ast.parse(content)):
            if isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant):
                for target in node.targets:
                    if isinstance(target, ast.Name) and target.id.startswith("__") and target.id.endswith("__"):
                        values[target.id[2:-2]] = node.value.value
        for key in ("id", "name"):
            if not isinstance(values.get(key), str) or not values[key]:
                raise PluginError("Plugin must define a non-empty __" + key + "__", values.get("id"))
        return PluginMetadata(*(values.get(key) for key in
                                ("id", "name", "description", "author", "version", "icon", "min_version")))

    @staticmethod
    def _check_min_version(metadata):
        if metadata.min_version:
            from org.telegram.messenger import BuildVars
            try:
                if parse(str(BuildVars.BUILD_VERSION_STRING)) < parse(metadata.min_version):
                    raise PluginError("Plugin requires version " + metadata.min_version, metadata.id)
            except InvalidVersion as error:
                raise PluginError(str(error), metadata.id) from error

    @classmethod
    def validate_plugin_from_file(cls, path):
        with open(path, "rb") as source:
            metadata = cls._parse_metadata(source.read())
        cls._check_min_version(metadata)
        return {key: getattr(metadata, key) for key in ("id", "name", "description", "author", "version", "icon")}

    @staticmethod
    def _destination(plugin_id):
        # Keep the reference's broad metadata IDs, while refusing filesystem escape.
        if plugin_id in (".", "..") or any(char in plugin_id for char in ("/", "\\", "\x00")):
            raise PluginError("Plugin ID must be a filename", plugin_id)
        return os.path.join(PluginsManager._plugins_dir, plugin_id + ".py")

    @staticmethod
    def _class_from_module(path, module_name):
        spec = importlib.util.spec_from_file_location(module_name, path)
        module = importlib.util.module_from_spec(spec)
        module.__dict__.update({name: globals()[name] for name in
                               ("BasePlugin", "AppEvent", "HookResult", "HookStrategy", "PluginError",
                                "XposedHook", "MenuItemData", "MenuItemType")})
        sys.modules[module_name] = module
        spec.loader.exec_module(module)
        for obj in module.__dict__.values():
            if isinstance(obj, type) and issubclass(obj, BasePlugin) and obj is not BasePlugin:
                return obj
        raise PluginError("No BasePlugin subclass found")

    @classmethod
    def _test_plugin_initialization(cls, plugin_class, plugin_id):
        instance = plugin_class()
        instance.id = plugin_id + "_validation_test_" + os.urandom(4).hex()
        instance.enabled = True
        instance.initialized = False
        try:
            instance.on_plugin_load()
            instance.on_plugin_unload()
        except Exception as error:
            raise PluginError(traceback.format_exc(), plugin_id) from error
        finally:
            instance._cleanup_hooks()
            cls.cleanup_plugin(instance.id)

    @classmethod
    def load_plugin_from_file(cls, path):
        with open(path, "rb") as source:
            content = source.read()
        metadata = cls._parse_metadata(content)
        cls._check_min_version(metadata)
        destination = cls._destination(metadata.id)
        module_name = metadata.id + "_temp_validation_mod_" + os.urandom(4).hex()
        with tempfile.TemporaryDirectory(prefix="plugintemp_" + metadata.id + "_", dir=cls._plugins_dir) as folder:
            temporary = os.path.join(folder, "plugin.py")
            with open(temporary, "wb") as output:
                output.write(content)
            try:
                cls._test_plugin_initialization(cls._class_from_module(temporary, module_name), metadata.id)
            finally:
                sys.modules.pop(module_name, None)
            # A failed preliminary validation never overwrites the installed file.
            if os.path.abspath(path) != os.path.abspath(destination):
                shutil.copyfile(temporary, destination)
            return cls.load_plugin(metadata.id + ".py", destination)

    @classmethod
    def load_plugin(cls, file_name, file_path=None):
        path = file_path or os.path.join(cls._plugins_dir, file_name)
        with open(path, "rb") as source:
            metadata = cls._parse_metadata(source.read())
        cls._check_min_version(metadata)
        enabled = cls._plugins[metadata.id].enabled if metadata.id in cls._plugins else cls._read_enabled(metadata.id)
        cls.unload_plugin(metadata.id)
        placeholder = BasePlugin()
        for key, value in vars(metadata).items(): setattr(placeholder, key, value)
        cls._plugins[metadata.id] = placeholder
        cls._publish(placeholder)
        try:
            destination = cls._destination(metadata.id)
            if os.path.abspath(path) != os.path.abspath(destination):
                shutil.copy2(path, destination)
                os.remove(path)
                path = destination
            sys.modules.pop(metadata.id, None)
            plugin_class = cls._class_from_module(path, metadata.id)
            cls._test_plugin_initialization(plugin_class, metadata.id)
            instance = plugin_class()
            for key, value in vars(metadata).items(): setattr(instance, key, value)
            instance.enabled = enabled
            instance.initialized = False
            instance.error_message = None
            cls._plugins[metadata.id] = instance
            cls._publish(instance)
            if enabled:
                cls._safe_execute(instance, instance.on_plugin_load)
                instance.initialized = True
            cls._save_enabled(metadata.id, enabled)
            cls._publish(instance)
            return instance
        except Exception as error:
            instance = cls._plugins[metadata.id]
            instance.error_message = traceback.format_exc()
            instance.enabled = instance.initialized = False
            cls.cleanup_plugin(metadata.id)
            cls._save_enabled(metadata.id, False)
            cls._publish(instance)
            raise PluginError(instance.error_message, metadata.id) from error

    @staticmethod
    def _safe_execute(instance, method, *args, handle_exceptions=True):
        from com.exteragram.messenger import ExteraConfig
        if not instance or instance.error_message or ExteraConfig.pluginsSafeMode or not instance.enabled or not callable(method):
            return None
        try:
            return method(*args)
        except Exception:
            import plugin_runtime
            plugin_runtime.log(instance.id, traceback.format_exc())
            if not handle_exceptions: raise

    @classmethod
    def set_plugin_enabled(cls, plugin_id, enabled):
        instance = cls._plugins.get(plugin_id)
        if instance is None: raise PluginError("Plugin not found", plugin_id)
        if enabled:
            if instance.error_message:
                instance.enabled = False
                cls._save_enabled(plugin_id, False)
                raise PluginError(instance.error_message, plugin_id)
            if instance.enabled and instance.initialized: return True
            instance.enabled = True
            try:
                if not instance.initialized:
                    cls.cleanup_plugin(plugin_id)
                    cls._safe_execute(instance, instance.on_plugin_load, handle_exceptions=False)
                instance.initialized = True
                instance.error_message = None
            except Exception as error:
                instance.enabled = instance.initialized = False
                instance.error_message = traceback.format_exc()
                cls.cleanup_plugin(plugin_id)
                cls._save_enabled(plugin_id, False)
                cls._publish(instance)
                raise PluginError(instance.error_message, plugin_id) from error
        else:
            if not instance.enabled: return True
            if instance.initialized: cls._safe_execute(instance, instance.on_plugin_unload)
            instance.enabled = instance.initialized = False
            cls.cleanup_plugin(plugin_id)
        cls._save_enabled(plugin_id, bool(enabled))
        cls._publish(instance)
        return True

    @classmethod
    def cleanup_plugin(cls, plugin_id):
        instance = cls._plugins.get(plugin_id)
        if instance: instance._cleanup_hooks()
        import plugin_runtime
        plugin_runtime.cleanup_plugin(plugin_id)
        sys.modules.pop(plugin_id, None)

    @classmethod
    def unload_plugin(cls, plugin_id, keep_entry=False):
        instance = cls._plugins.get(plugin_id)
        if instance is None: return False
        try:
            if instance.initialized and not instance.error_message:
                cls._safe_execute(instance, instance.on_plugin_unload)
        finally:
            cls.cleanup_plugin(plugin_id)
            instance.initialized = False
            if not keep_entry:
                cls._plugins.pop(plugin_id, None)
                import plugin_runtime
                plugin_runtime._plugins.pop(plugin_id, None)
        return True

    @classmethod
    def delete_plugin(cls, plugin_id):
        cls.unload_plugin(plugin_id)
        path = cls._destination(plugin_id)
        if os.path.isfile(path): os.remove(path)
        from com.exteragram.messenger.plugins import PluginsController
        PluginsController.getInstance().clearPluginSettingsPreferences(plugin_id)
        return True

    @classmethod
    def init(cls, plugins_dir):
        cls._plugins_dir = str(plugins_dir)
        os.makedirs(cls._plugins_dir, exist_ok=True)
        sys.setswitchinterval(0.001)
        if cls._plugins_dir not in sys.path: sys.path.append(cls._plugins_dir)
        for name in os.listdir(cls._plugins_dir):
            if name.endswith(".py"):
                try:
                    import plugin_runtime
                    if plugin_runtime._builtin_feature_plugin_id(os.path.join(cls._plugins_dir, name)): continue
                    cls.load_plugin(name)
                except Exception:
                    import plugin_runtime
                    plugin_runtime.log(name, traceback.format_exc())

    @classmethod
    def shutdown(cls):
        for plugin_id in list(cls._plugins): cls.unload_plugin(plugin_id)
        cls._plugins.clear()
        if cls._plugins_dir in sys.path: sys.path.remove(cls._plugins_dir)
        cls._plugins_dir = None

    @classmethod
    def _chain(cls, ids, method, args, field, value):
        for plugin_id in ids:
            instance = cls._plugins.get(plugin_id)
            if not instance or not instance.enabled or instance.error_message: continue
            try:
                result = getattr(instance, method)(*args, value)
                if not result: continue
                if result.strategy == HookStrategy.CANCEL: return HookResult(strategy=HookStrategy.CANCEL)
                replacement = getattr(result, field)
                if result.strategy in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL) and replacement is not None:
                    value = replacement
                    if result.strategy == HookStrategy.MODIFY_FINAL: break
            except Exception:
                import plugin_runtime
                plugin_runtime.log(plugin_id, traceback.format_exc())
        return HookResult(strategy=HookStrategy.MODIFY, **{field: value})

    @classmethod
    def execute_pre_request_hook(cls, ids, name, account, request):
        return cls._chain(ids, "pre_request_hook", (name, account), "request", request)

    @classmethod
    def execute_on_update_hook(cls, ids, name, account, update):
        return cls._chain(ids, "on_update_hook", (name, account), "update", update)

    @classmethod
    def execute_on_updates_hook(cls, ids, name, account, updates):
        return cls._chain(ids, "on_updates_hook", (name, account), "updates", updates)

    @classmethod
    def execute_on_send_message_hook(cls, ids, account, params):
        return cls._chain(ids, "on_send_message_hook", (account,), "params", params)

    @classmethod
    def execute_post_request_hook(cls, ids, name, account, response, error):
        for plugin_id in ids:
            instance = cls._plugins.get(plugin_id)
            if not instance or not instance.enabled or instance.error_message: continue
            try:
                result = instance.post_request_hook(name, account, response, error)
                if result and result.strategy in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL):
                    if result.response is not None: response = result.response
                    if result.error is not None: error = result.error
                    if result.strategy == HookStrategy.MODIFY_FINAL: break
            except Exception:
                import plugin_runtime
                plugin_runtime.log(plugin_id, traceback.format_exc())
        return HookResult(strategy=HookStrategy.MODIFY, response=response, error=error)

    @classmethod
    def execute_event(cls, event):
        event = AppEvent(event)
        for instance in list(cls._plugins.values()):
            cls._safe_execute(instance, instance.on_app_event, event)

    @classmethod
    def load_settings(cls, plugin_id):
        instance = cls._plugins.get(plugin_id)
        result = cls._safe_execute(instance, instance.create_settings) if instance else None
        return result if isinstance(result, list) else None
