"""Plugin-bound assets, translations and settings for the Elyx import bridge."""
import json
from pathlib import Path


class SettingsController:
    def __init__(self, plugin_id):
        self.plugin_id = plugin_id

    def get_settings(self):
        import plugin_settings
        return plugin_settings.get_all_settings(self.plugin_id)

    def get_setting(self, key, default=None):
        return self.get_settings().get(key, default)

    def set_setting(self, key, value, reload_settings=False):
        import plugin_runtime
        plugin_runtime.set_setting(self.plugin_id, key, value)
        if reload_settings:
            record = plugin_runtime._plugins.get(self.plugin_id)
            if record: record['instance'].reload_settings()

    def clear_settings(self):
        import plugin_settings
        plugin_settings.clear_settings(self.plugin_id)

    get = __getitem__ = __call__ = get_setting
    set = __setitem__ = set_setting


class Asset:
    def __init__(self, dir_path, filename, name):
        self.filename, self.name = filename, name
        self.path = Path(str(dir_path)) / filename
        self.path_str = str(self.path)

    @classmethod
    def from_path(cls, path):
        path = Path(str(path))
        return cls(path.parent, path.name, path.stem)

    @property
    def java_file(self):
        from java.io import File
        return File(self.path_str)

    def to_image_location(self):
        from org.telegram.messenger import ImageLocation
        return ImageLocation.getForPath(self.path_str)

    def to_drawable(self):
        from android.graphics.drawable import Drawable
        return Drawable.createFromPath(self.path_str)

    def to_bitmap_drawable(self, width=32, height=32):
        from android.graphics import Bitmap, BitmapFactory
        from android.graphics.drawable import BitmapDrawable
        from org.telegram.messenger import ApplicationLoader
        bitmap = BitmapFactory.decodeFile(self.path_str)
        if bitmap is None: return None
        return BitmapDrawable(ApplicationLoader.applicationContext.getResources(), Bitmap.createScaledBitmap(bitmap, width, height, True))

    def content_bytes(self): return self.path.read_bytes()
    def content_string(self): return self.path.read_text(encoding='utf-8')
    def content_json(self): return json.loads(self.content_string())
    def content_yaml(self):
        import yaml
        return yaml.safe_load(self.content_string())

    def content(self):
        if self.path.suffix == '.json': return self.content_json()
        if self.path.suffix in ('.yml', '.yaml'): return self.content_yaml()
        if self.path.suffix == '.txt': return self.content_string()
        return self.content_bytes()

    def __str__(self): return self.path_str


class Assets:
    def __init__(self, path): self.path = Path(path)

    def get(self, name):
        if not isinstance(name, str) or '/' in name or '\\' in name or name in ('.', '..'):
            raise KeyError(name)
        path = self.path / name
        if not path.exists():
            path = next((file for file in sorted(self.path.iterdir()) if file.stem == name), path)
        if path.is_dir(): return Assets(path)
        if path.is_file(): return Asset.from_path(path)
        raise KeyError(name)

    def __getattr__(self, name):
        try: return self.get(name)
        except KeyError: raise AttributeError(name) from None

    __getitem__ = get
    def __contains__(self, name):
        try: self.get(name); return True
        except KeyError: return False
    def __iter__(self): return iter(file.name for file in sorted(self.path.iterdir()))
    def __len__(self): return sum(1 for _ in self.path.iterdir())


class Strings:
    def __init__(self, source):
        self.locales = {}
        if isinstance(source, dict):
            self.locales = source
            return
        for path in sorted(source.iterdir()) if source.is_dir() else [source]:
            if path.suffix not in ('.json', '.yml', '.yaml') or not path.is_file(): continue
            values = Asset.from_path(path).content()
            if isinstance(values, dict):
                locale = path.stem.removeprefix('strings_')
                if locale == 'strings' and all(isinstance(item, dict) for item in values.values()): self.locales.update(values)
                else: self.locales[locale] = values

    def get_with_locale(self, key, default=None, locale=None):
        if not locale:
            try:
                from org.telegram.messenger import LocaleController
                locale = str(LocaleController.getInstance().getCurrentLocale().getLanguage())
            except Exception: locale = 'en'
        for requested in (locale, locale.split('-')[0], 'en', 'strings'):
            values = self.locales.get(requested, {})
            if key in values: return values[key]
        return default

    get = __getitem__ = get_with_locale
    def __call__(self, key, *args, default=None, locale=None, **kwargs):
        value = self.get_with_locale(key, default, locale)
        return value.format(*args, **kwargs) if isinstance(value, str) and (args or kwargs) else value
