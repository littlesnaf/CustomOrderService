package com.osman;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Utility responsible for reading Amazon customization JSON files and translating them into
 * {@link OrderInfo} instances that drive the mug rendering pipeline.
 */
public class JsonDataReader {

    /**
     * Parses a single customization JSON file and builds an {@link OrderInfo} with the required metadata.
     *
     * @param jsonFile        the customization descriptor found under the order folder
     * @param orderDirectory  directory used to infer a fallback customer name
     * @return populated {@link OrderInfo}
     * @throws IOException if any of the mandatory fields are missing or unreadable
     */
    public static OrderInfo parse(File jsonFile, File orderDirectory) throws IOException {
        String content = Files.readString(jsonFile.toPath());
        JSONObject root = new JSONObject(content);

        String orderId = root.optString("orderId");
        if (orderId.isEmpty()) throw new IOException("JSON is missing 'orderId' field.");

        String orderItemId = root.optString("orderItemId");
        if (orderItemId.isEmpty()) throw new IOException("JSON is missing 'orderItemId' field.");

        String fontName = findFontFamilyInCustomization(root);
        if (fontName == null) throw new IOException("Could not find font information in JSON.");

        String label = findLabelInCustomization(root);
        if (label == null) throw new IOException("Could not find label information in JSON.");

        int quantity = root.optInt("quantity", 1);
        String customerName = orderDirectory.getName();

        return new OrderInfo(orderId, customerName, fontName, quantity, orderItemId, label);
    }
    public static class PlacementPresence {
        public final boolean frontHasImage;
        public final boolean backHasImage;
        public PlacementPresence(boolean frontHasImage, boolean backHasImage) {
            this.frontHasImage = frontHasImage;
            this.backHasImage = backHasImage;
        }
    }

    public static PlacementPresence detectFrontBackImagePresence(File jsonFile) throws IOException {
        String content = Files.readString(jsonFile.toPath());
        JSONObject root = new JSONObject(content);
        boolean front = hasImageForPlacement(root, "FRONT  IMAGE");
        boolean back  = hasImageForPlacement(root, "BACK IMAGE");
        return new PlacementPresence(front, back);
    }

    private static boolean hasImageForPlacement(JSONObject root, String placementName) {
        JSONObject customizationData = root.optJSONObject("customizationData");
        if (customizationData == null) return false;
        JSONArray children = customizationData.optJSONArray("children");
        if (children == null || children.isEmpty()) return false;

        return scanHasImage(children, placementName);
    }

    private static boolean scanHasImage(JSONArray arr, String placementName) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject node = arr.optJSONObject(i);
            if (node == null) continue;

            String type = node.optString("type", "");
            if ("PlacementContainerCustomization".equalsIgnoreCase(type)) {
                String name = node.optString("name", node.optString("label", ""));
                if (placementName.equalsIgnoreCase(name)) {
                    JSONArray kids = node.optJSONArray("children");
                    if (kids != null) {
                        for (int k = 0; k < kids.length(); k++) {
                            JSONObject kid = kids.optJSONObject(k);
                            if (kid == null) continue;
                            if ("ImageCustomization".equalsIgnoreCase(kid.optString("type", ""))) {
                                JSONObject image = kid.optJSONObject("image");
                                String imageName = image != null ? image.optString("imageName", "").trim() : "";
                                if (!imageName.isEmpty()) return true; // FOTO VAR
                            }
                        }
                    }
                }
            }

            if (node.has("children")) {
                if (scanHasImage(node.getJSONArray("children"), placementName)) return true;
            }
        }
        return false;
    }
    /**
     * Reads the "printed area" selection from JSON.
     * Returns: "BOTH", "FRONT_ONLY", or "BACK_ONLY". Defaults to "BOTH" if not found.
     */
    public static String readDesignSide(File jsonFile) throws IOException {
        String content = Files.readString(jsonFile.toPath());
        JSONObject root = new JSONObject(content);

        // 1) legacy tree (exact "Design Side")
        String raw = findDesignSideInCustomization(root);
        if (isNonEmpty(raw)) return normalizeDesignSide(raw);

        // 2) generic scan over any OptionCustomization in customizationData (name/label may vary)
        raw = findDesignSideGeneric(root);
        if (isNonEmpty(raw)) return normalizeDesignSide(raw);

        // 3) v3 schema support
        raw = findDesignSideInV3(root);
        if (isNonEmpty(raw)) return normalizeDesignSide(raw);

        return "BOTH";
    }

    // --------------------- helpers ---------------------

    private static boolean isNonEmpty(String s) { return s != null && !s.isBlank(); }

    private static String normalizeDesignSide(String raw) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.contains("both"))  return "BOTH";
        if (s.contains("front")) return "FRONT_ONLY";
        if (s.contains("back"))  return "BACK_ONLY";
        return "BOTH";
    }

    /**
     * Locates the visible label text in the customization tree.
     */
    private static String findLabelInCustomization(JSONObject root) {
        if (root.has("customizationData")) {
            JSONObject customizationData = root.getJSONObject("customizationData");
            if (customizationData.has("children")) {
                JSONArray children = customizationData.getJSONArray("children");
                if (children.length() > 0) {
                    JSONObject previewContainer = children.getJSONObject(0);
                    return previewContainer.optString("label", null);
                }
            }
        }
        return null;
    }

    /**
     * Traverses the customization structure to locate the selected font family.
     */
    private static String findFontFamilyInCustomization(JSONObject root) {
        if (root.has("customizationData")) {
            JSONObject customizationData = root.getJSONObject("customizationData");
            if (customizationData.has("children")) {
                return findFontRecursively(customizationData.getJSONArray("children"));
            }
        }
        return null;
    }

    /**
     * Recursively scans the customization nodes for a font selection entry.
     */
    private static String findFontRecursively(JSONArray jsonArray) {
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject currentObject = jsonArray.getJSONObject(i);
            if ("FontCustomization".equals(currentObject.optString("type"))) {
                JSONObject fontSelection = currentObject.optJSONObject("fontSelection");
                if (fontSelection != null && fontSelection.has("family")) {
                    return fontSelection.getString("family");
                }
            }
            if (currentObject.has("children")) {
                String foundFont = findFontRecursively(currentObject.getJSONArray("children"));
                if (foundFont != null) return foundFont;
            }
        }
        return null;
    }

    /**
     * Legacy: finds the "Design Side" option selection if node name is exactly "Design Side".
     * Returns the raw selection text.
     */
    private static String findDesignSideInCustomization(JSONObject root) {
        if (!root.has("customizationData")) return null;
        JSONObject customizationData = root.getJSONObject("customizationData");
        if (!customizationData.has("children")) return null;
        return findDesignSideRecursively(customizationData.getJSONArray("children"));
    }

    private static String findDesignSideRecursively(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject node = arr.getJSONObject(i);

            if ("OptionCustomization".equals(node.optString("type"))
                    && "Design Side".equalsIgnoreCase(node.optString("name"))) {

                JSONObject sel = node.optJSONObject("optionSelection");
                if (sel != null) {
                    String name = sel.optString("name", "");
                    String label = sel.optString("label", "");
                    if (!name.isBlank()) return name;
                    if (!label.isBlank()) return label;
                }
                String dv = node.optString("displayValue", "");
                if (!dv.isBlank()) return dv;
            }

            if (node.has("children")) {
                String found = findDesignSideRecursively(node.getJSONArray("children"));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Generic: scans any OptionCustomization regardless of its name/label.
     * Returns a value that looks like a printed-area choice.
     */
    private static String findDesignSideGeneric(JSONObject root) {
        if (!root.has("customizationData")) return null;
        JSONObject cd = root.getJSONObject("customizationData");
        if (!cd.has("children")) return null;
        return scanOptionsRecursively(cd.getJSONArray("children"));
    }

    private static String scanOptionsRecursively(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject node = arr.getJSONObject(i);

            if ("OptionCustomization".equals(node.optString("type"))) {
                JSONObject sel = node.optJSONObject("optionSelection");
                if (sel != null) {
                    String name  = sel.optString("name", "");
                    String label = sel.optString("label", "");
                    String v = !name.isBlank() ? name : label;
                    if (isNonEmpty(v) && looksLikePrintedAreaValue(v)) return v;
                }
                String dv = node.optString("displayValue", "");
                if (isNonEmpty(dv) && looksLikePrintedAreaValue(dv)) return dv;
            }

            if (node.has("children")) {
                String found = scanOptionsRecursively(node.getJSONArray("children"));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean looksLikePrintedAreaValue(String v) {
        String s = v.toLowerCase(Locale.ROOT);
        return s.contains("front") || s.contains("back") || s.contains("both");
        // Covers: "Print FRONT of a mug ONLY", "Print BACK ...", "Print on BOTH sides ..."
    }

    /**
     * version3.0 support: reads optionValue from areas with customizationType="Options".
     */
    private static String findDesignSideInV3(JSONObject root) {
        if (!root.has("version3.0")) return null;
        JSONObject v3 = root.getJSONObject("version3.0");
        if (!v3.has("customizationInfo")) return null;
        JSONObject ci = v3.getJSONObject("customizationInfo");
        if (!ci.has("surfaces")) return null;

        JSONArray surfaces = ci.getJSONArray("surfaces");
        for (int i = 0; i < surfaces.length(); i++) {
            JSONObject surf = surfaces.getJSONObject(i);
            if (!surf.has("areas")) continue;
            JSONArray areas = surf.getJSONArray("areas");
            for (int j = 0; j < areas.length(); j++) {
                JSONObject area = areas.getJSONObject(j);
                if (!"Options".equalsIgnoreCase(area.optString("customizationType"))) continue;
                String val = area.optString("optionValue", "");
                if (isNonEmpty(val) && looksLikePrintedAreaValue(val)) return val;
            }
        }
        return null;
    }
}
