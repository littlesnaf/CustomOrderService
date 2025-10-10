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
    private final ItemTypeCategorizer.Category category;

    ItemTypeGroup(String itemType,
                  ItemTypeCategorizer.Category category,
                  Map<String, CustomerGroup> customersBySanitizedName) {
        this.itemType = itemType;
        this.category = category;
        this.customersBySanitizedName = Collections.unmodifiableMap(customersBySanitizedName);
    }

    public String itemType() {
        return itemType;
    }

    public Map<String, CustomerGroup> customers() {
        return customersBySanitizedName;
    }

    public ItemTypeCategorizer.Category category() {
        return category;
    }

    public boolean isEmpty() {
        return customersBySanitizedName.isEmpty();
    }

    public static ItemTypeGroup empty(String itemType) {
        return new ItemTypeGroup(itemType, ItemTypeCategorizer.Category.OTHER, new LinkedHashMap<>());
    }
}
