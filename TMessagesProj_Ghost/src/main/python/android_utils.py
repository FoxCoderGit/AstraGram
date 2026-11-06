import traceback
from client_utils import run_on_queue
from org.telegram.messenger import AndroidUtilities, ApplicationLoader, FileLog
from java import dynamic_proxy
from java.lang import Runnable
from android.view import View


class R(dynamic_proxy(Runnable)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn
    def run(self):
        try: self.fn()
        except Exception: log(traceback.format_exc())


class OnClickListener(dynamic_proxy(View.OnClickListener)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn
    def onClick(self, view):
        try: self.fn()
        except Exception: log(traceback.format_exc())


class OnLongClickListener(dynamic_proxy(View.OnLongClickListener)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn
    def onLongClick(self, view):
        try:
            self.fn()
            return True
        except Exception:
            log(traceback.format_exc())
            return False


def run_on_ui_thread(fn, delay=0): AndroidUtilities.runOnUIThread(R(fn), int(delay))

def log(value): FileLog.d(str(value))

def copy_to_clipboard(text): AndroidUtilities.addToClipboard(str(text))

def get_context(): return ApplicationLoader.applicationContext
