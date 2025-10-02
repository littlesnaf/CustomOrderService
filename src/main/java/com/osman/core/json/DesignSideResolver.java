package com.osman.core.json;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Resolves which side(s) of the mug should be printed based on customization metadata.
 */
public final class DesignSideResolver {
    private DesignSideResolver() {
    }

    public static String resolveDesignSide(JSONObject root) {
        String value = findDesignSideInCustomization(root);
        if (isNonEmpty(value)) {
            return normalize(value);
        }
        value = findDesignSideGeneric(root);
        if (isNonEmpty(value)) {
            return normalize(value);
        }
        value = findDesignSideInV3(root);
        if (isNonEmpty(value)) {
            return normalize(value);
        }
        return "BOTH";
    }

    private static boolean isNonEmpty(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.contains("both")) {
            return "BOTH";
        }
        if (s.contains("front")) {
            return "FRONT_ONLY";
        }
        if (s.contains("back")) {
            return "BACK_ONLY";
        }
        return "BOTH";
    }

    private static String findDesignSideInCustomization(JSONObject root) {
        if (!root.has("customizationData")) {
            return null;
        }
        JSONObject customizationData = root.getJSONObject("customizationData");
        if (!customizationData.has("children")) {
            return null;
        }
        return findDesignSideRecursively(customizationData.getJSONArray("children"));
    }

    private static String findDesignSideRecursively(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject node = arr.optJSONObject(i);
            if (node == null) {
                continue;
            }
            if ("OptionCustomization".equals(node.optString("type")) &&
                    "Design Side".equalsIgnoreCase(node.optString("name"))) {
                JSONObject sel = node.optJSONObject("optionSelection");
                if (sel != null) {
                    String name = sel.optString("name", "");
                    String label = sel.optString("label", "");
                    if (!name.isBlank()) {
                        return name;
                    }
                    if (!label.isBlank()) {
                        return label;
                    }
                }
                String displayValue = node.optString("displayValue", "");
                if (!displayValue.isBlank()) {
                    return displayValue;
                }
            }
            if (node.has("children")) {
                String value = findDesignSideRecursively(node.getJSONArray("children"));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String findDesignSideGeneric(JSONObject root) {
        if (!root.has("customizationData")) {
            return null;
        }
        JSONObject cd = root.getJSONObject("customizationData");
        if (!cd.has("children")) {
            return null;
        }
        return scanOptionsRecursively(cd.getJSONArray("children"));
    }

    private static String scanOptionsRecursively(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject node = arr.optJSONObject(i);
            if (node == null) {
                continue;
            }
            if ("OptionCustomization".equals(node.optString("type"))) {
                JSONObject sel = node.optJSONObject("optionSelection");
                if (sel != null) {
                    String name = sel.optString("name", "");
                    String label = sel.optString("label", "");
                    String value = !name.isBlank() ? name : label;
                    if (looksLikePrintedAreaValue(value)) {
                        return value;
                    }
                }
                String displayValue = node.optString("displayValue", "");
                if (looksLikePrintedAreaValue(displayValue)) {
                    return displayValue;
                }
            }
            if (node.has("children")) {
                String value = scanOptionsRecursively(node.getJSONArray("children"));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static boolean looksLikePrintedAreaValue(String value) {
        if (value == null) {
            return false;
        }
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains("front") || s.contains("back") || s.contains("both");
    }

    private static String findDesignSideInV3(JSONObject root) {
        if (!root.has("version3.0")) {
            return null;
        }
        JSONObject v3 = root.getJSONObject("version3.0");
        if (!v3.has("customizationInfo")) {
            return null;
        }
        JSONObject ci = v3.getJSONObject("customizationInfo");
        if (!ci.has("surfaces")) {
            return null;
        }
        JSONArray surfaces = ci.getJSONArray("surfaces");
        for (int i = 0; i < surfaces.length(); i++) {
            JSONObject surf = surfaces.optJSONObject(i);
            if (surf == null || !surf.has("areas")) {
                continue;
            }
            JSONArray areas = surf.getJSONArray("areas");
            for (int j = 0; j < areas.length(); j++) {
                JSONObject area = areas.optJSONObject(j);
                if (area == null) {
                    continue;
                }
                if (!"Options".equalsIgnoreCase(area.optString("customizationType"))) {
                    continue;
                }
                String value = area.optString("optionValue", "");
                if (looksLikePrintedAreaValue(value)) {
                    return value;
                }
            }
        }
        return null;
    }
}
