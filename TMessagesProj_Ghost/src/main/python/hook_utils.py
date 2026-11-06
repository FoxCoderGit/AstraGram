def find_class(name):
    from java import jclass
    try:
        return jclass(name)
    except Exception:
        return None


def _find_field(instance, name):
    clazz = instance.getClass()
    while clazz is not None:
        try:
            field = clazz.getDeclaredField(name)
            field.setAccessible(True)
            return field
        except Exception:
            clazz = clazz.getSuperclass()
    raise AttributeError("Field not found: " + str(name))


def get_private_field(instance, name):
    try: return _find_field(instance, name).get(instance)
    except Exception: return None


def set_private_field(instance, name, value):
    try:
        _find_field(instance, name).set(instance, value)
        return True
    except Exception: return False


def hook_method(plugin, method, callback, priority=None):
    from org.ghostgram.messenger.plugins import PluginHookBridge
    if method is None:
        raise ValueError("method is required")
    try:
        if not any(callable(getattr(callback,name,None)) for name in ('replace_hooked_method','before_hooked_method','after_hooked_method')):
            raise TypeError('Unsupported Xposed callback')
        return PluginHookBridge.hook(method, _FilteredCallback(callback), 50 if priority is None else int(priority))
    except Exception as error:
        plugin.log(error)
        return None


class _FilteredCallback:
    def __init__(self, callback): self.callback = callback
    def is_replacement(self): return callable(getattr(self.callback, 'replace_hooked_method', None))
    def replace_hooked_method(self, param): return self.callback.replace_hooked_method(param)
    def before_hooked_method(self, param):
        fn = getattr(self.callback, 'before_hooked_method', None)
        if callable(fn) and matches_filters(getattr(fn, '__hook_filters__', ()), param): fn(param)
    def after_hooked_method(self, param):
        fn = getattr(self.callback, 'after_hooked_method', None)
        if callable(fn) and matches_filters(getattr(fn, '__hook_filters__', ()), param): fn(param)


def matches_filters(filters, param):
    return all(_matches_filter(item, param) for item in filters)


def _matches_filter(item, param):
    if isinstance(item, str):
        value = param.getResult()
        return {'result_is_null': value is None, 'result_not_null': value is not None,
                'result_is_true': value == True, 'result_is_false': value == False}.get(item, False)
    kind, *data = item
    if kind == 'or': return any(_matches_filter(child, param) for child in data[0])
    value = param.args[data[0]] if kind.startswith('argument_') else param.getResult()
    expected = data[-1]
    if kind.endswith('is_null'): return value is None
    if kind.endswith('not_null'): return value is not None
    if kind.endswith('is_true'): return value == True
    if kind.endswith('is_false'): return value == False
    if kind.endswith('not_equal'): return value != expected
    if kind.endswith('equal'): return value == expected
    if kind.endswith('instance_of'): return value is not None and expected.isInstance(value)
    raise ValueError('Unsupported hook filter: ' + str(kind))


class HookFilter:
    RESULT_IS_NULL = "result_is_null"
    RESULT_NOT_NULL = "result_not_null"
    RESULT_IS_TRUE = "result_is_true"
    RESULT_IS_FALSE = "result_is_false"

    @staticmethod
    def ResultEqual(value): return ('result_equal', value)
    @staticmethod
    def ResultNotEqual(value): return ('result_not_equal', value)
    @staticmethod
    def ResultIsInstanceOf(clazz): return ('result_instance_of', clazz)
    @staticmethod
    def ArgumentNotNull(index): return ('argument_not_null', index)
    @staticmethod
    def ArgumentIsTrue(index): return ('argument_is_true', index)
    @staticmethod
    def ArgumentIsFalse(index): return ('argument_is_false', index)
    @staticmethod
    def ArgumentEqual(index, value): return ('argument_equal', index, value)
    @staticmethod
    def ArgumentNotEqual(index, value): return ('argument_not_equal', index, value)
    @staticmethod
    def ArgumentIsInstanceOf(index, clazz): return ('argument_instance_of', index, clazz)

    @staticmethod
    def ArgumentIsNull(index):
        return ("argument_is_null", index)

    @staticmethod
    def Or(*filters):
        return ("or", filters)


def get_static_private_field(clazz, name):
    try:
        field = _find_static_field(clazz, name)
        return field.get(None)
    except Exception: return None


def _find_static_field(clazz, name):
    while clazz is not None:
        try:
            field = clazz.getDeclaredField(name)
            field.setAccessible(True)
            return field
        except Exception: clazz = clazz.getSuperclass()
    raise AttributeError(name)


def set_static_private_field(clazz, name, value):
    try:
        _find_static_field(clazz, name).set(None, value)
        return True
    except Exception: return False


def set_static_private_field(clazz, name, value):
    field = clazz.getDeclaredField(name)
    field.setAccessible(True)
    field.set(None, value)
    return True
