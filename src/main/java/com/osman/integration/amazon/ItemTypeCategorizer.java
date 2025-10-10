package com.osman.integration.amazon;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Provides helper utilities for classifying normalized Amazon item types.
 */
public final class ItemTypeCategorizer {

    public static final String MUGS_FOLDER_NAME = "Mugs";
    public static final String IMAGES_FOLDER_NAME = "Images";

    private static final Pattern MUG_PATTERN = Pattern.compile("^(\\d{2})([A-Z]{0,4})?$");

    private ItemTypeCategorizer() {
    }

    public enum Category {
        MUG_CUSTOM,
        MUG_STANDARD,
        OTHER
    }

    public static boolean isMug(String itemType) {
        if (itemType == null) {
            return false;
        }

        String normalized = itemType.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }

        if (normalized.contains("TMBLR") || normalized.contains("TUMBLER")) {
            return false;
        }

        if (MUG_PATTERN.matcher(normalized).matches()) {
            return true;
        }

        return normalized.endsWith("OZ") && normalized.length() <= 6 && normalized.matches(".*\\d.*");
    }

    public static Category resolveCategory(AmazonOrderRecord record) {
        if (record == null) {
            return Category.OTHER;
        }

        String itemType = record.normalizedItemType();
        if (!isMug(itemType)) {
            return Category.OTHER;
        }

        return record.customizable() ? Category.MUG_CUSTOM : Category.MUG_STANDARD;
    }

    public static Path resolveItemTypeFolder(Path batchRoot, ItemTypeGroup group) {
        return resolveItemTypeFolder(batchRoot, group.itemType());
    }

    public static Path resolveItemTypeFolder(Path batchRoot, String itemType) {
        return batchRoot.resolve(itemType);
    }

    public static Path resolveImagesFolder(Path itemTypeFolder) {
        return itemTypeFolder.resolve(IMAGES_FOLDER_NAME);
    }
}
