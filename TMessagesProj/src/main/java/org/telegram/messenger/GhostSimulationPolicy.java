package org.telegram.messenger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

/** Independent of UI lifecycle: virtual peers must never reach the transport. */
public final class GhostSimulationPolicy {
    public static final long VIRTUAL_PREFIX = 0x1000000000000000L;
    private GhostSimulationPolicy() {}
    public static boolean virtual(long id) { return (id & 0x7fff000000000000L) == VIRTUAL_PREFIX; }
    public static boolean mutation(String name) {
        if (name.matches("TL_(messages|users|account|contacts|upload|auth|phone|channels|payments|stickers|bots|stories|help|folders|langpack)_.*")) {
            // Unknown writes fail closed as Telegram adds methods. Only read/search/file-download
            // requests are permitted while a simulation owns the account.
            boolean read = name.matches("TL_.*_(get|search|resolve|check)[A-Z].*")
                || name.equals("TL_upload_reuploadCdnFile");
            if (!read || name.contains("BotCallbackAnswer")) return true;
        }
        return name.startsWith("TL_messages_send") || name.startsWith("TL_messages_forward")
            || name.startsWith("TL_messages_edit") || name.startsWith("TL_messages_delete")
            || name.startsWith("TL_messages_read") || name.startsWith("TL_messages_save")
            || name.contains("setTyping") || name.contains("sendEncrypted") || name.contains("sendScreenshot")
            || name.startsWith("TL_upload_save") || name.startsWith("TL_contacts_block")
            || name.startsWith("TL_contacts_unblock") || name.startsWith("TL_phone_")
            || name.equals("TL_account_updateStatus") || name.equals("TL_sendMessage")
            || name.equals("TL_messages_startBot");
    }
    public static boolean suppress(Object request, boolean active) {
        return walk(request, active, 0, new IdentityHashMap<>());
    }
    private static boolean walk(Object object, boolean active, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (object == null || visited.put(object, true) != null) return false;
        if (depth > 20) return object instanceof Iterable || object.getClass().getName().startsWith("org.telegram.tgnet.");
        if (object instanceof Iterable) {
            for (Object value : (Iterable<?>) object) if (walk(value, active, depth + 1, visited)) return true;
            return false;
        }
        if (!object.getClass().getName().startsWith("org.telegram.tgnet.")) return false;
        String name = object.getClass().getSimpleName();
        if (active && mutation(name)) return true;
        for (Field field : object.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            try {
                Object value = field.get(object);
                if (field.getName().equals("user_id") && value instanceof Long && virtual((Long) value)) return true;
                // Negative local IDs are never valid server deletion targets, even after exit.
                if (name.equals("TL_messages_deleteMessages") && field.getName().equals("id") && value instanceof Iterable)
                    for (Object id : (Iterable<?>) value) if (id instanceof Integer && (Integer) id < 0) return true;
                if (walk(value, active, depth + 1, visited)) return true;
            } catch (IllegalAccessException error) { return true; }
        }
        return false;
    }
}
