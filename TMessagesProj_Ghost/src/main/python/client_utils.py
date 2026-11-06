from java import dynamic_proxy
from java.lang import Runnable
from org.telegram.messenger import AndroidUtilities, Utilities
from org.telegram.messenger import UserConfig, MessagesController, AccountInstance, SendMessagesHelper, FileLoader, MessagesStorage
from org.telegram.tgnet import RequestDelegate
from java.lang import Long
from org.telegram.ui import LaunchActivity
from contextlib import contextmanager
import threading

_account_context = threading.local()
STAGE_QUEUE = 'stageQueue'
GLOBAL_QUEUE = 'globalQueue'
CACHE_CLEAR_QUEUE = 'cacheClearQueue'
SEARCH_QUEUE = 'searchQueue'
PHONE_BOOK_QUEUE = 'phoneBookQueue'
THEME_QUEUE = 'themeQueue'
EXTERNAL_NETWORK_QUEUE = 'externalNetworkQueue'
PLUGINS_QUEUE = 'pluginsQueue'

def get_selected_account(): return int(UserConfig.selectedAccount)
def get_hook_account(): return getattr(_account_context, 'account', None)
def _resolve_account(account=None):
    return int(account if account is not None else UserConfig.selectedAccount)

@contextmanager
def account_scope(account):
    previous = get_hook_account()
    _account_context.account = account
    try: yield
    finally: _account_context.account = previous

def get_queue_by_name(name): return getattr(Utilities, str(name), None)


class _Runnable(dynamic_proxy(Runnable)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn
        self.account = get_hook_account()

    def run(self):
        try:
            with account_scope(self.account): self.fn()
        except Exception:
            import traceback
            from android_utils import log
            log(traceback.format_exc())


class RequestCallback(dynamic_proxy(RequestDelegate)):
    """ExteraGram-compatible wrapper for Telegram network request callbacks."""
    def __init__(self, callback, account=None):
        super().__init__()
        self.callback = callback
        self.account = _resolve_account(account)

    def run(self, response, error):
        if self.callback is not None:
            try:
                with account_scope(self.account): self.callback(response, error)
            except Exception:
                import traceback
                from android_utils import log
                log(traceback.format_exc())


def run_on_ui_thread(fn, delay=0):
    AndroidUtilities.runOnUIThread(_Runnable(fn), int(delay))


def run_on_queue(fn, queue_name=PLUGINS_QUEUE, delay=0, queue=None):
    target = queue or (get_queue_by_name(queue_name) if isinstance(queue_name, str) else queue_name)
    if target is None:
        from android_utils import log
        log('Unknown dispatch queue: ' + str(queue_name))
        return
    target.postRunnable(_Runnable(fn), int(delay))


def get_last_fragment():
    return LaunchActivity.getSafeLastFragment()


def get_messages_controller(account=None):
    return MessagesController.getInstance(_resolve_account(account))


def get_account_instance(account=None):
    return AccountInstance.getInstance(_resolve_account(account))


def get_user_config(account=None):
    return UserConfig.getInstance(_resolve_account(account))


def get_send_messages_helper(account=None):
    return SendMessagesHelper.getInstance(_resolve_account(account))


def get_file_loader(account=None):
    return FileLoader.getInstance(_resolve_account(account))


def get_messages_storage(account=None):
    return MessagesStorage.getInstance(_resolve_account(account))


def send_request(request, callback=None, account=None, flags=0):
    manager = get_account_instance(account).getConnectionsManager()
    delegate = callback
    if delegate is None:
        delegate = RequestCallback(lambda response, error: None, account)
    elif not hasattr(delegate, "run"):
        delegate = RequestCallback(delegate, account)
    return manager.sendRequest(request, delegate, int(flags or 0))


def send_text(peer, text, account=None, **kwargs):
    """Send text to a Telegram InputPeer or numeric dialog ID.

    ExteraGram plugins receive an InputPeer from the send hook. Converting that
    Java object to ``str`` produced a class description rather than its dialog
    ID, so answer delivery failed after the AI request had completed.
    """
    selected = _resolve_account(account)
    dialog_id = _peer_id(peer)
    params = SendMessagesHelper.SendMessageParams.of(str(text), int(dialog_id))
    SendMessagesHelper.getInstance(selected).sendMessage(params)
    return True


def _peer_id(peer):
    if isinstance(peer, (int, float)):
        return int(peer)
    for field, sign in (("user_id", 1), ("chat_id", -1), ("channel_id", -1)):
        value = getattr(peer, field, None)
        if value:
            return sign * int(value)
    return int(str(peer))


def send_message(params, parse_mode=None, *, account=None):
    if not isinstance(params, dict):
        run_on_ui_thread(lambda: get_send_messages_helper(account).sendMessage(params))
        return
    from java.util import ArrayList, HashMap
    entities = None
    if params.get('entities'):
        entities = ArrayList()
        for entity in params['entities']: entities.add(entity)
    message_params = params.get('params')
    if isinstance(message_params, dict):
        converted = HashMap()
        for key, value in message_params.items(): converted.put(str(key), str(value))
        message_params = converted
    message = SendMessagesHelper.SendMessageParams.of(
        params.get('message'), params.get('caption'), params.get('location'), params.get('photo'),
        params.get('videoEditedInfo'), params.get('user'), params.get('document'), params.get('game'),
        params.get('poll'), params.get('invoice'), _peer_id(params.get('peer')), params.get('path'),
        params.get('replyToMsg'), params.get('replyToTopMsg'), params.get('webPage'),
        bool(params.get('searchLinks', True)), params.get('retryMessageObject'), entities,
        params.get('replyMarkup'), message_params, bool(params.get('notify', True)),
        int(params.get('scheduleDate', 0)), int(params.get('ttl', 0)), params.get('parentObject'),
        params.get('sendAnimationData'), bool(params.get('updateStickersOrder', False)),
        bool(params.get('hasMediaSpoilers', False)))
    for name in ('replyToStoryItem', 'sendingStory', 'replyQuote'):
        if params.get(name): setattr(message, name, params[name])
    for name in ('quick_reply_shortcut', 'quick_reply_shortcut_id', 'effect_id', 'stars'):
        if params.get(name) is not None: setattr(message, name, params[name])
    message.invert_media = bool(params.get('invert_media', False))
    message.mediaWebPage = params.get('mediaWebPage')
    selected = _resolve_account(account)
    run_on_ui_thread(lambda: get_send_messages_helper(selected).sendMessage(message))


def edit_message(message_obj, text=None, file_path=None, with_spoiler=False, *, account=None, parse_mode=None, **kwargs):
    if file_path is not None: raise NotImplementedError('Media replacement is not supported yet')
    if text is None: raise ValueError('text is required')
    return get_send_messages_helper(account).editMessage(message_obj, str(text),
        bool(kwargs.get('search_links', True)), get_last_fragment(), None,
        int(kwargs.get('schedule_date', 0)), 0)


# Media sending is exposed explicitly so plugins can feature-detect it. A later
# bridge can provide Telegram's full media preparation pipeline.
def send_document(peer, file_path, caption='', *, account=None, parse_mode=None, **kwargs):
    from java.util import ArrayList
    import mimetypes, os
    if not os.path.isfile(file_path): raise FileNotFoundError(file_path)
    paths = ArrayList()
    paths.add(str(file_path))
    SendMessagesHelper.prepareSendingDocuments(get_account_instance(account), paths, paths, None,
        str(caption), mimetypes.guess_type(file_path)[0] or 'application/octet-stream',
        _peer_id(peer), kwargs.get('reply_to_msg'), kwargs.get('reply_to_top_msg'), None, None, None,
        bool(kwargs.get('notify', True)), int(kwargs.get('schedule_date', 0)), None,
        SendMessagesHelper.SendMessageChatArguments.EMPTY, 0, False, 0)
    return True

def send_photo(peer, file_path, caption='', high_quality=False, *, account=None, parse_mode=None, **kwargs):
    import os
    if not os.path.isfile(file_path): raise FileNotFoundError(file_path)
    SendMessagesHelper.prepareSendingPhoto(get_account_instance(account), str(file_path), None, _peer_id(peer),
        kwargs.get('reply_to_msg'), kwargs.get('reply_to_top_msg'), None, str(caption), None, None, None,
        0, None, bool(kwargs.get('notify', True)), int(kwargs.get('schedule_date', 0)), 0,
        SendMessagesHelper.SendMessageChatArguments.EMPTY)
    return True

def send_audio(peer, file_path, caption='', **kwargs): return send_document(peer, file_path, caption, **kwargs)
def send_video(peer, file_path, caption='', **kwargs): return send_document(peer, file_path, caption, **kwargs)

def _controller(name, account=None):
    from java import jclass
    return jclass('org.telegram.messenger.' + name).getInstance(_resolve_account(account))

def get_connections_manager(account=None): return get_account_instance(account).getConnectionsManager()
def get_contacts_controller(account=None): return _controller('ContactsController', account)
def get_media_data_controller(account=None): return _controller('MediaDataController', account)
def get_location_controller(account=None): return _controller('LocationController', account)
def get_notifications_controller(account=None): return _controller('NotificationsController', account)
def get_secret_chat_helper(account=None): return _controller('SecretChatHelper', account)
def get_download_controller(account=None): return _controller('DownloadController', account)
def get_notification_center(account=None): return _controller('NotificationCenter', account)
def get_notifications_settings(account=None): return MessagesController.getNotificationsSettings(_resolve_account(account))
def get_media_controller():
    from org.telegram.messenger import MediaController
    return MediaController.getInstance()

class AccountClient:
    def __init__(self, account): self.account = int(account)
    def __eq__(self, other): return isinstance(other, AccountClient) and self.account == other.account
    def __hash__(self): return hash(self.account)
    def __getattr__(self, name):
        fn = globals().get(name)
        if callable(fn) and (name.startswith('get_') or name.startswith('send_') or name == 'edit_message'):
            return lambda *args, **kwargs: fn(*args, account=self.account, **kwargs)
        raise AttributeError(name)

def get_client(account=None): return AccountClient(_resolve_account(account))


from org.telegram.messenger import NotificationCenter
class NotificationCenterDelegate(dynamic_proxy(NotificationCenter.NotificationCenterDelegate)):
    def didReceivedNotification(self, notification_id, account, args):
        pass
