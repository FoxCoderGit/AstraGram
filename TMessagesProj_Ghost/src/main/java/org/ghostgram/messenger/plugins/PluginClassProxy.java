package org.ghostgram.messenger.plugins;

import com.android.dx.stock.ProxyBuilder;
import com.chaquo.python.PyObject;
import org.telegram.messenger.ApplicationLoader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Creates actual Android subclasses for Python-defined plugin views. */
public final class PluginClassProxy {
    public static synchronized Object create(Class<?> base, Object[] arguments, PyObject owner) throws Exception {
        Class<?>[] types = null;
        for (Constructor<?> constructor : base.getDeclaredConstructors()) {
            if (!Modifier.isPublic(constructor.getModifiers()) && !Modifier.isProtected(constructor.getModifiers())) continue;
            Class<?>[] candidate = constructor.getParameterTypes();
            if (candidate.length != arguments.length) continue;
            boolean compatible = true;
            for (int i = 0; i < candidate.length; i++) {
                if (arguments[i] == null) compatible &= !candidate[i].isPrimitive();
                else compatible &= boxed(candidate[i]).isInstance(arguments[i]);
            }
            if (compatible) { types = candidate; break; }
        }
        if (types == null) throw new IllegalArgumentException("No compatible constructor: " + base.getName());
        return ProxyBuilder.forClass(base)
                .dexCache(ApplicationLoader.applicationContext.getDir("plugin_dex", 0))
                .constructorArgTypes(types).constructorArgValues(arguments)
                .handler((proxy, method, args) -> {
                    if (!owner.callAttr("_has_java_override", method.getName()).toBoolean()) return ProxyBuilder.callSuper(proxy, method, args);
                    PyObject result = owner.callAttr("_dispatch_java", proxy, method, args);
                    if (method.getReturnType() == Void.TYPE) return null;
                    return result == null ? null : result.toJava(boxed(method.getReturnType()));
                }).build();
    }
    public static Object callSuper(Object proxy, Method method, Object[] arguments) throws Throwable {
        return ProxyBuilder.callSuper(proxy, method, arguments);
    }
    private static Class<?> boxed(Class<?> type) {
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
