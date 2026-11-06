"""Loopback-only development protocol compatible with the reference SDK."""
import json
import os
import socket
import sys
import tempfile
import threading
import traceback
from enum import Enum
from base_plugin import BasePlugin, AppEvent
from plugins_manager import PluginsManager


class DebuggerPlatform(Enum):
    PyCharm = 'pycharm'
    VSCode = 'vscode'


class DebuggerEventListener(BasePlugin):
    def on_app_event(self, event):
        if DevServer._active_platform == 'pycharm':
            if event == AppEvent.PAUSE: DevServer._stop_pycharm_remote_debugging()
            elif event == AppEvent.RESUME: DevServer._setup_pycharm_remote_debugger(*DevServer._debug_target)


class DevServer:
    _is_running = False
    _server_thread = None
    _server_socket = None
    _client_socket = None
    _active_debugging = False
    _active_platform = None
    _debug_target = None

    @classmethod
    def start_server(cls, host=None, port=None):
        if cls._server_thread and cls._server_thread.is_alive(): return cls._server_thread
        host, port = host or '127.0.0.1', port or 42690
        if host != '127.0.0.1': raise ValueError('Development server is restricted to loopback')
        cls._is_running = True
        cls._server_thread = threading.Thread(target=cls._server_thread_function, args=(host, int(port)), daemon=True)
        cls._server_thread.start()
        return cls._server_thread

    @classmethod
    def _server_thread_function(cls, host, port):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
                cls._server_socket = server
                server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                server.bind((host, port))
                server.listen(1)
                server.settimeout(0.5)
                while cls._is_running:
                    try: client, address = server.accept()
                    except socket.timeout: continue
                    with client:
                        cls._client_socket = client
                        client.settimeout(600)
                        cls._handle_client(client)
                    cls._client_socket = None
        except OSError:
            if cls._is_running: cls._log(traceback.format_exc())
        finally:
            cls._is_running = False
            cls._server_socket = cls._client_socket = None

    @classmethod
    def _handle_client(cls, client_socket):
        pending = b''
        while cls._is_running:
            try:
                data = client_socket.recv(4096)
                if not data: return
                pending = cls._process_buffer(pending + data, client_socket)
            except OSError: return

    @classmethod
    def _process_buffer(cls, buffer, client_socket):
        text = buffer.decode('utf-8', errors='replace')
        decoder, position = json.JSONDecoder(), 0
        while position < len(text):
            while position < len(text) and text[position].isspace(): position += 1
            if position == len(text): break
            try: command, end = decoder.raw_decode(text, position)
            except json.JSONDecodeError: break
            cls._process_command(command, client_socket)
            position = end
        return buffer[len(text[:position].encode('utf-8')):]

    @classmethod
    def _process_command(cls, command, client_socket):
        handler = getattr(cls, '_handle_' + str(command.get('@', '')), None)
        try: result = handler(command) if handler else None
        except Exception as error: result = {'error': str(error)}
        response = {'#': command.get('#')}
        response.update(result or {})
        client_socket.sendall(json.dumps(response).encode('utf-8'))

    @staticmethod
    def _handle_ping(command): return {'pong': True}

    @staticmethod
    def _handle_get_plugins(command):
        return {plugin_id: {'name': p.name, 'description': p.description, 'author': p.author,
                            'version': p.version, 'error': p.error_message, 'enabled': p.enabled}
                for plugin_id, p in list(PluginsManager._plugins.items())}

    @staticmethod
    def _validate_id(command):
        plugin_id = command.get('plugin_id')
        if not plugin_id: return None, {'error': 'Missing plugin_id'}
        if plugin_id not in PluginsManager._plugins: return None, {'error': 'Plugin not found'}
        return plugin_id, None

    @classmethod
    def _handle_enable_plugin(cls, command): return cls._toggle(command, True)
    @classmethod
    def _handle_disable_plugin(cls, command): return cls._toggle(command, False)
    @classmethod
    def _toggle(cls, command, value):
        plugin_id, error = cls._validate_id(command)
        if error: return error
        from client_utils import run_on_ui_thread
        from com.exteragram.messenger.plugins import PluginsController
        run_on_ui_thread(lambda: PluginsController.getInstance().setPluginEnabled(plugin_id, value, None))

    @classmethod
    def _handle_reload_plugin(cls, command):
        plugin_id, error = cls._validate_id(command)
        if error: return error
        from client_utils import run_on_ui_thread
        run_on_ui_thread(lambda: cls._reload_plugin(plugin_id))

    @staticmethod
    def _reload_plugin(plugin_id):
        from client_utils import run_on_queue, PLUGINS_QUEUE
        from com.exteragram.messenger.plugins import PluginsController
        def reload():
            try:
                PluginsManager.load_plugin(plugin_id + '.py')
                PluginsController.getInstance().refresh()
                PluginsController.getInstance().loadPluginSettings(plugin_id)
            except Exception:
                DevServer._log(traceback.format_exc())
                PluginsController.getInstance().invalidatePluginSettings(plugin_id)
        run_on_queue(reload, PLUGINS_QUEUE)

    @classmethod
    def _handle_remove_plugin(cls, command):
        plugin_id, error = cls._validate_id(command)
        if error: return error
        from client_utils import run_on_ui_thread
        from com.exteragram.messenger.plugins import PluginsController
        run_on_ui_thread(lambda: PluginsController.getInstance().deletePlugin(plugin_id, None))

    @classmethod
    def _handle_write_plugin(cls, command):
        plugin_id, content = command.get('plugin_id'), command.get('content')
        if not plugin_id or not content: return {'error': 'Missing plugin_id or content'}
        if any(char in plugin_id for char in ('/', '\\', '\x00')) or plugin_id in ('.', '..'):
            return {'error': 'Plugin ID must be a filename'}
        existing = plugin_id in PluginsManager._plugins
        path = os.path.join(PluginsManager._plugins_dir if existing else tempfile.gettempdir(), plugin_id + '.py')
        try:
            with open(path, 'w', encoding='utf-8') as output: output.write(content)
        except OSError as error: return {'error': str(error)}
        if not existing:
            from client_utils import run_on_queue, PLUGINS_QUEUE
            def activate():
                try:
                    PluginsManager.load_plugin_from_file(path)
                    PluginsManager.set_plugin_enabled(plugin_id, True)
                except Exception: cls._log(traceback.format_exc())
            run_on_queue(activate, PLUGINS_QUEUE)

    @classmethod
    def _handle_start_debugger(cls, command):
        if 'debugger_event_listener' in PluginsManager._plugins: return {'status': 'Already running'}
        host, port, platform = command.get('host'), command.get('port'), command.get('platform')
        if not host or not port or not platform: return {'error': 'Missing required parameters'}
        if platform not in ('pycharm', 'vscode'): return {'error': 'Invalid platform: ' + str(platform)}
        from client_utils import run_on_ui_thread
        run_on_ui_thread(lambda: cls.setup_remote_debugging(host, port, platform))
        listener = DebuggerEventListener()
        listener.id = 'debugger_event_listener'
        listener.enabled = listener.initialized = True
        PluginsManager._plugins[listener.id] = listener
        PluginsManager._publish(listener)
        cls._active_debugging, cls._active_platform = True, platform
        cls._debug_target = (host, int(port))

    @classmethod
    def _handle_stop_debugger(cls, command):
        platform = command.get('platform')
        if not platform: return {'error': 'Missing platform'}
        if platform not in ('pycharm', 'vscode'): return {'error': 'Invalid platform: ' + str(platform)}
        from client_utils import run_on_ui_thread
        run_on_ui_thread(lambda: cls.stop_remote_debugging(platform))
        PluginsManager.unload_plugin('debugger_event_listener')
        cls._active_debugging, cls._active_platform = False, None

    @classmethod
    def setup_remote_debugging(cls, host, port, platform):
        return cls._setup_pycharm_remote_debugger(host, port) if platform == 'pycharm' else cls._setup_vscode_remote_debugger(host, port)

    @classmethod
    def stop_remote_debugging(cls, platform):
        return cls._stop_pycharm_remote_debugging() if platform == 'pycharm' else cls._stop_vscode_remote_debugger()

    @classmethod
    def _setup_pycharm_remote_debugger(cls, host, port):
        try:
            from org.telegram.messenger import FileLoader
            path = str(FileLoader.getDirectory(FileLoader.MEDIA_DIR_FILES).getAbsolutePath())
            egg = os.path.join(path, 'debugger', 'pydevd-pycharm.egg')
            if egg not in sys.path: sys.path.append(egg)
            import pydevd_pycharm
            pydevd_pycharm.settrace(host, port=int(port), stdoutToServer=True, stderrToServer=True, suspend=False)
            return True
        except Exception: cls._log(traceback.format_exc()); return False

    @classmethod
    def _setup_vscode_remote_debugger(cls, host, port):
        try:
            os.environ.setdefault('PYDEVD_USE_CYTHON', 'NO')
            os.environ.setdefault('PYDEVD_USE_FRAME_EVAL', 'NO')
            import debugpy
            debugpy.listen((host, int(port)), in_process_debug_adapter=True)
            return True
        except Exception: cls._log(traceback.format_exc()); return False

    @classmethod
    def _stop_pycharm_remote_debugging(cls):
        try:
            import pydevd_pycharm
            pydevd_pycharm.stoptrace()
            return True
        except Exception: cls._log(traceback.format_exc()); return False

    @classmethod
    def _stop_vscode_remote_debugger(cls):
        try:
            import debugpy._vendored.force_pydevd
            import pydevd
            pydevd.stoptrace()
            return True
        except Exception: cls._log(traceback.format_exc()); return False

    @classmethod
    def stop_server(cls):
        if not cls._is_running: return False
        cls._is_running = False
        if cls._active_platform: cls.stop_remote_debugging(cls._active_platform)
        PluginsManager.unload_plugin('debugger_event_listener')
        for stream in (cls._client_socket, cls._server_socket):
            if stream:
                try: stream.shutdown(socket.SHUT_RDWR)
                except OSError: pass
                stream.close()
        if cls._server_thread is not None and cls._server_thread is not threading.current_thread():
            cls._server_thread.join(timeout=1)
        cls._server_thread = None
        cls._active_debugging, cls._active_platform = False, None
        return True

    @staticmethod
    def _log(text):
        from plugin_runtime import log
        log('dev_server', text)
