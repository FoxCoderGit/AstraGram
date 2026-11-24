package com.exteragram.messenger.plugins.utils;

import android.os.Build;
import android.util.LruCache;
import androidx.annotation.Keep;

import com.android.dx.Code;
import com.android.dx.Comparison;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Label;
import com.android.dx.Local;
import com.android.dx.TypeId;
import com.chaquo.python.PyObject;

import dalvik.system.InMemoryDexClassLoader;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.mvel2.MVEL;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

/** Generates Java subclasses described by the exteraGram plugin API. */
@Keep
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ClassProxy {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final String PEER = "__extera_internal_python_peer__";
    private static final TypeId HANDLER = TypeId.get(Utilities.Callback3Return.class);
    private static final TypeId<Object[]> ARGUMENTS = TypeId.get(Object[].class);
    private static final LruCache<String, Serializable> EXPRESSIONS = new LruCache<>(64);

    public interface DexMakerHook {
        void apply(DexMaker maker, TypeId<?> proxy, TypeId<?> base, List<TypeId<?>> interfaces);
    }

    public static void runPythonRunnable(PyObject callback) {
        try {
            if (callback != null) callback.call();
        } catch (Exception error) {
            FileLog.e(error);
        }
    }

    public static Class<?> createProxyClass(Class<?> base, Utilities.Callback3Return<Object, String, Object[], Object> handler, List<Method> methods, List<Constructor<?>> constructors) throws Exception {
        return createProxyClass(base, handler, methods, constructors, (String) null);
    }

    public static Class<?> createProxyClass(Class<?> base, Utilities.Callback3Return<Object, String, Object[], Object> handler, List<Method> methods, List<Constructor<?>> constructors, String name) throws Exception {
        if (methods == null) {
            Map<String, Method> candidates = new LinkedHashMap<>();
            collect(base, candidates);
            methods = new ArrayList<>(candidates.values());
        }
        return createProxyClass(base, handler, methods, constructors, null, null, name);
    }

    public static Class<?> createProxyClass(Class<?> base, Utilities.Callback3Return<Object, String, Object[], Object> handler, List<Method> methods, List<Constructor<?>> constructors, List<ProxyMethodSpec> specs, List<FieldSpec> fields) throws Exception {
        return createProxyClass(base, handler, methods, constructors, specs, fields, null);
    }

    public static Class<?> createProxyClass(Class<?> base, Utilities.Callback3Return<Object, String, Object[], Object> handler, List<Method> methods, List<Constructor<?>> constructors, List<ProxyMethodSpec> specs, List<FieldSpec> fields, String name) throws Exception {
        return createProxyClass(base, handler, null, null, methods, constructors, specs, fields, name);
    }

    public static Class<?> createProxyClass(Class<?> base, Utilities.Callback3Return<Object, String, Object[], Object> handler, DexMakerHook hook, List<Class<?>> interfaces, List<Method> methods, List<Constructor<?>> constructors, List<ProxyMethodSpec> specs, List<FieldSpec> fields) throws Exception {
        return createProxyClass(base, handler, hook, interfaces, methods, constructors, specs, fields, null);
    }

    public static Class<?> createProxyClass(Class<?> base, Utilities.Callback3Return<Object, String, Object[], Object> handler, DexMakerHook hook, List<Class<?>> interfaces, List<Method> methods, List<Constructor<?>> constructors, List<ProxyMethodSpec> specs, List<FieldSpec> fields, String name) throws Exception {
        String segment = name == null ? base.getSimpleName() : name;
        String qualifiedName = "com.exteragram.messenger.plugins.Proxy_" + segment.replaceAll("[^a-zA-Z0-9_]", "_") + "_" + NEXT_ID.incrementAndGet();
        TypeId proxy = TypeId.get("L" + qualifiedName.replace('.', '/') + ";");
        TypeId parent = TypeId.get(base);
        DexMaker maker = new DexMaker();
        List<TypeId<?>> interfaceIds = new ArrayList<>();
        Map<String, Method> candidates = new LinkedHashMap<>();
        collect(base, candidates);
        if (interfaces != null) {
            for (Class<?> iface : interfaces) {
                if (!iface.isInterface()) throw new IllegalArgumentException(iface.getName());
                interfaceIds.add(TypeId.get(iface));
                collect(iface, candidates);
            }
        }
        maker.declare(proxy, "PluginProxy.java", Modifier.PUBLIC, parent, interfaceIds.toArray(new TypeId<?>[0]));
        FieldId callback = proxy.getField(HANDLER, "staticHandler");
        maker.declare(callback, Modifier.PUBLIC | Modifier.STATIC, null);
        boolean hasPeer = false;
        if (fields != null) {
            for (FieldSpec field : fields) {
                declareField(maker, proxy, field);
                hasPeer |= PEER.equals(field.name);
            }
        }
        if (!hasPeer) {
            maker.declare(proxy.getField(TypeId.OBJECT, PEER), Modifier.PUBLIC, null);
        }
        for (Constructor<?> constructor : constructors == null ? Arrays.asList(base.getDeclaredConstructors()) : constructors) {
            if (accessible(constructor.getModifiers())) {
                declareConstructor(maker, proxy, parent, callback, constructor.getParameterTypes());
            }
        }
        Map<String, Method> overrides = new LinkedHashMap<>();
        Map<String, ProxyMethodSpec> bodies = new HashMap<>();
        if (methods != null) {
            for (Method method : methods) overrides.put(signature(method.getName(), method.getParameterTypes()), method);
        }
        if (specs != null) {
            for (ProxyMethodSpec spec : specs) {
                if (!spec.overrideExisting) {
                    declareMethod(maker, proxy, callback, spec.name, spec.returnType == null ? void.class : spec.returnType,
                            spec.parameterTypes == null ? new Class<?>[0] : spec.parameterTypes, access(spec.modifiers), spec);
                    continue;
                }
                boolean matched = false;
                for (Map.Entry<String, Method> entry : candidates.entrySet()) {
                    Method method = entry.getValue();
                    if (spec.name.equals(method.getName()) && (spec.parameterTypes == null || Arrays.equals(spec.parameterTypes, method.getParameterTypes()))) {
                        overrides.put(entry.getKey(), method);
                        bodies.put(entry.getKey(), spec);
                        matched = true;
                    }
                }
                if (!matched) throw new NoSuchMethodException(spec.name);
            }
        }
        for (Map.Entry<String, Method> entry : overrides.entrySet()) {
            Method method = entry.getValue();
            if (!Modifier.isAbstract(method.getModifiers()) && !method.getDeclaringClass().isInterface()) {
                TypeId result = TypeId.get(method.getReturnType());
                TypeId<?>[] types = types(method.getParameterTypes());
                Code code = maker.declare(proxy.getMethod(result, "SUPER_" + method.getName(), types), Modifier.PUBLIC);
                Local value = result.equals(TypeId.VOID) ? null : code.newLocal(result);
                code.invokeSuper(TypeId.get(method.getDeclaringClass()).getMethod(result, method.getName(), types), value, code.getThis(proxy), parameters(code, types));
                if (value == null) code.returnVoid(); else code.returnValue(value);
            }
            declareMethod(maker, proxy, callback, method.getName(), method.getReturnType(), method.getParameterTypes(), Modifier.PUBLIC, bodies.get(entry.getKey()));
        }
        if (hook != null) hook.apply(maker, proxy, parent, interfaceIds);

        ClassLoader loader = base.getClassLoader();
        if (loader == null || loader == Object.class.getClassLoader()) loader = ClassProxy.class.getClassLoader();
        ClassLoader generated = Build.VERSION.SDK_INT >= 26
                ? new InMemoryDexClassLoader(ByteBuffer.wrap(maker.generate()), loader)
                : maker.generateAndLoad(loader, ApplicationLoader.applicationContext.getCodeCacheDir());
        Class<?> result = generated.loadClass(qualifiedName);
        result.getField("staticHandler").set(null, handler);
        return result;
    }

    private static void declareConstructor(DexMaker maker, TypeId proxy, TypeId parent, FieldId callback, Class<?>[] classes) {
        TypeId<?>[] types = types(classes);
        Code code = maker.declare(proxy.getConstructor(types), Modifier.PUBLIC);
        Local self = code.getThis(proxy);
        Local[] args = parameters(code, types);
        Local<Object> before = code.newLocal(TypeId.OBJECT);
        Local<Object> replacement = code.newLocal(TypeId.OBJECT);
        Local<Object[]> newArgs = code.newLocal(ARGUMENTS);
        Local<Integer> index = code.newLocal(TypeId.INT);
        Local<Object> value = code.newLocal(TypeId.OBJECT);
        Arguments boxed = new Arguments(code);
        Callback invocation = new Callback(code);
        code.loadConstant(before, null);
        boxed.fill(args, classes);
        invocation.invoke(callback, before, signature("<constructor>", classes), boxed.array, replacement);
        Label unchanged = new Label();
        code.compare(Comparison.EQ, unchanged, replacement, before);
        code.cast(newArgs, replacement);
        for (int i = 0; i < args.length; i++) {
            code.loadConstant(index, i);
            code.aget(value, newArgs, index);
            convert(code, args[i], classes[i], value);
        }
        code.mark(unchanged);
        code.invokeDirect(parent.getConstructor(types), null, self, args);
        invocation.invoke(callback, self, signature("<constructor>", classes), boxed.array, null);
        code.returnVoid();
    }

    private static void declareMethod(DexMaker maker, TypeId proxy, FieldId callback, String name, Class<?> result, Class<?>[] classes, int modifiers, ProxyMethodSpec spec) {
        TypeId<?>[] types = types(classes);
        TypeId resultType = TypeId.get(result);
        Code code = maker.declare(proxy.getMethod(resultType, name, types), modifiers);
        boolean isStatic = Modifier.isStatic(modifiers);
        Local self = isStatic ? code.newLocal(TypeId.OBJECT) : code.getThis(proxy);
        Local<Object> returned = code.newLocal(TypeId.OBJECT);
        Local output = result == void.class ? null : code.newLocal(resultType);
        Arguments args = new Arguments(code);
        Local[] params = parameters(code, types);
        if (spec != null && spec.isMvel()) {
            Local<Object> peer = code.newLocal(TypeId.OBJECT);
            Local<String> expression = code.newLocal(TypeId.STRING);
            TypeId<String[]> strings = TypeId.get(String[].class);
            Local<String[]> names = code.newLocal(strings);
            Local<String> argumentName = code.newLocal(TypeId.STRING);
            Local<Integer> index = code.newLocal(TypeId.INT);
            if (isStatic) {
                code.loadConstant(self, null);
                code.loadConstant(peer, null);
            } else {
                code.iget(proxy.getField(TypeId.OBJECT, PEER), peer, self);
            }
            code.loadConstant(expression, spec.mvelCode);
            if (spec.argumentNames == null) {
                code.loadConstant(names, null);
            } else {
                code.loadConstant(index, spec.argumentNames.size());
                code.newArray(names, index);
                for (int i = 0; i < spec.argumentNames.size(); i++) {
                    code.loadConstant(index, i);
                    code.loadConstant(argumentName, spec.argumentNames.get(i));
                    code.aput(names, index, argumentName);
                }
            }
            args.fill(params, classes);
            code.invokeStatic(TypeId.get(ClassProxy.class).getMethod(TypeId.OBJECT, "executeMvelMethod",
                    TypeId.OBJECT, TypeId.OBJECT, TypeId.STRING, strings, ARGUMENTS), returned, self, peer, expression, names, args.array);
        } else {
            Callback invocation = new Callback(code);
            if (isStatic) code.loadConstant(self, null);
            args.fill(params, classes);
            invocation.invoke(callback, self, signature(name, classes), args.array, returned);
        }
        if (output == null) {
            code.returnVoid();
        } else {
            convert(code, output, result, returned);
            code.returnValue(output);
        }
    }

    private static void declareField(DexMaker maker, TypeId proxy, FieldSpec spec) {
        TypeId type = TypeId.get(spec.type);
        FieldId field = proxy.getField(type, spec.name);
        maker.declare(field, spec.modifiers, null);
        if (spec.methods == null) return;
        boolean isStatic = Modifier.isStatic(spec.modifiers);
        for (FieldMethodSpec method : spec.methods) {
            int modifiers = access(method.modifiers) | (isStatic ? Modifier.STATIC : 0);
            if (method.getter) {
                Code code = maker.declare(proxy.getMethod(type, method.name), modifiers);
                Local value = code.newLocal(type);
                if (isStatic) code.sget(field, value); else code.iget(field, value, code.getThis(proxy));
                code.returnValue(value);
            } else {
                Code code = maker.declare(proxy.getMethod(TypeId.VOID, method.name, type), modifiers);
                Local value = code.getParameter(0, type);
                if (isStatic) code.sput(field, value); else code.iput(field, code.getThis(proxy), value);
                code.returnVoid();
            }
        }
    }

    private static void collect(Class<?> type, Map<String, Method> target) {
        if (type == null || type == Object.class) return;
        for (Method method : type.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (accessible(modifiers) && !Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers) && !method.isBridge()) {
                target.putIfAbsent(signature(method.getName(), method.getParameterTypes()), method);
            }
        }
        collect(type.getSuperclass(), target);
        for (Class<?> iface : type.getInterfaces()) collect(iface, target);
    }

    private static boolean accessible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static int access(int modifiers) {
        return modifiers == 0 ? Modifier.PUBLIC : modifiers;
    }

    private static TypeId<?>[] types(Class<?>[] classes) {
        TypeId<?>[] result = new TypeId<?>[classes.length];
        for (int i = 0; i < classes.length; i++) result[i] = TypeId.get(classes[i]);
        return result;
    }

    private static Local[] parameters(Code code, TypeId<?>[] types) {
        Local[] result = new Local[types.length];
        for (int i = 0; i < types.length; i++) result[i] = code.getParameter(i, types[i]);
        return result;
    }

    private static String signature(String name, Class<?>[] types) {
        StringBuilder signature = new StringBuilder(name);
        for (Class<?> type : types) signature.append(':').append(type.getName());
        return signature.toString();
    }

    private static void convert(Code code, Local destination, Class<?> type, Local<Object> value) {
        if (type.isPrimitive()) {
            String name = type == boolean.class ? "Bool" : Character.toUpperCase(type.getName().charAt(0)) + type.getName().substring(1);
            code.invokeStatic(TypeId.get(TypeHelper.class).getMethod(TypeId.get(type), "unbox" + name, TypeId.OBJECT), destination, value);
        } else {
            code.cast(destination, value);
        }
    }

    private static final class Arguments {
        final Code code;
        final Local<Object[]> array;
        final Local<Integer> index;
        final Local<Object> boxed;

        Arguments(Code code) {
            this.code = code;
            array = code.newLocal(ARGUMENTS);
            index = code.newLocal(TypeId.INT);
            boxed = code.newLocal(TypeId.OBJECT);
        }

        void fill(Local[] params, Class<?>[] types) {
            code.loadConstant(index, params.length);
            code.newArray(array, index);
            for (int i = 0; i < params.length; i++) {
                code.invokeStatic(TypeId.get(TypeHelper.class).getMethod(TypeId.OBJECT, "box",
                        types[i].isPrimitive() ? TypeId.get(types[i]) : TypeId.OBJECT), boxed, params[i]);
                code.loadConstant(index, i);
                code.aput(array, index, boxed);
            }
        }
    }

    private static final class Callback {
        final Code code;
        final Local handler;
        final Local<String> signature;

        Callback(Code code) {
            this.code = code;
            handler = code.newLocal(HANDLER);
            signature = code.newLocal(TypeId.STRING);
        }

        void invoke(FieldId field, Local self, String name, Local<Object[]> args, Local<Object> result) {
            code.sget(field, handler);
            code.loadConstant(signature, name);
            code.invokeInterface(HANDLER.getMethod(TypeId.OBJECT, "run", TypeId.OBJECT, TypeId.OBJECT, TypeId.OBJECT),
                    result, handler, self, signature, args);
        }
    }

    public static Object executeMvelMethod(Object java, Object peer, String expression, String[] names, Object[] args) {
        if (args == null) args = new Object[0];
        Map<String, Object> variables = new HashMap<>();
        variables.put("java", java);
        variables.put("python", peer);
        variables.put("py", peer);
        variables.put("self", peer);
        variables.put("args", args);
        variables.put("argc", args.length);
        for (int i = 0; i < args.length; i++) {
            variables.put("arg" + i, args[i]);
            if (names != null && i < names.length && names[i] != null && !names[i].isEmpty()) {
                variables.put(names[i], args[i]);
            }
        }
        Serializable compiled = EXPRESSIONS.get(expression);
        if (compiled == null) {
            compiled = MVEL.compileExpression(expression);
            EXPRESSIONS.put(expression, compiled);
        }
        return MVEL.executeExpression(compiled, java, variables);
    }

    @Keep
    public static final class ProxyMethodSpec {
        private final String name;
        private final Class<?> returnType;
        private final Class<?>[] parameterTypes;
        private final int modifiers;
        private final boolean overrideExisting;
        private final String implementation;
        private final String mvelCode;
        private final List<String> argumentNames;

        public ProxyMethodSpec(String name, Class<?> returns, Class<?>[] parameters, int modifiers, boolean overrideExisting) {
            this(name, returns, parameters, modifiers, overrideExisting, "python", null, null);
        }

        public ProxyMethodSpec(String name, Class<?> returns, Class<?>[] parameters, int modifiers, boolean overrideExisting, String implementation, String mvelCode, List<String> argumentNames) {
            this.name = name;
            this.returnType = returns;
            this.parameterTypes = parameters;
            this.modifiers = modifiers;
            this.overrideExisting = overrideExisting;
            this.implementation = implementation;
            this.mvelCode = mvelCode;
            this.argumentNames = argumentNames;
        }

        public String name() { return name; }
        public Class<?> returnType() { return returnType; }
        public Class<?>[] parameterTypes() { return parameterTypes; }
        public int modifiers() { return modifiers; }
        public boolean overrideExisting() { return overrideExisting; }
        public String implementation() { return implementation; }
        public String mvelCode() { return mvelCode; }
        public List<String> argumentNames() { return argumentNames; }
        public boolean isMvel() { return "mvel".equals(implementation) && mvelCode != null; }
    }

    @Keep
    public static final class FieldSpec {
        private final String name;
        private final Class<?> type;
        private final int modifiers;
        private final List<FieldMethodSpec> methods;

        public FieldSpec(String name, Class<?> type, int modifiers) {
            this(name, type, modifiers, null);
        }

        public FieldSpec(String name, Class<?> type, int modifiers, List<FieldMethodSpec> methods) {
            this.name = name;
            this.type = type;
            this.modifiers = modifiers;
            this.methods = methods;
        }

        public String name() { return name; }
        public Class<?> type() { return type; }
        public int modifiers() { return modifiers; }
        public List<FieldMethodSpec> methods() { return methods; }
    }

    @Keep
    public static final class FieldMethodSpec {
        private final String name;
        private final int modifiers;
        private final boolean getter;

        public FieldMethodSpec(String name, int modifiers, boolean getter) {
            this.name = name;
            this.modifiers = modifiers;
            this.getter = getter;
        }

        public String name() { return name; }
        public int modifiers() { return modifiers; }
        public boolean getter() { return getter; }
    }

    @Keep
    public static final class TypeHelper {
        public static Object box(Object value) { return value; }
        public static Object box(boolean value) { return value; }
        public static Object box(byte value) { return value; }
        public static Object box(char value) { return value; }
        public static Object box(short value) { return value; }
        public static Object box(int value) { return value; }
        public static Object box(long value) { return value; }
        public static Object box(float value) { return value; }
        public static Object box(double value) { return value; }

        public static boolean unboxBool(Object value) { return value instanceof Boolean && (Boolean) value; }
        public static byte unboxByte(Object value) { return value instanceof Number ? ((Number) value).byteValue() : 0; }
        public static char unboxChar(Object value) { return value instanceof Character ? (Character) value : 0; }
        public static short unboxShort(Object value) { return value instanceof Number ? ((Number) value).shortValue() : 0; }
        public static int unboxInt(Object value) { return value instanceof Number ? ((Number) value).intValue() : 0; }
        public static long unboxLong(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0; }
        public static float unboxFloat(Object value) { return value instanceof Number ? ((Number) value).floatValue() : 0; }
        public static double unboxDouble(Object value) { return value instanceof Number ? ((Number) value).doubleValue() : 0; }
    }
}
