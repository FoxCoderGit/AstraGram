"""Python view subclasses backed by real DexMaker Android instances."""
import threading
import weakref
_instances = weakref.WeakValueDictionary()


class Base:
    def __init__(self, *args, **kwargs):
        if '_java' in self.__dict__: return
        self._construct_java(args)

    def _construct_java(self, args):
        from java import jclass
        from org.ghostgram.messenger.plugins import PluginClassProxy
        base = self.__java_base_class__
        if isinstance(base, str): base = jclass(base)
        self._java_calls = threading.local()
        from java.lang import Class
        java_class = base if isinstance(base, Class) else base.getClass()
        self._java = PluginClassProxy.create(java_class, list(args), self)
        from java.lang import System
        _instances[int(System.identityHashCode(self._java))] = self

    @property
    def java(self): return self._java
    @property
    def this(self): return self._java
    def __getattr__(self, name): return getattr(self._java, name)

    def _has_java_override(self, name):
        fn = getattr(type(self), str(name), None)
        return bool(fn and getattr(fn, '__java_override__', False))

    def _dispatch_java(self, proxy, method, args):
        previous = getattr(self._java_calls, 'method', None)
        self._java_calls.method = method
        try: return getattr(self, str(method.getName()))(*(args or ()))
        finally: self._java_calls.method = previous

    def call_super(self, *args):
        from org.ghostgram.messenger.plugins import PluginClassProxy
        method = getattr(self._java_calls, 'method', None)
        if method is None: raise RuntimeError('call_super must be used inside a Java override')
        return PluginClassProxy.callSuper(self.java, method, list(args))

    def jrelease(self):
        from java.lang import System
        _instances.pop(int(System.identityHashCode(self.java)), None)
        self.on_release()
    def on_release(self): pass

    @classmethod
    def extends(cls, java_class, *interfaces, **kwargs):
        cls.__java_base_class__ = java_class
        return cls

    @classmethod
    def new_instance(cls, *args, init_args=None):
        instance = cls.__new__(cls)
        instance._construct_java(args)
        cls.__init__(instance, *(args if init_args is None else init_args))
        return instance

    @classmethod
    def new_java_instance(cls, *args): return cls.new_instance(*args).java

    @classmethod
    def from_java(cls, instance, **kwargs):
        from java.lang import System
        found = _instances.get(int(System.identityHashCode(instance)))
        if found is None: raise ValueError('This Java object was not created by the Python class proxy')
        return found


def java_subclass(java_class):
    def decorate(cls):
        cls.__java_base_class__ = java_class
        for name, fn in cls.__dict__.items():
            if getattr(fn, '__java_override__', False) and not hasattr(Base, name):
                def super_method(self, *args): return self.call_super(*args)
                setattr(Base, name, super_method)
        return cls
    return decorate


def joverride(*_args, **_kwargs):
    if len(_args) == 1 and callable(_args[0]) and not hasattr(_args[0], 'class_'):
        _args[0].__java_override__ = True
        return _args[0]
    def decorate(function):
        function.__java_override__ = True
        return function
    return decorate

class JHelper:
    Override = staticmethod(joverride)
