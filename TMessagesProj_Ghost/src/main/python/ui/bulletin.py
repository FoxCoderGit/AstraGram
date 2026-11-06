from org.telegram.ui.Components import BulletinFactory
from org.telegram.ui.ActionBar import BottomSheet
from org.telegram.messenger import R
from android_utils import run_on_ui_thread, R as Runnable
from client_utils import get_last_fragment


class BulletinHelper:
    DURATION_SHORT, DURATION_LONG, DURATION_PROLONG = 1500, 2750, 5000

    @classmethod
    def _get_factory_and_provider(cls, fragment=None):
        target = fragment or get_last_fragment()
        if target is None: return getattr(BulletinFactory, 'global')(), None
        provider = target.getResourceProvider()
        dialog = target.visibleDialog
        if fragment is None and dialog is not None and isinstance(dialog, BottomSheet):
            return BulletinFactory.of(dialog.getContainer(), provider), provider
        return BulletinFactory.of(target), provider

    @classmethod
    def _show(cls, fragment, make):
        def task():
            factory, provider = cls._get_factory_and_provider(fragment)
            make(factory, provider).show()
        run_on_ui_thread(task)

    @classmethod
    def show_info(cls, message, fragment=None): cls._show(fragment, lambda factory, provider: factory.createSimpleBulletin(R.raw.info, str(message)))
    @classmethod
    def show_error(cls, message, fragment=None): cls._show(fragment, lambda factory, provider: factory.createErrorBulletin(str(message)))
    @classmethod
    def show_success(cls, message, fragment=None): cls._show(fragment, lambda factory, provider: factory.createSuccessBulletin(str(message)))
    @classmethod
    def show_simple(cls, text, icon_res_id, fragment=None): cls._show(fragment, lambda factory, provider: factory.createSimpleBulletin(icon_res_id, str(text)))
    @classmethod
    def show_two_line(cls, title, subtitle, icon_res_id, fragment=None): cls._show(fragment, lambda factory, provider: factory.createSimpleBulletin(icon_res_id, str(title), str(subtitle)))
    @classmethod
    def show_with_button(cls, text, icon_res_id, button_text, on_click, fragment=None, duration=DURATION_LONG):
        cls._show(fragment, lambda factory, provider: factory.createSimpleBulletin(icon_res_id, str(text), str(button_text), int(duration), Runnable(on_click)))
    @classmethod
    def show_undo(cls, text, on_undo, on_action=None, subtitle=None, fragment=None):
        cls._show(fragment, lambda factory, provider: factory.createUndoBulletin(str(text), subtitle, Runnable(on_undo), Runnable(on_action) if on_action else None))
    @classmethod
    def show_copied_to_clipboard(cls, message=None, fragment=None): cls._show(fragment, lambda factory, provider: factory.createCopyBulletin(message))
    @classmethod
    def show_link_copied(cls, is_private_link_info=False, fragment=None): cls._show(fragment, lambda factory, provider: factory.createCopyLinkBulletin(bool(is_private_link_info)))
    @classmethod
    def show_file_saved_to_gallery(cls, is_video=False, amount=1, fragment=None):
        kind = ('VIDEOS' if amount > 1 else 'VIDEO') if is_video else ('PHOTOS' if amount > 1 else 'PHOTO')
        cls.show_file_saved_to_downloads(kind, amount, fragment)
    @classmethod
    def show_file_saved_to_downloads(cls, file_type_enum_name='UNKNOWN', amount=1, fragment=None):
        cls._show(fragment, lambda factory, provider: factory.createDownloadBulletin(BulletinFactory.FileType.valueOf(file_type_enum_name), int(amount), provider))
