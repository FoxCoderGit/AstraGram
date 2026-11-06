package org.telegram.messenger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable GhostGram assignments. No default users or third-party lists. */
public final class GhostBadgeRegistry {
    // Local rendering keys, never Telegram document IDs or verification flags.
    public static final long DEV = -10601L, SUPPORTER = -10602L;
    public enum Type { DEVELOPER, SUPPORTER, EXTERA_SUPPORTER }
    public static final class Badge {
        public final Type type;
        public final long documentId;
        public Badge(Type type) {
            if (type == null || type == Type.EXTERA_SUPPORTER) throw new IllegalArgumentException("Missing local badge type");
            this.type = type;
            documentId = type == Type.DEVELOPER ? DEV : SUPPORTER;
        }
        public Badge(Type type,long documentId){
            if(type!=Type.EXTERA_SUPPORTER||documentId<=0)throw new IllegalArgumentException("Invalid external badge");
            this.type=type;this.documentId=documentId;
        }
    }
    private final Map<Long, Badge> users;
    public GhostBadgeRegistry(Map<Long, Type> assignments) {
        Map<Long, Badge> clean = new HashMap<>();
        for (Map.Entry<Long, Type> entry : assignments.entrySet()) {
            if (entry.getKey() == null || entry.getKey() <= 0) throw new IllegalArgumentException("Invalid user ID");
            clean.put(entry.getKey(), new Badge(entry.getValue()));
        }
        users = Collections.unmodifiableMap(clean);
    }
    public Badge user(long id) { return users.get(id); }
    public static boolean isLocal(long key) { return key == DEV || key == SUPPORTER; }
}
