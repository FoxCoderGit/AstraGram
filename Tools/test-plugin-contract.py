"""Reference contract checks with a fake Java host; no user plugins or accounts."""
import builtins
import dataclasses
import json
import sys
import tempfile
import types
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / 'TMessagesProj_Ghost/src/main/python'))


def module(name, **values):
    obj = types.ModuleType(name)
    obj.__dict__.update(values)
    sys.modules[name] = obj
    if '.' in name:
        parent, child = name.rsplit('.', 1)
        if parent not in sys.modules: module(parent)
        setattr(sys.modules[parent], child, obj)
    return obj


class Preferences:
    def __init__(self): self.data = {}
    def edit(self): return self
    def getBoolean(self, key, default): return self.data.get(key, default)
    def putBoolean(self, key, value): self.data[key] = value; return self
    def apply(self): pass


prefs = Preferences()
config = types.SimpleNamespace(preferences=prefs, pluginsSafeMode=False)
module('com.exteragram.messenger', ExteraConfig=config)
module('org.telegram.messenger', BuildVars=types.SimpleNamespace(BUILD_VERSION_STRING='12.10.6'),
       FileLog=types.SimpleNamespace(d=lambda value: None))
module('org.ghostgram.messenger.plugins', PluginMenuBridge=types.SimpleNamespace(notifyChanged=lambda: None))
import base_plugin as base
import plugin_runtime as runtime
from plugins_manager import PluginsManager as manager

count = 0


def check(actual, expected):
    global count
    assert actual == expected, (actual, expected)
    count += 1


def plugin(plugin_id, strategy, field, value):
    result = base.HookResult(strategy=strategy, **{field: value})
    instance = base.BasePlugin()
    instance.id, instance.enabled = plugin_id, True
    for name in ('pre_request_hook', 'post_request_hook', 'on_update_hook', 'on_updates_hook', 'on_send_message_hook'):
        setattr(instance, name, lambda *args: result)
    return instance


for field, method, args in [
    ('request', 'execute_pre_request_hook', ('TL_test', 0, 'original')),
    ('update', 'execute_on_update_hook', ('TL_test', 0, 'original')),
    ('updates', 'execute_on_updates_hook', ('TL_test', 0, 'original')),
    ('params', 'execute_on_send_message_hook', (0, 'original')),
]:
    for strategy in base.HookStrategy:
        manager._plugins = {'first': plugin('first', strategy, field, 'changed')}
        result = getattr(manager, method)(['first'], *args)
        check(getattr(result, field), None if strategy == base.HookStrategy.CANCEL else 'original' if strategy == base.HookStrategy.DEFAULT else 'changed')
    manager._plugins = {'first': plugin('first', base.HookStrategy.MODIFY_FINAL, field, None),
                        'later': plugin('later', base.HookStrategy.MODIFY, field, 'later')}
    check(getattr(getattr(manager, method)(['first', 'later'], *args), field), 'later')

manager._plugins = {'first': plugin('first', base.HookStrategy.CANCEL, 'response', 'changed'),
                    'later': plugin('later', base.HookStrategy.MODIFY, 'response', 'later')}
check(manager.execute_post_request_hook(['first', 'later'], 'TL_test', 0, 'old', 'error').response, 'later')
manager._plugins['first'] = plugin('first', base.HookStrategy.MODIFY_FINAL, 'response', None)
result = manager.execute_post_request_hook(['first', 'later'], 'TL_test', 0, 'old', 'error')
check((result.response, result.error), ('old', 'error'))
check(manager._parse_metadata(b'__id__="x"\n__name__="X"\n__version__="1"+"2"').version, None)
check(manager._parse_metadata(b'__id__="x"\n__name__="X"\n__version__="1"\n__version__="2"').version, '2')
modern = manager._parse_metadata(b'__id__: str="x"\n__name__="X"\n__app_version__=">=12.5.1,<13"\n__sdk_version__=">=1.4.4.3"\n__requirements__=["example>=2"]')
check(modern.sdk_version, '>=1.4.4.3')
check(modern.requirements, ['example>=2'])
manager._check_min_version(modern)
try:
    manager._check_min_version(dataclasses.replace(modern, app_version='>=13'))
    raise AssertionError('accepted unsupported app version')
except base.PluginError:
    pass

builtins._ghost_plugin_trace = []
code = '''import builtins
__id__ = "probe"
__name__ = "Probe"
builtins._ghost_plugin_trace.append("module")
class Probe(BasePlugin):
    def __init__(self):
        super().__init__()
        builtins._ghost_plugin_trace.append("constructor")
    def on_plugin_load(self): builtins._ghost_plugin_trace.append("load")
    def on_plugin_unload(self): builtins._ghost_plugin_trace.append("unload")
'''
with tempfile.TemporaryDirectory() as directory:
    manager._plugins = {}
    runtime._plugins.clear()
    manager._plugins_dir = directory
    source = Path(directory) / 'input.plugin'
    source.write_text(code, encoding='utf-8')
    manager.validate_plugin_from_file(str(source))
    check(builtins._ghost_plugin_trace, [])
    manager.load_plugin_from_file(str(source))
    check({name: builtins._ghost_plugin_trace.count(name) for name in ('module', 'constructor', 'load', 'unload')},
          {'module': 2, 'constructor': 3, 'load': 2, 'unload': 2})
    instance = manager._plugins['probe']
    check((instance.enabled, instance.initialized, source.exists()), (False, False, True))
    manager.set_plugin_enabled('probe', True)
    manager.set_plugin_enabled('probe', False)
    manager.set_plugin_enabled('probe', True)
    check(manager._plugins['probe'] is instance, True)
    saved = (Path(directory) / 'probe.py').read_bytes()
    source.write_text(code.replace('def on_plugin_load(self): builtins._ghost_plugin_trace.append("load")',
                                   'def on_plugin_load(self): raise RuntimeError("bad update")'), encoding='utf-8')
    try: manager.load_plugin_from_file(str(source))
    except base.PluginError: pass
    else: raise AssertionError('bad update accepted')
    check((Path(directory) / 'probe.py').read_bytes(), saved)
    check(manager._plugins['probe'] is instance and instance.enabled, True)
    manager.shutdown()

runtime._plugins.clear()
for plugin_id in ('z', 'a'):
    instance = base.BasePlugin()
    instance.id, instance.enabled = plugin_id, True
    instance.add_hook('TL_test', priority=5)
    instance.add_hook('TL_test', priority=99)
    instance.add_hook('TL_', match_substring=True, priority=3)
    runtime.sync_record(instance)
check([record['instance'].id for record in runtime._matching_records('TL_test')], ['a', 'z'])
check(runtime._plugins['a']['instance']._hook_options[('TL_test', False)], 5)
check(base.AppEvent.START.value, 'app_start')
check(base.MenuItemType.DRAWER_MENU.value, 'drawer_menu')

from markdown_utils import parse_markdown
for vector in json.loads((ROOT / 'tests/plugins/fixtures/markdown-vectors.json').read_text(encoding='utf-8')):
    try:
        parsed = parse_markdown(vector['input'])
        actual = dict(input=vector['input'], text=parsed.text,
                      entities=[dict(dataclasses.asdict(entity), type=entity.type.value) for entity in parsed.entities])
    except Exception as error:
        actual = dict(input=vector['input'], error_type=type(error).__name__, error=str(error))
    check(actual, vector)

from dev_server import DevServer


class Socket:
    def __init__(self): self.output = b''
    def sendall(self, value): self.output += value


manager._plugins.clear()
for vector in json.loads((ROOT / 'tests/plugins/fixtures/dev-protocol-vectors.json').read_text(encoding='utf-8')):
    socket = Socket()
    if 'input' in vector:
        DevServer._process_command(vector['input'], socket)
        check(json.loads(socket.output), vector['output'])
    else:
        pending = DevServer._process_buffer(vector['input_raw'].encode(), socket)
        check(socket.output.decode(), vector['output_raw'])
        check(pending.decode(), vector['remaining'])

from ui.settings import Switch, Selector, Input, Text, Header, Divider
check([entry.type for entry in (Switch('s', 'S', False), Selector('k', 'K', 0, ['x']), Input('i', 'I'), Text('T'), Header('H'), Divider())],
      ['switch', 'selector', 'input', 'text', 'header', 'divider'])


class SettingsHost:
    def __init__(self):
        self.scopes = {}

    def readPluginSettingsJson(self, plugin_id):
        return json.dumps(self.scopes.get(plugin_id, {}))

    def writePluginSettingJson(self, plugin_id, key, value):
        self.scopes.setdefault(plugin_id, {})[key] = json.loads(value)

    def replacePluginSettingsJson(self, plugin_id, values):
        self.scopes[plugin_id] = json.loads(values)

    def clearPluginSettingsPreferences(self, plugin_id):
        self.scopes.pop(plugin_id, None)


settings_host = SettingsHost()
settings_host.removeHooksByPluginId = lambda plugin_id: None
settings_host.invalidatePluginSettings = lambda plugin_id: None
module('com.exteragram.messenger.plugins', PluginsController=types.SimpleNamespace(getInstance=lambda: settings_host))
import plugin_settings
plugin_settings.set_setting('first', 'nested', {'items': [True, None, 2.5]})
plugin_settings.set_setting('second', 'active', True)
check(runtime.get_setting('first', 'nested'), {'items': [True, None, 2.5]})
check(runtime.get_setting('first', 'missing', 10), 10)
check(runtime.import_settings('first', {'count': 1 << 40}), True)
check(runtime.export_settings('first'), {'count': 1 << 40})
plugin_settings.clear_settings('first')
check(plugin_settings.get_all_settings('first'), {})
check(plugin_settings.get_all_settings('second'), {'active': True})

with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    archive = root / 'library.elyx'
    manager.init(root / 'plugins')
    host_bulletin = module('ui.bulletin', BulletinHelper=object())
    entry = '''import sys
for name in list(sys.modules):
    if name == 'astratestlib' or name.startswith('astratestlib.'):
        del sys.modules[name]
from astratestlib.src.api import VALUE
from astratestlib.src.ui.bulletin import SDK_HELPERS
from base_plugin import BasePlugin
class Library(BasePlugin):
    def on_plugin_load(self):
        assert self.id == 'astratestlib'
        self.value = VALUE
        self.sdk_helpers = SDK_HELPERS
'''
    def write_library(main):
        with zipfile.ZipFile(archive, 'w') as output:
            output.writestr('refmap.json', json.dumps({'metainfo': 'metainfo.json', 'main': 'src/main.py'}))
            output.writestr('metainfo.json', json.dumps({'id': 'astratestlib', 'name': 'Test library'}))
            output.writestr('__init__.py', 'from .src.api import VALUE')
            output.writestr('src/api.py', 'VALUE = 42')
            output.writestr('src/main.py', main)
            output.writestr('src/ui/__init__.py', '')
            output.writestr('src/ui/bulletin.py', '''from ui.bulletin import BulletinHelper
from elyx import import_module
SDK_HELPERS = (BulletinHelper, import_module('ui.bulletin').BulletinHelper)
''')

    write_library(entry)
    check(runtime.install_file(archive), True)
    check(sys.modules['astratestlib'].VALUE, 42)
    check(manager._plugins['astratestlib'].initialized, False)
    manager.set_plugin_enabled('astratestlib', True)
    check(manager._plugins['astratestlib'].value, 42)
    check(manager._plugins['astratestlib'].sdk_helpers, (host_bulletin.BulletinHelper,) * 2)
    write_library(entry + '\nimport missing_astra_test_library')
    check(runtime.install_file(archive), False)
    check(runtime.last_install_error().splitlines()[0],
          "ModuleNotFoundError: No module named 'missing_astra_test_library'")
    check(manager._plugins['astratestlib'].value, 42)
    manager.shutdown()
    manager.init(root / 'plugins')
    check(manager._plugins['astratestlib'].value, 42)
    manager.shutdown()

from unittest.mock import Mock
proxy = type('Proxy', (), {})
module('java', dynamic_proxy=lambda interface: proxy)
module('java.lang', Runnable=proxy)
module('android.view', View=types.SimpleNamespace(OnClickListener=proxy, OnLongClickListener=proxy))
module('client_utils', run_on_queue=lambda callback: callback())
sys.modules['org.telegram.messenger'].AndroidUtilities = object()
sys.modules['org.telegram.messenger'].ApplicationLoader = object()
from android_utils import OnClickListener, OnLongClickListener
view = object()
callback = Mock(return_value=False)
OnClickListener(callback).onClick(view)
check(callback.call_args.args, (view,))
check(OnLongClickListener(callback).onLongClick(view), False)
check(callback.call_args.args, (view,))
print('PASS:', count, 'plugin lifecycle / metadata / callback contract checks')
