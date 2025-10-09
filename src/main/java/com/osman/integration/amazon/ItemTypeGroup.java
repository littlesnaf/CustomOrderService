package com.osman.integration.amazon;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stores customer groupings for a given item type (e.g. 11B, 11W).
 */
public final class ItemTypeGroup {
    private final String itemType;
    private final Map<String, CustomerGroup> customersBySanitizedName;

    ItemTypeGroup(String itemType, Map<String, CustomerGroup> customersBySanitizedName) {
        this.itemType = itemType;
        this.customersBySanitizedName = Collections.unmodifiableMap(customersBySanitizedName);
    }

    public String itemType() {
        return itemType;
    }

    public Map<String, CustomerGroup> customers() {
        return customersBySanitizedName;
    }

    public boolean isEmpty() {
        return customersBySanitizedName.isEmpty();
    }

    public static ItemTypeGroup empty(String itemType) {
        return new ItemTypeGroup(itemType, new LinkedHashMap<>());
    }
}
