from org.telegram.ui.ActionBar import AlertDialog, Theme
from android.content import DialogInterface
from java import dynamic_proxy, jarray, jint
from java.lang import CharSequence
from android_utils import log
import traceback


class _ButtonClickListenerProxy(dynamic_proxy(AlertDialog.OnButtonClickListener)):
    def __init__(self, callback, builder):
        super().__init__()
        self.callback, self.builder = callback, builder
    def onClick(self, dialog, which):
        try: self.callback(self.builder, int(which))
        except Exception: log(traceback.format_exc())


class _ItemsClickListenerProxy(dynamic_proxy(DialogInterface.OnClickListener)):
    def __init__(self, callback, builder):
        super().__init__()
        self.callback, self.builder = callback, builder
    def onClick(self, dialog, which):
        try: self.callback(self.builder, int(which))
        except Exception: log(traceback.format_exc())


class _DismissListenerProxy(dynamic_proxy(DialogInterface.OnDismissListener)):
    def __init__(self, callback, builder):
        super().__init__()
        self.callback, self.builder = callback, builder
    def onDismiss(self, dialog):
        try: self.callback(self.builder)
        except Exception: log(traceback.format_exc())


class _CancelListenerProxy(dynamic_proxy(DialogInterface.OnCancelListener)):
    def __init__(self, callback, builder):
        super().__init__()
        self.callback, self.builder = callback, builder
    def onCancel(self, dialog):
        try: self.callback(self.builder)
        except Exception: log(traceback.format_exc())


class AlertDialogBuilder:
    ALERT_TYPE_MESSAGE, ALERT_TYPE_LOADING, ALERT_TYPE_SPINNER = 0, 2, 3
    BUTTON_POSITIVE, BUTTON_NEGATIVE, BUTTON_NEUTRAL = -1, -2, -3

    def __init__(self, context, progress_style=0, resources_provider=None):
        self._context, self._progress_style = context, progress_style
        self._java_builder = AlertDialog.Builder(context, progress_style, resources_provider)
        self.builder = self._java_builder
        self._alert_dialog = None
        self._red_buttons = []

    def get_context(self): return self._context

    def set_title(self, value):
        self.builder.setTitle(str(value)); return self

    def set_message(self, value):
        self.builder.setMessage(str(value)); return self

    def set_positive_button(self, text, listener=None):
        self.builder.setPositiveButton(str(text), _ButtonClickListenerProxy(listener, self) if listener else None); return self

    def set_negative_button(self, text, listener=None):
        self.builder.setNegativeButton(str(text), _ButtonClickListenerProxy(listener, self) if listener else None); return self

    def set_neutral_button(self, text, listener=None):
        self.builder.setNeutralButton(str(text), _ButtonClickListenerProxy(listener, self) if listener else None); return self

    def set_message_text_view_clickable(self, clickable): self.builder.setMessageTextViewClickable(clickable); return self
    def set_on_back_button_listener(self, listener=None): self.builder.setOnBackButtonListener(_ButtonClickListenerProxy(listener, self) if listener else None); return self
    def set_view(self, view, height=-2): self.builder.setView(view, height); return self
    def set_items(self, items, listener=None, icons=None):
        callback = _ItemsClickListenerProxy(listener, self) if listener else None
        if icons is None: self.builder.setItems(jarray(CharSequence)(items), callback)
        else: self.builder.setItems(jarray(CharSequence)(items), jarray(jint)(icons), callback)
        return self
    def set_on_dismiss_listener(self, listener=None): self.builder.setOnDismissListener(_DismissListenerProxy(listener, self) if listener else None); return self
    def set_on_cancel_listener(self, listener=None): self.builder.setOnCancelListener(_CancelListenerProxy(listener, self) if listener else None); return self
    def set_top_image(self, res_id, background_color): self.builder.setTopImage(res_id, background_color); return self
    def set_top_drawable(self, drawable, background_color): self.builder.setTopImage(drawable, background_color); return self
    def set_top_animation(self, res_id, size, auto_repeat, background_color, layer_colors=None):
        if isinstance(layer_colors, dict):
            from java.util import HashMap
            converted = HashMap()
            for key, value in layer_colors.items(): converted.put(key, value)
            layer_colors = converted
        self.builder.setTopAnimation(res_id, size, auto_repeat, background_color, layer_colors)
        return self
    def set_top_animation_is_new(self, is_new): self.builder.setTopAnimationIsNew(is_new); return self
    def set_dim_enabled(self, enabled): self.builder.setDimEnabled(enabled); return self
    def set_dialog_button_color_key(self, theme_key): self.builder.setDialogButtonColorKey(theme_key); return self
    def set_blurred_background(self, blur, blur_behind_if_possible=True): self.builder.setBlurredBackground(blur); return self
    def make_button_red(self, button_type):
        self._red_buttons.append(button_type)
        if self._alert_dialog is not None: self._apply_red_buttons()
        return self
    def _apply_red_buttons(self):
        for button in self._red_buttons:
            view = self._alert_dialog.getButton(button)
            if view is not None: view.setTextColor(Theme.getColor(Theme.key_text_RedBold))
    def create(self):
        if self._alert_dialog is None:
            self._alert_dialog = self.builder.create()
            self._apply_red_buttons()
        return self

    def show(self):
        if self._alert_dialog is None:
            self._alert_dialog = self.builder.show()
            self._apply_red_buttons()
        elif not self._alert_dialog.isShowing(): self._alert_dialog.show()
        return self
    def get_dialog(self): return self._alert_dialog
    def get_button(self, button_type): return self._alert_dialog.getButton(button_type) if self._alert_dialog else None
    def dismiss(self):
        if self._alert_dialog is not None and self._alert_dialog.isShowing(): self._alert_dialog.dismiss()
    def set_progress(self, progress):
        if self._alert_dialog is not None: self._alert_dialog.setProgress(progress)
        else: log('Dialog has not been created')
    def set_cancelable(self, cancelable):
        if self._alert_dialog is not None: self._alert_dialog.setCancelable(cancelable)
        else: log('Dialog has not been created')
    def set_canceled_on_touch_outside(self, cancel):
        if self._alert_dialog is not None: self._alert_dialog.setCanceledOnTouchOutside(cancel)
        else: log('Dialog has not been created')

