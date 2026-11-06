"""Regression probes invoked only by the debug instrumentation runner."""
def run(context):
    from base_plugin import BasePlugin, HookResult, HookStrategy, MethodHook, hook_filters, HookFilter
    from client_utils import get_hook_account
    from ui.settings import Switch, Selector, Custom, SimpleSettingFactory
    from extera_utils.classes import Base, java_subclass, joverride
    from android.widget import TextView
    from org.telegram.tgnet import TLRPC
    from org.telegram.tgnet.tl import TL_account
    from java import jclass
    import plugin_runtime as runtime
    reports = []
    assert Switch('enabled', 'Enabled', True).key == 'enabled'
    assert Selector('choice', 'Choice', 1, ['a', 'b']).items == ['a', 'b']
    reports.append('settings signatures')

    class ProbePlugin(BasePlugin):
        def pre_request_hook(self, name, account, request):
            assert get_hook_account() == account
            replacement = TL_account.updateStatus()
            replacement.offline = True
            return HookResult(HookStrategy.MODIFY, request=replacement)
        def post_request_hook(self, name, account, response, error):
            return HookResult(HookStrategy.MODIFY, response='modified')

    plugin = ProbePlugin()
    plugin.add_hook('TL_account_', match_substring=True, priority=5)
    saved = runtime._plugins
    runtime._plugins = {'probe': {'instance': plugin, 'enabled': True, 'meta': {'id': 'probe'}, 'error': ''}}
    try:
        request = TL_account.updateStatus()
        assert runtime.transform_pre_request(1, request).offline
        assert runtime.transform_post_request(1, request, 'original', None)[1] == 'modified'
        assert get_hook_account() is None
        reports.append('request/response replacement, substring matching, account isolation')
    finally: runtime._plugins = saved

    probe_class = jclass('org.ghostgram.messenger.plugins.GhostDiagnostics$HookProbe')
    probe = probe_class()
    class Hook(MethodHook):
        @hook_filters(HookFilter.RESULT_NOT_NULL)
        def after_hooked_method(self, param): param.setResult('hooked')
    handle = plugin.hook_method(probe_class.getClass().getDeclaredMethod('value'), Hook())
    assert str(probe.value()) == 'hooked'
    plugin._cleanup_hooks()
    assert str(probe.value()) == 'original'
    reports.append('native Java hook, filter, automatic unhook')

    @java_subclass(TextView)
    class Label(Base):
        @joverride
        def getContentDescription(self): return 'Python view'
    label = Label(context)
    assert str(label.java.getContentDescription()) == 'Python view'
    reports.append('DexMaker Android subclass')
    factory = SimpleSettingFactory(lambda ctx, *args: TextView(ctx), lambda view, item, *args: view.setText('Custom row'))
    row = Custom(factory=factory.instance.java)
    record = {'instance': plugin, 'settings_cache': [row], 'meta': {'id': 'probe'}}
    runtime._plugins = {'probe': record}
    try:
        view = runtime.custom_setting_view('probe', -1, 0, context, None, 0, 0, None, None)
        assert str(view.getText()) == 'Custom row'
        reports.append('custom settings view creation and binding')
    finally: runtime._plugins = saved
    return 'PASS: ' + '; '.join(reports)
