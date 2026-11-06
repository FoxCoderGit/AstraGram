import os
from org.telegram.messenger import ApplicationLoader


def get_cache_dir():
    return str(ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath())
