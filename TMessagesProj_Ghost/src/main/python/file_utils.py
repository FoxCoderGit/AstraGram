import os
from org.telegram.messenger import ApplicationLoader


def get_plugins_dir():
    from plugins_manager import PluginsManager
    return PluginsManager._plugins_dir


def get_cache_dir():
    return str(ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath())
