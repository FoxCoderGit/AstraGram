package com.exteragram.messenger.utils;

import android.util.Log;

import androidx.annotation.Keep;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;

@Keep
public final class AppUtils {
    private static final String TAG = "AstraGramPlugins";

    private AppUtils() {}

    public static void log(String value) {
        Log.d(TAG, value);
    }

    public static void log(Throwable error) {
        log("", error);
    }

    public static void log(String value, Throwable error) {
        if (error == null) {
            log(value);
        } else {
            Log.e(TAG, value, error);
        }
    }

    public static String stackTraceToString(Throwable error) {
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }

    public static Object getPrivateField(Object target, String name) {
        return readField(target.getClass(), target, name);
    }

    public static void setPrivateField(Object target, String name, Object value) {
        writeField(target.getClass(), target, name, value);
    }

    public static Object getPrivateStaticField(Class<?> type, String name) {
        return readField(type, null, name);
    }

    public static void setPrivateStaticField(Class<?> type, String name, Object value) {
        writeField(type, null, name, value);
    }

    private static Object readField(Class<?> type, Object target, String name) {
        try {
            return findField(type, name).get(target);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(type.getName(), error);
            return null;
        }
    }

    private static void writeField(Class<?> type, Object target, String name, Object value) {
        try {
            findField(type, name).set(target, value);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(type.getName(), error);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Plugins may address private fields declared by a parent class.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    public static boolean compareVersions(String operator, String left, String right) {
        return compare(operator, compareVersionValues(left, right));
    }

    public static boolean compareVersions(String operator, int left, int right) {
        return compare(operator, Integer.compare(left, right));
    }

    public static int compareVersionValues(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int i = 0; i < Math.max(leftParts.length, rightParts.length); i++) {
            int leftPart = i < leftParts.length ? Integer.parseInt(leftParts[i]) : 0;
            int rightPart = i < rightParts.length ? Integer.parseInt(rightParts[i]) : 0;
            int result = Integer.compare(leftPart, rightPart);
            if (result != 0) return result;
        }
        return 0;
    }

    private static boolean compare(String operator, int result) {
        switch (operator) {
            case "<": return result < 0;
            case "<=": return result <= 0;
            case "==": return result == 0;
            case ">=": return result >= 0;
            case ">": return result > 0;
            default: throw new IllegalArgumentException("Unsupported version operator: " + operator);
        }
    }
}
