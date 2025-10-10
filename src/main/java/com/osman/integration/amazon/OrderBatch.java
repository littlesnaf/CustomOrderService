package com.osman.integration.amazon;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory representation of a parsed batch grouped by item type and customer/order.
 */
public final class OrderBatch {
    private final Instant createdAt;
    private final Map<String, ItemTypeGroup> groups;

    OrderBatch(Map<String, ItemTypeGroup> groups) {
        this.createdAt = Instant.now();
        this.groups = Collections.unmodifiableMap(groups);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Map<String, ItemTypeGroup> groups() {
        return groups;
    }

    public List<String> itemTypeKeys() {
        return new ArrayList<>(groups.keySet());
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    public ItemTypeGroup group(String itemType) {
        if (itemType == null) {
            return null;
        }
        for (ItemTypeGroup group : groups.values()) {
            if (itemType.equals(group.itemType())) {
                return group;
            }
        }
        return null;
    }

    public static OrderBatch empty() {
        return new OrderBatch(new LinkedHashMap<>());
    }
}
