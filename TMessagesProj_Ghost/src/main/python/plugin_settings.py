"""JSON settings API for exteraGram plugins, backed by AstraGram preferences."""
import json


def _controller():
    from com.exteragram.messenger.plugins import PluginsController
    return PluginsController.getInstance()


def get_all_settings(plugin_id):
    return json.loads(str(_controller().readPluginSettingsJson(plugin_id)))


def get_setting(plugin_id, key, default=None):
    return get_all_settings(plugin_id).get(key, default)


def set_setting(plugin_id, key, value):
    if not isinstance(key, str):
        raise TypeError("Setting key must be a string")
    payload = json.dumps(value, ensure_ascii=False, allow_nan=False)
    _controller().writePluginSettingJson(plugin_id, key, payload)


def set_all_settings(plugin_id, settings):
    if not isinstance(settings, dict) or not all(isinstance(key, str) for key in settings):
        raise TypeError("Plugin settings must be a dictionary with string keys")
    payload = json.dumps(settings, ensure_ascii=False, allow_nan=False)
    _controller().replacePluginSettingsJson(plugin_id, payload)


def clear_settings(plugin_id):
    _controller().clearPluginSettingsPreferences(plugin_id)
