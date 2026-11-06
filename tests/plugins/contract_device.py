"""Real Chaquopy/preferences/ART checks, with only synthetic plugin data."""
import json
import os
import tempfile
import threading
from java.lang import Integer, Thread
from java.util import HashMap
from base_plugin import BasePlugin, MethodHook, MethodReplacement, MenuItemData, MenuItemType
from plugins_manager import PluginsManager as manager
import plugin_runtime as runtime
from com.exteragram.messenger import ExteraConfig
from com.exteragram.messenger.plugins import PluginsController
from org.ghostgram.messenger.plugins import PluginMenuBridge
from hook_utils import get_private_field, set_private_field
from client_utils import run_on_queue, run_on_ui_thread, PLUGINS_QUEUE

if ExteraConfig.pluginsEngine:
    raise RuntimeError('Run contract checks while the external plugin engine is switched off')

count = 0
def check(actual, expected):
    global count
    assert actual == expected, (actual, expected)
    count += 1

saved = (manager._plugins, manager._plugins_dir, runtime._plugins, runtime._menu_index,
         runtime._interested_cache, ExteraConfig.pluginsSafeMode)
manager._plugins, runtime._plugins, runtime._menu_index, runtime._interested_cache = {}, {}, {}, {}
plugin = BasePlugin()
plugin.id = 'ghost_contract_' + os.urandom(5).hex()
plugin.enabled = True
controller = PluginsController.getInstance()
manager._plugins[plugin.id] = plugin
runtime.sync_record(plugin)
try:
    check(plugin.get_setting('missing', None), None)
    plugin.set_setting('bool', True)
    plugin.set_setting('int', 42)
    plugin.set_setting('str', 'value')
    check(plugin.get_setting('bool', False), True)
    check(plugin.get_setting('int', 0), 42)
    check(plugin.get_setting('str', ''), 'value')
    check(plugin.get_setting('bool', 'fallback'), 'fallback')
    plugin.set_setting('str', None)
    check(plugin.get_setting('str', None), None)
    plugin.set_setting('float', 1.25)
    check(plugin.get_setting('float', ''), '1.25')
    check(set_private_field(fixture, 'secret', Integer(11)), True)
    check(int(get_private_field(fixture, 'secret')), 11)
    check(get_private_field(fixture, 'missing'), None)
    check(set_private_field(fixture, 'missing', Integer(1)), False)

    values = HashMap()
    values.put('account', Integer(3))
    values.put('message', fixture)
    check(PluginMenuBridge.matches("account == 3 && message.text == 'fixture'", values), True)
    check(PluginMenuBridge.matches('account == 4', values), False)
    check(PluginMenuBridge.matches('account + 2', values), False)
    check(PluginMenuBridge.matches('this is invalid !', values), False)
    check(PluginMenuBridge.matches('account == 9', None), True)
    clicked = []
    item = MenuItemData(MenuItemType.MESSAGE_CONTEXT_MENU, 'Probe', lambda data: clicked.append(int(data.get('account'))),
                        condition='account == 3', priority=7)
    item_id = plugin.add_menu_item(item)
    check(bool(item_id), True)
    check(len(json.loads(runtime.menu_items_json(2, values))), 1)
    check(runtime.invoke_menu(plugin.id, item_id, values), True)
    check(clicked, [3])
    values.put('account', Integer(4))
    check(json.loads(runtime.menu_items_json(2, values)), [])
    check(runtime.remove_menu_item('other_owner', item_id), False)
    check(plugin.remove_menu_item(item_id), True)

    event = threading.Event()
    threads = []
    run_on_queue(lambda: (threads.append(str(Thread.currentThread().getName())), event.set()), PLUGINS_QUEUE)
    check(event.wait(10), True)
    check(threads, ['pluginsQueue'])
    event.clear()
    run_on_ui_thread(lambda: (threads.append(str(Thread.currentThread().getName())), event.set()))
    check(event.wait(10), True)
    check(threads[-1], 'main')

    check(fixture.call(3), 6)
    class Modify(MethodHook):
        def before_hooked_method(self, param): param.args[0] = Integer(7)
        def after_hooked_method(self, param): param.setResult(Integer(int(param.getResult()) + 1))
    handle = plugin.hook_method(fixture_method, Modify())
    check(handle is not None, True)
    check(handle.getHookedMethod().getName(), 'compute')
    check(fixture.call(3), 15)
    plugin.unhook_method(handle)
    check(fixture.call(3), 6)

    trace = []
    class Ordered(MethodHook):
        def __init__(self, name): self.name = name
        def before_hooked_method(self, param): trace.append('before' + self.name)
        def after_hooked_method(self, param): trace.append('after' + self.name)
    high = plugin.hook_method(fixture_method, Ordered('100'), 100)
    low = plugin.hook_method(fixture_method, Ordered('50'), 50)
    check(fixture.call(4), 8)
    check(trace, ['before100', 'before50', 'after50', 'after100'])
    plugin._cleanup_hooks()
    check(fixture.call(4), 8)

    class Early(MethodHook):
        def before_hooked_method(self, param): param.setResult(Integer(99))
    plugin.hook_method(fixture_method, Early())
    check(fixture.call(3), 99)
    plugin._cleanup_hooks()
    check(fixture.call(3), 6)

    class Replace(MethodReplacement):
        def replace_hooked_method(self, param): return None
    plugin.hook_method(fixture_text_method, Replace())
    check(fixture.textCall(), None)
    plugin._cleanup_hooks()
    check(fixture.textCall(), 'original')

    class Constructor(MethodHook):
        def before_hooked_method(self, param): param.args[0] = Integer(12)
        def after_hooked_method(self, param): param.thisObject.value += 1
    plugin.hook_method(fixture_constructor, Constructor())
    check(int(fixture.child(2).value), 13)
    plugin._cleanup_hooks()
    check(int(fixture.child(2).value), 2)
    ExteraConfig.pluginsSafeMode = True
    check(plugin.hook_method(fixture_method, Early()), None)
    check(runtime.menu_items_json(2, values), '[]')
    ExteraConfig.pluginsSafeMode = False

    # Source fixtures are created in cache; no real installed plugin is loaded.
    with tempfile.TemporaryDirectory(prefix='ghost_contract_', dir=str(context.getCacheDir().getAbsolutePath())) as folder:
        manager._plugins_dir = folder
        source = os.path.join(folder, 'input.plugin')
        text = '__id__ = "' + plugin.id + '_lifecycle"\n__name__ = "Lifecycle"\nclass P(BasePlugin):\n    pass\n'
        with open(source, 'w', encoding='utf-8') as output: output.write(text)
        metadata = manager.validate_plugin_from_file(source)
        check(metadata['id'], plugin.id + '_lifecycle')
        instance = manager.load_plugin_from_file(source)
        check((instance.enabled, instance.initialized), (False, False))
        check(os.path.exists(source), True)
        manager.set_plugin_enabled(instance.id, True)
        manager.set_plugin_enabled(instance.id, False)
        manager.set_plugin_enabled(instance.id, True)
        check(manager._plugins[instance.id] is instance, True)
        manager.unload_plugin(instance.id)
        ExteraConfig.preferences.edit().remove('plugin_enabled_' + instance.id).apply()
    report = json.dumps({'checks': count, 'runtime': 'real Android Chaquopy / SharedPreferences / MVEL / LSPlant', 'synthetic_data_only': True})
finally:
    ExteraConfig.pluginsSafeMode = saved[5]
    plugin._cleanup_hooks()
    controller.clearPluginSettingsPreferences(plugin.id)
    for test_id in list(manager._plugins):
        if test_id.startswith(plugin.id):
            controller.clearPluginSettingsPreferences(test_id)
            controller.invalidatePluginSettings(test_id)
            ExteraConfig.preferences.edit().remove('plugin_enabled_' + test_id).apply()
    manager._plugins, manager._plugins_dir, runtime._plugins, runtime._menu_index, runtime._interested_cache = saved[:5]
