import importlib.util
import inspect
import json
import os
import shutil
import sys
import threading
import traceback

from base_plugin import BasePlugin, HookStrategy

_lock = threading.RLock()
_context = None
_plugin_dir = None
_state_file = None
_plugins = {}
_state = {"enabled": {}, "settings": {}, "errors": {}}
_last_install_error = ""


def _save_state():
    if not _state_file:
        return
    temp = _state_file + ".tmp"
    with open(temp, "w", encoding="utf-8") as stream:
        json.dump(_state, stream, ensure_ascii=False, indent=2)
    os.replace(temp, _state_file)


def _load_state():
    global _state
    if _state_file and os.path.exists(_state_file):
        try:
            with open(_state_file, "r", encoding="utf-8") as stream:
                loaded = json.load(stream)
            if isinstance(loaded, dict):
                _state.update(loaded)
        except Exception:
            pass


def initialize(context):
    global _context, _plugin_dir, _state_file
    from plugins_manager import PluginsManager
    with _lock:
        _context = context
        _plugin_dir = os.path.join(str(context.getFilesDir().getAbsolutePath()), "plugins")
        os.makedirs(_plugin_dir, exist_ok=True)
        _state_file = os.path.join(_plugin_dir, "state.json")
        _load_state()
        _migrate_legacy()
        PluginsManager.init(_plugin_dir)


def _builtin_feature_plugin_id(path):
    from plugins_manager import PluginsManager
    try:
        with open(path, 'rb') as source: metadata = PluginsManager._parse_metadata(source.read())
        return metadata.id if metadata.id in ('zwyNoForwardLimit', 'round_client_detector') else None
    except Exception: return None


def _migrate_legacy():
    from com.exteragram.messenger import ExteraConfig
    from plugins_manager import PluginsManager
    if ExteraConfig.preferences.getBoolean('ghost_plugins_migrated_v2', False): return
    for name in os.listdir(_plugin_dir):
        path = os.path.join(_plugin_dir, name)
        if not name.lower().endswith(('.plugin', '.py')) or _builtin_feature_plugin_id(path): continue
        try:
            with open(path, 'rb') as source: metadata = PluginsManager._parse_metadata(source.read())
            target = os.path.join(_plugin_dir, metadata.id + '.py')
            PluginsManager._plugins_dir = _plugin_dir
            target = PluginsManager._destination(metadata.id)
            if os.path.abspath(path) != os.path.abspath(target) and not os.path.exists(target): shutil.copy2(path, target)
            if name.endswith('.py') and os.path.abspath(path) != os.path.abspath(target):
                backup_dir = os.path.join(_plugin_dir, 'legacy-v1')
                os.makedirs(backup_dir, exist_ok=True)
                backup = os.path.join(backup_dir, name)
                if os.path.exists(backup): backup += '.' + os.urandom(4).hex()
                shutil.copy2(path, backup)
                os.remove(path)
            if not ExteraConfig.preferences.contains('plugin_enabled_' + metadata.id):
                PluginsManager._save_enabled(metadata.id, _state.get('enabled', {}).get(metadata.id, False))
        except Exception: log(name, traceback.format_exc())
    for plugin_id, values in _state.get('settings', {}).items():
        for key, value in values.items(): set_setting(plugin_id, key, value)
    _state['errors'] = {}
    _save_state()
    ExteraConfig.preferences.edit().putBoolean('ghost_plugins_migrated_v2', True).apply()


def sync_record(instance):
    record = _plugins.get(instance.id, {})
    if record.get('instance') is not instance: record = {}
    record.update(instance=instance, enabled=bool(instance.enabled), error=instance.error_message or '',
                  meta={key: getattr(instance, key) for key in ('id','name','version','author','description','icon','min_version')})
    _plugins[instance.id] = record
    invalidate_hooks()


def cleanup_plugin(plugin_id):
    record = _plugins.get(plugin_id)
    if record: record.pop('settings_cache', None)
    remove_menu_items(plugin_id)
    invalidate_hooks()


def reload_all():
    from plugins_manager import PluginsManager
    with _lock:
        folder = PluginsManager._plugins_dir or _plugin_dir
        PluginsManager.shutdown()
        _plugins.clear()
        if folder: PluginsManager.init(folder)


def shutdown():
    from plugins_manager import PluginsManager
    from dev_server import DevServer
    DevServer.stop_server()
    PluginsManager.shutdown()
    _plugins.clear()
    invalidate_hooks()


def install_file(source_path):
    global _last_install_error
    from plugins_manager import PluginsManager
    with _lock:
        _last_install_error = ''
        try:
            if _builtin_feature_plugin_id(source_path): return True
            PluginsManager.load_plugin_from_file(source_path)
            return True
        except Exception:
            _last_install_error = traceback.format_exc()
            return False


def validate_file(source_path):
    from plugins_manager import PluginsManager
    return json.dumps(PluginsManager.validate_plugin_from_file(source_path), ensure_ascii=False)


def last_install_error(): return _last_install_error


def set_last_install_error(error):
    global _last_install_error
    _last_install_error = str(error or '')


def remove_plugin(plugin_id):
    from plugins_manager import PluginsManager
    with _lock: return PluginsManager.delete_plugin(plugin_id)


def set_enabled(plugin_id, enabled):
    from plugins_manager import PluginsManager
    with _lock:
        try:
            PluginsManager.set_plugin_enabled(plugin_id, bool(enabled))
            return bool(PluginsManager._plugins[plugin_id].enabled)
        except Exception:
            log(plugin_id, traceback.format_exc())
            return False


def plugins_json():
    with _lock:
        result = []
        for record in _plugins.values():
            item = dict(record["meta"])
            item.pop("path", None)
            item["enabled"] = record["enabled"]
            from com.exteragram.messenger import ExteraConfig
            item["pinned"] = bool(ExteraConfig.pinnedPlugins.contains(record['instance'].id))
            item["error"] = record["instance"].error_message or ""
            item["has_settings"] = bool(_safe_settings(record)) if record["instance"].enabled else False
            result.append(item)
        for plugin_id, error in _state.get("errors", {}).items():
            if plugin_id not in _plugins:
                result.append({"id": plugin_id, "name": plugin_id, "version": "", "author": "",
                               "description": "", "enabled": False, "error": error, "has_settings": False})
        return json.dumps(result, ensure_ascii=False)


def settings_json(plugin_id):
    with _lock:
        record = _plugins.get(plugin_id)
        if not record:
            return "[]"
        return _settings_json(plugin_id, _safe_settings(record, refresh=True))


def _settings_for_path(record, path):
    from plugins_manager import PluginsManager
    items = _safe_settings(record)
    for index in json.loads(path):
        if not isinstance(index, int) or index < 0 or index >= len(items): return []
        callback = getattr(items[index], 'create_sub_fragment', None)
        items = PluginsManager._safe_execute(record['instance'], callback)
        if not isinstance(items, list): return []
    return items


def settings_page_json(plugin_id, path):
    with _lock:
        record = _plugins.get(plugin_id)
        if not record: return '[]'
        try:
            items = _settings_for_path(record, path)
            record.setdefault('settings_pages', {})[path] = items
            return _settings_json(plugin_id, items)
        except Exception:
            log(plugin_id, traceback.format_exc())
            return '[]'


def set_setting_page_json(plugin_id, path, key, value_json):
    from plugins_manager import PluginsManager
    with _lock:
        record = _plugins.get(plugin_id)
        if not record: return False
        value = json.loads(value_json)
        items = _settings_for_path(record, path)
        set_setting(plugin_id, key, value)
        for item in items:
            if getattr(item, 'key', None) == key:
                PluginsManager._safe_execute(record['instance'], getattr(item, 'on_change', None), value)
                break
        record.pop('settings_cache', None)
        record.pop('settings_pages', None)
        return True


def invoke_setting_page(plugin_id, path, index, view):
    with _lock:
        record = _plugins.get(plugin_id)
        if not record: return False
        return _invoke_setting_from_items(record, record.get('settings_pages', {}).get(path, []), index, view)


def _settings_json(plugin_id, items):
        result = []
        for index, item in enumerate(items):
            data = dict(getattr(item, "__dict__", {}))
            kind = item.__class__.__name__.lower()
            if kind in ('switch', 'selector', 'input') and (getattr(item,'key',None) is None or getattr(item,'text',None) is None): continue
            if kind in ('switch', 'selector') and getattr(item,'default',None) is None: continue
            if kind == 'selector' and not getattr(item,'items',None): continue
            if kind in ('header', 'text') and getattr(item,'text',None) is None: continue
            if kind not in ('switch','selector','input','text','header','divider','edittext','custom'): continue
            row = {"type": kind, "index": index}
            if kind == 'custom':
                row['native_view'] = data.get('view') is not None or callable(data.get('create_view'))
            # ExteraGram custom rows can contain an Android view factory. The
            # GhostGram settings renderer cannot reuse that view safely, but
            # Personal Quick Reactions exposes a stable row payload which we
            # render as a native editable reaction row.
            if kind == "custom" and not row.get('native_view') and plugin_id == "personal_quick_reactions":
                args = tuple(data.get("factory_args") or ())
                if len(args) >= 4:
                    reaction = str(args[3] or "__follow_global__")
                    row.update({
                        "type": "customreaction",
                        "text": str(args[2]),
                        "subtext": "Quick reaction",
                        "value": reaction,
                        "clickable": True,
                    })
                    result.append(row)
                    continue
            for name in ("key", "text", "subtext", "hint", "icon", "accent", "red", "link_alias",
                         "default", "items", "multiline", "max_length", "mask"):
                value = data.get(name, getattr(item, name, None))
                if value is not None and not callable(value):
                    row[name] = value
            if row.get("key"):
                row["value"] = get_setting(plugin_id, row["key"], row.get("default"))
            row["clickable"] = callable(data.get("on_click")) or callable(data.get("create_sub_fragment"))
            row["has_submenu"] = callable(data.get("create_sub_fragment"))
            result.append(row)
        return json.dumps(result, ensure_ascii=False)


def submenu_settings_json(plugin_id, index):
    with _lock:
        record = _plugins.get(plugin_id)
        if not record:
            return "[]"
        items = _safe_settings(record)
        if index < 0 or index >= len(items):
            return "[]"
        callback = getattr(items[index], "create_sub_fragment", None)
        if not callable(callback):
            return "[]"
        try:
            nested = callback() or []
            return _settings_json(plugin_id, nested)
        except Exception:
            record["error"] = traceback.format_exc()
            _state.setdefault("errors", {})[plugin_id] = record["error"]
            _save_state()
            return "[]"


def set_setting_json(plugin_id, key, value_json):
    value = json.loads(value_json)
    set_setting(plugin_id, key, value)
    record = _plugins.get(plugin_id)
    if record:
        candidates = list(_safe_settings(record))
        for item in list(candidates):
            sub = getattr(item, "create_sub_fragment", None)
            if callable(sub):
                try:
                    candidates.extend(sub() or [])
                except Exception:
                    pass
        for item in candidates:
            if getattr(item, "key", None) == key:
                callback = getattr(item, "on_change", None)
                if callable(callback):
                    try:
                        callback(value)
                    except Exception:
                        record["error"] = traceback.format_exc()
                        _state.setdefault("errors", {})[plugin_id] = record["error"]
                        _save_state()
                        return False
                break
    return True


def _invoke_setting_from_items(record, items, index, view=None):
        if index < 0 or index >= len(items):
            return False
        item = items[index]
        callback = getattr(item, "on_click", None)
        if not callable(callback):
            return False
        try:
            if item.__class__.__name__ == 'Custom':
                callback(None, item.item, getattr(item, '_bound_view', None))
                return True
            callback(view)
            return True
        except Exception:
            record["error"] = traceback.format_exc()
            _state.setdefault("errors", {})[record["meta"]["id"]] = record["error"]
            _save_state()
            return False


def invoke_setting(plugin_id, index):
    with _lock:
        record = _plugins.get(plugin_id)
        if not record:
            return False
        items = _safe_settings(record)
        return _invoke_setting_from_items(record, items, index)


def invoke_submenu_setting(plugin_id, parent_index, index):
    with _lock:
        record = _plugins.get(plugin_id)
        if not record:
            return False
        root = _safe_settings(record)
        if parent_index < 0 or parent_index >= len(root):
            return False
        try:
            builder = getattr(root[parent_index], "create_sub_fragment", None)
            items = builder() if callable(builder) else []
        except Exception:
            record["error"] = traceback.format_exc()
            _state.setdefault("errors", {})[plugin_id] = record["error"]
            _save_state()
            return False
        return _invoke_setting_from_items(record, items or [], index)


def set_custom_reaction(plugin_id, index, reaction):
    """Persist a Personal Quick Reactions row edited by GhostGram's UI."""
    with _lock:
        record = _plugins.get(plugin_id)
        if plugin_id != "personal_quick_reactions" or not record:
            return False
        items = _safe_settings(record)
        if index < 0 or index >= len(items):
            return False
        args = tuple(getattr(items[index], "factory_args", ()) or ())
        if len(args) < 2:
            return False
        try:
            account, user_id = int(args[0]), int(args[1])
            plugin = record["instance"]
            users = plugin._read_users()
            user = plugin._user_record(users, account, user_id)
            if user is None:
                return False
            user["reaction"] = None if reaction == "__follow_global__" else str(reaction)
            plugin._write_users(users)
            return True
        except Exception:
            record["error"] = traceback.format_exc()
            _state.setdefault("errors", {})[plugin_id] = record["error"]
            _save_state()
            return False


_menu_index = {}
_interested_cache = {}
_MENU_TYPES = {0: 'drawer_menu', 1: 'chat_action_menu', 2: 'message_context_menu', 3: 'profile_action_menu'}


def is_safe_mode():
    from com.exteragram.messenger import ExteraConfig
    return bool(ExteraConfig.pluginsSafeMode)


def invalidate_hooks(): _interested_cache.clear()


def register_event_hook(plugin_id, name, substring, priority):
    record = _plugins.get(plugin_id)
    if record: record['instance'].__dict__.setdefault('_hook_options', {}).setdefault((name, bool(substring)), int(priority))
    invalidate_hooks()


def remove_event_hook(plugin_id, name):
    record = _plugins.get(plugin_id)
    if record:
        hooks = getattr(record['instance'], '_hook_options', {})
        for key in list(hooks):
            if key[0] == name: hooks.pop(key)
    invalidate_hooks()


def clear_event_hooks(plugin_id):
    record = _plugins.get(plugin_id)
    if record: getattr(record['instance'], '_hook_options', {}).clear()
    invalidate_hooks()


def notify_menus_changed():
    try:
        from org.ghostgram.messenger.plugins import PluginMenuBridge
        PluginMenuBridge.notifyChanged()
    except ImportError: pass


def add_menu_item(owner, item):
    import uuid
    if is_safe_mode() or not item.menu_type or not item.text or not callable(item.on_click): return None
    if not item.item_id: item.item_id = str(uuid.uuid4())
    old = _menu_index.get(item.item_id)
    if old:
        old[0]._menu_items[:] = [entry for entry in old[0]._menu_items if entry.item_id != item.item_id]
    _menu_index[item.item_id] = (owner, item)
    owner.__dict__.setdefault('_menu_items', []).append(item)
    notify_menus_changed()
    return item.item_id


def remove_menu_item(plugin_id, item_id):
    old = _menu_index.get(item_id)
    if not old or old[0].id != plugin_id: return False
    _menu_index.pop(item_id)
    old[0]._menu_items[:] = [entry for entry in old[0]._menu_items if entry.item_id != item_id]
    notify_menus_changed()
    return True


def remove_menu_items(plugin_id):
    for item_id, (owner, item) in list(_menu_index.items()):
        if owner.id == plugin_id: remove_menu_item(plugin_id, item_id)


def add_menu_data(plugin_id, data):
    from base_plugin import MenuItemData, MenuItemType
    from plugins_manager import PluginsManager
    owner = PluginsManager._plugins.get(plugin_id)
    if owner is None: return None
    values = dict(data)
    values['menu_type'] = MenuItemType(values['menu_type'])
    return add_menu_item(owner, MenuItemData(**values))


def menu_records(location):
    return [dict(plugin_id=owner.id, menu_type=item.menu_type.value, text=item.text,
                 on_click=item.on_click, item_id=item.item_id, icon=item.icon, subtext=item.subtext,
                 condition=item.condition, priority=item.priority)
            for owner, item in sorted(_menu_index.values(), key=lambda pair: -pair[1].priority)
            if not is_safe_mode() and owner.id in _plugins and _plugins[owner.id]['instance'] is owner
            and owner.enabled and not owner.error_message and item.menu_type.value == location]


def menu_items_json(menu_type, context=None):
    if is_safe_mode(): return '[]'
    location = _MENU_TYPES.get(menu_type, menu_type)
    with _lock:
        result = []
        for owner, item in list(_menu_index.values()):
            if owner.id not in _plugins or _plugins[owner.id]['instance'] is not owner: continue
            if not owner.enabled or owner.error_message or item.menu_type.value != location: continue
            if item.condition and context is not None:
                from org.ghostgram.messenger.plugins import PluginMenuBridge
                if not PluginMenuBridge.matches(item.condition, context): continue
            result.append({'plugin_id': owner.id, 'item_id': item.item_id, 'text': str(item.text),
                           'icon': item.icon or '', 'priority': int(item.priority)})
        result.sort(key=lambda item: -item['priority'])
        return json.dumps(result, ensure_ascii=False)


def invoke_menu(plugin_id, item_id, context=None):
    if is_safe_mode(): return False
    with _lock:
        found = _menu_index.get(item_id)
        if not found or found[0].id != plugin_id or not found[0].enabled or found[0].error_message: return False
        try:
            found[1].on_click(context)
            return True
        except Exception:
            log(plugin_id, traceback.format_exc())
            return False


def _safe_call(record, method, *args):
    from plugins_manager import PluginsManager
    instance = record['instance']
    PluginsManager._safe_execute(instance, getattr(instance, method, None), *args)
    return True


def _enabled_records():
    if is_safe_mode(): return []
    return [record for record in list(_plugins.values())
            if record['instance'].enabled and not record['instance'].error_message]


def _matching_records(name):
    if is_safe_mode(): return []
    if name not in _interested_cache:
        matches = []
        for record in _enabled_records():
            priorities = [priority for (hook, substring), priority in getattr(record['instance'], '_hook_options', {}).items()
                          if hook == name or substring and hook and hook in name]
            if priorities: matches.append((max(priorities), record['instance'].id))
        matches.sort(key=lambda item: (-item[0], item[1].encode('utf-16-be', 'surrogatepass')))
        _interested_cache[name] = [plugin_id for priority, plugin_id in matches]
    return [_plugins[plugin_id] for plugin_id in _interested_cache[name] if plugin_id in _plugins]


def _object_name(value): return str(value.getClass().getSimpleName())


def _ids(name): return [record['instance'].id for record in _matching_records(name)]


def transform_send(account, params):
    from plugins_manager import PluginsManager
    result = PluginsManager.execute_on_send_message_hook(_ids('send_message_hook'), account, params)
    return None if result.strategy == HookStrategy.CANCEL else result.params


def transform_pre_request(account, request):
    from plugins_manager import PluginsManager
    name = _object_name(request)
    result = PluginsManager.execute_pre_request_hook(_ids(name), name, account, request)
    return None if result.strategy == HookStrategy.CANCEL else result.request


def transform_post_request(account, request_name, response, error):
    from plugins_manager import PluginsManager
    name = request_name if isinstance(request_name, str) else _object_name(request_name)
    result = PluginsManager.execute_post_request_hook(_ids(name), name, account, response, error)
    return [False, result.response, result.error]


def transform_updates(account, updates):
    if updates is None: return None
    from plugins_manager import PluginsManager
    name = _object_name(updates)
    result = PluginsManager.execute_on_updates_hook(_ids(name), name, account, updates)
    if result.strategy == HookStrategy.CANCEL: return None
    updates = result.updates
    # The reference hooks short/list entries here and hooks processUpdateArray
    # entries again. Preserve both host entry points rather than deduplicating.
    if _object_name(updates) == 'TL_updateShort':
        updates.update = transform_update(account, updates.update)
    else:
        nested = getattr(updates, 'updates', None)
        if nested is not None:
            index = 0
            while index < int(nested.size()):
                transformed = transform_update(account, nested.get(index))
                if transformed is None: nested.remove(index)
                else:
                    nested.set(index, transformed)
                    index += 1
    return updates


def transform_update(account, update):
    if update is None: return None
    from plugins_manager import PluginsManager
    name = _object_name(update)
    result = PluginsManager.execute_on_update_hook(_ids(name), name, account, update)
    return None if result.strategy == HookStrategy.CANCEL else result.update


def dispatch_send(account, params): return transform_send(account, params) is None

def dispatch_pre_request(account, request): return transform_pre_request(account, request) is None

def dispatch_post_request(account, request, response, error): return transform_post_request(account, request, response, error)

def dispatch_update(account, updates): return transform_updates(account, updates)


def dispatch_app_event(event_type):
    from plugins_manager import PluginsManager
    mapping = {'START':'app_start', 'STOP':'app_stop', 'PAUSE':'app_pause', 'RESUME':'app_resume'}
    PluginsManager.execute_event(mapping.get(str(event_type), str(event_type)))


def get_setting(plugin_id, key, default=None):
    from com.exteragram.messenger.plugins import PluginsController
    controller = PluginsController.getInstance()
    try:
        if isinstance(default, bool): return bool(controller.getPluginSettingBoolean(plugin_id, key, default))
        if isinstance(default, int): return int(controller.getPluginSettingInt(plugin_id, key, default))
        return controller.getPluginSettingString(plugin_id, key, None if default is None else str(default))
    except Exception: return default


def set_setting(plugin_id, key, value):
    from com.exteragram.messenger.plugins import PluginsController
    controller = PluginsController.getInstance()
    try:
        if isinstance(value, bool): controller.setPluginSettingBoolean(plugin_id, key, value)
        elif isinstance(value, int): controller.setPluginSettingInt(plugin_id, key, value)
        else: controller.setPluginSettingString(plugin_id, key, None if value is None else str(value))
        record = _plugins.get(plugin_id)
        if record:
            record.pop('settings_cache', None)
            record.pop('settings_pages', None)
    except Exception: log(plugin_id, traceback.format_exc())


def export_settings(plugin_id):
    from com.exteragram.messenger.plugins import PluginsController
    values = PluginsController.getInstance().preferences.getAll()
    prefix = 'plugin_setting_' + plugin_id + '_'
    return {str(entry.getKey())[len(prefix):]: entry.getValue() for entry in values.entrySet()
            if str(entry.getKey()).startswith(prefix)}


def import_settings(plugin_id, settings):
    if not isinstance(settings, dict): return False
    for key, value in settings.items(): set_setting(plugin_id, key, value)
    return True


def _safe_settings(record, refresh=False):
    from plugins_manager import PluginsManager
    if not refresh and 'settings_cache' in record: return record['settings_cache']
    items = PluginsManager.load_settings(record['instance'].id)
    record['settings_cache'] = items if isinstance(items, list) else []
    return record['settings_cache']


def log(plugin_id, value):
    from org.telegram.messenger import FileLog
    FileLog.d('[' + str(plugin_id) + '] ' + str(value))


def custom_setting_view(plugin_id, parent_index, index, context, list_view, account, guid, resources, adapter):
    from client_utils import account_scope
    record = _plugins.get(plugin_id)
    if not record: return None
    try:
        items = _safe_settings(record)
        if parent_index >= 0: items = items[parent_index].create_sub_fragment()
        setting = items[index]
        with account_scope(account):
            view = getattr(setting, 'view', None)
            if view is None:
                view = setting.create_view(context, list_view, account, guid, resources)
            view = getattr(view, 'java', view)
            bind = getattr(setting, 'bind_view', None)
            if callable(bind): bind(view, setting.item, True, adapter, list_view)
            setting._bound_view = view
            return view
    except Exception:
        record['error'] = traceback.format_exc()
        log(plugin_id, record['error'])
        return None
