package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;

/** Notification delivery history, independent of Telegram's message/reaction cache. */
public final class GhostReactionEvents {
    private final int capacity;
    private final LinkedHashSet<String> delivered = new LinkedHashSet<>();

    public GhostReactionEvents(int capacity, Collection<String> saved) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
        for (String key : saved) record(key);
    }

    public synchronized boolean contains(String key) {
        return delivered.contains(key);
    }

    public synchronized boolean record(String key) {
        if (!delivered.add(key)) return false;
        while (delivered.size() > capacity) delivered.remove(delivered.iterator().next());
        return true;
    }

    public synchronized ArrayList<String> snapshot() {
        return new ArrayList<>(delivered);
    }
}
