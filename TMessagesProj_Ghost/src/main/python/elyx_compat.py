"""Install source-compatible Elyx archives using the AstraGram plugin lifecycle."""
import builtins
import hashlib
import importlib
import importlib.abc
import importlib.machinery
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile
from types import ModuleType
import zipfile

ARCHIVE_MAX = 10 * 1024 * 1024
EXPANDED_MAX = 48 * 1024 * 1024
REFMAP = ('refmap.yaml', 'refmap.yml', 'refmap.json')
METADATA = ('metainfo.yaml', 'metainfo.yml', 'metainfo.json')
FIELDS = ('id', 'name', 'description', 'author', 'version', 'icon', 'min_version',
          'app_version', 'sdk_version', 'category', 'requirements')
# These absolute imports belong to the host SDK even if the archive has a
# package with the same name. Relative imports still resolve inside Elyx.
_SDK_IMPORT_ROOTS = frozenset((
    'android', 'androidx', 'android_utils', 'base_plugin', 'client_utils',
    'com', 'de', 'elyx', 'extera_utils', 'file_utils', 'hook_utils', 'importlib',
    'java', 'log_utils', 'markdown_utils', 'org', 'plugin_settings', 'ui',
))
_scopes = {}
_namespaces = {}


def is_archive(path):
    return str(path).lower().endswith(('.elyx', '.eaf')) or zipfile.is_zipfile(path)


def _path(name):
    if not isinstance(name, str):
        raise ValueError('Invalid Elyx path')
    result = PurePosixPath(name)
    if not result.parts or result.is_absolute() or '..' in result.parts or '\\' in name or ':' in name:
        raise ValueError('Invalid Elyx path: ' + name)
    return result


def _values(data, name):
    if len(data) > 65536:
        raise ValueError('Elyx metadata is too large')
    if name.endswith('.json'):
        result = json.loads(data)
    elif name.endswith('.py'):
        from plugins_manager import PluginsManager
        result = vars(PluginsManager._parse_metadata(data))
    else:
        import yaml
        result = yaml.safe_load(data)
    if not isinstance(result, dict):
        raise ValueError('Expected an Elyx metadata mapping')
    return result


def _read(archive):
    files = {}
    size = 0
    for info in archive.infolist():
        name = _path(info.filename).as_posix()
        if stat.S_ISLNK(info.external_attr >> 16) or info.flag_bits & 1:
            raise ValueError('Elyx archive contains a symbolic link or encrypted entry')
        if info.is_dir():
            continue
        size += info.file_size
        if name in files or len(files) >= 2048 or size > EXPANDED_MAX:
            raise ValueError('Invalid or oversized Elyx archive')
        files[name] = info
    config = next((name for name in REFMAP if name in files), None)
    if config is None:
        raise ValueError('Elyx archive has no refmap')
    refmap = _values(archive.read(files[config]), config)
    meta_file = _path(refmap.get('metainfo') or next((name for name in METADATA if name in files), 'metainfo.yml')).as_posix()
    main = _path(refmap.get('main') or ('main.py' if 'main.py' in files else 'main.pyc')).as_posix()
    if meta_file not in files or main not in files or not main.endswith(('.py', '.pyc')):
        raise ValueError('Elyx entry point or metadata file is missing')
    raw = _values(archive.read(files[meta_file]), meta_file)
    metadata = {key.strip('_'): value for key, value in raw.items() if isinstance(key, str) and key.strip('_') in FIELDS}
    plugin_id = metadata.get('id')
    if not isinstance(plugin_id, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', plugin_id) or plugin_id in ('.', '..'):
        raise ValueError('Invalid Elyx plugin ID')
    if not isinstance(metadata.get('name'), str) or not metadata['name']:
        raise ValueError('Elyx name is missing')
    for key, value in metadata.items():
        if key == 'requirements' and isinstance(value, (list, tuple)) and all(isinstance(item, str) for item in value):
            continue
        if value is not None and not isinstance(value, str):
            raise ValueError('Invalid Elyx metadata field ' + key)
    exports = {key: _path(value).as_posix() for key, value in refmap.items()
               if key in ('main', 'metainfo', 'assets', 'strings') and value is not None}
    exports['main'] = main
    if 'assets' not in exports and any(path.startswith('assets/') for path in files):
        exports['assets'] = 'assets'
    if 'strings' not in exports and any(path.startswith('strings/') for path in files):
        exports['strings'] = 'strings'
    return metadata, exports, files


def _open(path):
    if Path(path).stat().st_size > ARCHIVE_MAX:
        raise ValueError('Elyx archive exceeds 10 MB')
    return zipfile.ZipFile(path)


def validate(path):
    from base_plugin import PluginMetadata
    from plugins_manager import PluginsManager
    with _open(path) as archive:
        metadata, _, _ = _read(archive)
    PluginsManager._check_min_version(PluginMetadata(**{key: metadata.get(key) for key in FIELDS}))
    return dict(metadata, archive=True)


def directory(manager, plugin_id):
    manager._destination(plugin_id)
    return Path(manager._plugins_dir) / 'ElyxPlugins' / plugin_id


def legacy_directory(manager, plugin_id):
    manager._destination(plugin_id)
    return Path(manager._plugins_dir) / '.elyx' / plugin_id


def install(path, manager):
    metadata = validate(path)
    plugin_id = metadata.pop('id')
    metadata.pop('archive', None)
    target = directory(manager, plugin_id)
    target.parent.mkdir(exist_ok=True)
    wrapper_path = Path(manager._destination(plugin_id))
    with _open(path) as archive, tempfile.TemporaryDirectory(prefix='stage-', dir=target.parent) as stage:
        metadata, refmap, files = _read(archive)
        if metadata['id'] != plugin_id:
            raise ValueError('Elyx archive changed during installation')
        stage = Path(stage)
        incoming = stage / 'incoming'
        code = incoming
        code.mkdir(parents=True)
        for name, info in files.items():
            if _path(name).parts[0] == '.astra':
                raise ValueError('Elyx archive uses a reserved path')
            destination = code.joinpath(*_path(name).parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, destination.open('xb') as output:
                shutil.copyfileobj(source, output, 65536)
        internal = incoming / '.astra'
        internal.mkdir()
        (internal / 'manifest.json').write_text(json.dumps([metadata, refmap], ensure_ascii=False), encoding='utf-8')
        shutil.copyfile(path, internal / 'source.elyx')
        wrapper = stage / 'loader.py'
        wrapper.write_text(''.join(f'__{key}__ = {value!r}\n' for key, value in metadata.items())
                           + f'from elyx_compat import load_plugin\nElyxPlugin = load_plugin({plugin_id!r})\n', encoding='utf-8')
        previous = stage / 'previous'
        previous_wrapper = wrapper_path.read_bytes() if wrapper_path.is_file() else None
        previous_enabled = manager._read_enabled(plugin_id)
        if target.exists(): target.replace(previous)
        try:
            incoming.replace(target)
            manager.load_plugin_from_file(str(wrapper), structured=True)
        except Exception:
            unload(plugin_id)
            if target.exists(): shutil.rmtree(target)
            if previous.exists(): previous.replace(target)
            if previous_wrapper is None:
                wrapper_path.unlink(missing_ok=True)
            else:
                wrapper_path.write_bytes(previous_wrapper)
                manager.unload_plugin(plugin_id)
                manager._save_enabled(plugin_id, previous_enabled)
                manager.load_plugin(wrapper_path.name)
            raise
        shutil.rmtree(legacy_directory(manager, plugin_id), ignore_errors=True)


def _clear_namespace(namespace):
    for name in tuple(sys.modules):
        if name == namespace or name.startswith(namespace + '.'):
            sys.modules.pop(name, None)
    _scopes.pop(namespace, None)


def _local_import_root(name, roots):
    first = name.partition('.')[0]
    if first and first not in _SDK_IMPORT_ROOTS and any(
        (root / first).is_dir() or (root / (first + '.py')).is_file()
        or (root / (first + '.pyc')).is_file() for root in roots
    ):
        return first
    return None


class _Loader(importlib.abc.Loader):
    def __init__(self, loader, namespace):
        self.loader = loader
        self.namespace = namespace

    def create_module(self, spec):
        return self.loader.create_module(spec)

    def exec_module(self, module):
        namespace = self.namespace
        roots, api = _scopes[namespace]

        def importing(name, globals=None, locals=None, fromlist=(), level=0):
            if level == 0 and name == 'elyx':
                return api
            if level == 0:
                first = _local_import_root(name, roots)
                if first is not None:
                    loaded = builtins.__import__(namespace + '.' + name, globals, locals, fromlist, 0)
                    return loaded if fromlist else sys.modules[namespace + '.' + first]
            return builtins.__import__(name, globals, locals, fromlist, level)

        module.__dict__['__builtins__'] = dict(vars(builtins), __import__=importing)
        self.loader.exec_module(module)


class _Finder(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        namespace = fullname.split('.')[0]
        if namespace not in _scopes:
            return None
        roots, _ = _scopes[namespace]
        if fullname == namespace:
            entry = next((roots[0] / name for name in ('__init__.py', '__init__.pyc')
                          if (roots[0] / name).is_file()), None)
            if entry is None:
                return None
            spec = importlib.util.spec_from_file_location(fullname, entry, submodule_search_locations=[str(root) for root in roots])
        else:
            spec = importlib.machinery.PathFinder.find_spec(fullname, path)
        if spec is not None and isinstance(spec.loader, (importlib.machinery.SourceFileLoader, importlib.machinery.SourcelessFileLoader)):
            spec.loader = _Loader(spec.loader, namespace)
        return spec


def unload(plugin_id):
    namespace = _namespaces.pop(plugin_id, None)
    if namespace is not None:
        _clear_namespace(namespace)


def load_plugin(plugin_id):
    from base_plugin import BasePlugin
    from plugins_manager import PluginsManager
    folder = directory(PluginsManager, plugin_id)
    if folder.is_dir():
        manifest = folder / '.astra' / 'manifest.json'
        code = folder
    else:
        folder = legacy_directory(PluginsManager, plugin_id)
        manifest = folder / 'manifest.json'
        code = folder / 'code'
    metadata, refmap = json.loads(manifest.read_text(encoding='utf-8'))
    main = code.joinpath(*_path(refmap['main']).parts)
    unload(plugin_id)
    namespace = '_astra_elyx_' + hashlib.sha256(plugin_id.encode()).hexdigest()[:24]
    has_package = any((code / name).is_file() for name in ('__init__.py', '__init__.pyc'))
    if has_package and plugin_id.isidentifier() and plugin_id not in sys.modules:
        existing = importlib.util.find_spec(plugin_id)
        if existing is None or (existing.origin and Path(existing.origin) == Path(PluginsManager._destination(plugin_id))):
            namespace = plugin_id
    _namespaces[plugin_id] = namespace
    if not any(isinstance(finder, _Finder) for finder in sys.meta_path):
        sys.meta_path.insert(0, _Finder())
    roots = tuple(dict.fromkeys((code, main.parent)))
    try:
        _scopes[namespace] = (roots, _environment(plugin_id, metadata, refmap, code, namespace))
        if not has_package:
            package = ModuleType(namespace)
            package.__path__ = [str(root) for root in roots]
            sys.modules[namespace] = package
        entry_package = '.'.join((namespace, *main.relative_to(code).parent.parts))
        spec = importlib.util.spec_from_file_location(entry_package + '._entry', main)
        if spec is None or spec.loader is None:
            raise ImportError('Cannot load Elyx entry point')
        spec.loader = _Loader(spec.loader, namespace)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        # A library may clear its own namespace during entry initialization.
        spec.loader.exec_module(module)
        sys.modules[spec.name] = module
        for item in vars(module).values():
            if isinstance(item, type) and item is not BasePlugin and issubclass(item, BasePlugin):
                return item
        raise ImportError('Elyx entry point has no BasePlugin subclass')
    except Exception:
        unload(plugin_id)
        raise


def _environment(plugin_id, metadata, refmap, code, namespace):
    from elyx_environment import Asset, Assets, SettingsController, Strings
    api = ModuleType('elyx')
    api.metainfo = metadata.copy()
    api.refmap = refmap.copy()
    api.settings = SettingsController(plugin_id)
    api.Asset, api.Assets, api.SettingsController, api.Strings = Asset, Assets, SettingsController, Strings
    if 'assets' in refmap: api.assets = Assets(code.joinpath(*_path(refmap['assets']).parts))
    if 'strings' in refmap: api.strings = Strings(code.joinpath(*_path(refmap['strings']).parts))
    api.get_environment = lambda: {name: getattr(api, name) for name in ('metainfo', 'refmap', 'assets', 'strings', 'settings') if hasattr(api, name)}
    def import_module(name, package=None):
        if name.startswith('.'):
            return importlib.import_module(name, package or namespace)
        if _local_import_root(name, _scopes[namespace][0]) is not None:
            return importlib.import_module(namespace + '.' + name)
        return importlib.import_module(name, package)
    api.import_module = import_module
    return api
