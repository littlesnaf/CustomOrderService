package com.osman;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonDataReader {

    /**
     * FINAL CORRECTED METHOD: Uses the folder name convention (numeric vs. text)
     * to distinguish between single and multi-design orders and calculates the quantity correctly.
     * @param orderRoot The folder containing the JSON file being processed.
     * @return The correct total order quantity.
     */
    public static int calculateTotalOrderQuantity(File orderRoot) {
        if (orderRoot == null || !orderRoot.isDirectory()) return 1;

        File customerFolder;
        // Check if the current folder name looks like a design identifier (e.g., "1", "8")
        if (isDesignIdentifier(orderRoot.getName())) {
            // This is a subfolder of a multi-design order (e.g., ".../Customer/8")
            customerFolder = orderRoot.getParentFile();
        } else {
            // This is a single-design order folder (e.g., ".../Micah Campbell")
            customerFolder = orderRoot;
        }

        if (customerFolder == null || !customerFolder.isDirectory()) return 1;

        // Find all design folders (folders with JSON) under the customer folder
        File[] designFolders = customerFolder.listFiles(file -> file.isDirectory() && folderContainsJson(file));

        if (designFolders != null && designFolders.length > 0) {
            // --- MULTI-DESIGN SCENARIO ---
            // We have subfolders like /1, /2, etc. Sum the quantities from the JSON in each.
            int totalQuantity = 0;
            for (File subfolder : designFolders) {
                File jsonFile = findFirstJsonIn(subfolder);
                totalQuantity += readQuantityFromJson(jsonFile);
            }
            return totalQuantity > 0 ? totalQuantity : 1;
        } else {
            // --- SINGLE-DESIGN SCENARIO ---
            // The customer folder itself contains the JSON.
            File jsonFile = findFirstJsonIn(customerFolder);
            return readQuantityFromJson(jsonFile);
        }
    }

    /**
     * HELPER: Checks if a folder name is a design identifier (numeric)
     * instead of a customer name.
     */
    private static boolean isDesignIdentifier(String name) {
        if (name == null || name.isBlank()) return false;
        String baseName = name.replaceAll("\\s*\\(x\\d+\\)$", "").trim();
        return baseName.matches("\\d+");
    }

    /**
     * HELPER: Checks if a directory directly contains any .json files.
     */
    private static boolean folderContainsJson(File directory) {
        if (directory == null || !directory.isDirectory()) return false;
        File[] jsonFiles = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        return jsonFiles != null && jsonFiles.length > 0;
    }

    /**
     * NEW HELPER: Finds the first .json file in a given directory.
     */
    private static File findFirstJsonIn(File directory) {
        if (directory == null || !directory.isDirectory()) return null;
        File[] jsonFiles = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (jsonFiles != null && jsonFiles.length > 0) {
            return jsonFiles[0];
        }
        return null;
    }

    /**
     * NEW HELPER: Reads the "quantity" value from a specific JSON file.
     */
    private static int readQuantityFromJson(File jsonFile) {
        if (jsonFile == null) return 0;
        try {
            String content = Files.readString(jsonFile.toPath());
            JSONObject root = new JSONObject(content);
            return root.optInt("quantity", 1); // Default to 1 if not found
        } catch (IOException | JSONException e) {
            System.err.println("Could not read quantity from " + jsonFile.getName() + ": " + e.getMessage());
            return 1; // Default to 1 on error
        }
    }

    // =================================================================================
    // YOUR OTHER EXISTING METHODS (NO CHANGES NEEDED BELOW THIS LINE)
    // =================================================================================

    public record ImageFileInfo(String frontImageFile, String backImageFile) {}

    public static ImageFileInfo readImageFileNames(File jsonFile) throws IOException {
        String content = Files.readString(jsonFile.toPath());
        JSONObject root = new JSONObject(content);
        String[] fileNames = {null, null};
        if (root.has("customizationData")) {
            JSONObject customizationData = root.getJSONObject("customizationData");
            if (customizationData.has("children")) {
                findImageFilesRecursively(customizationData.getJSONArray("children"), fileNames);
            }
        }
        return new ImageFileInfo(fileNames[0], fileNames[1]);
    }

    private static void findImageFilesRecursively(JSONArray array, String[] fileNames) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject node = array.optJSONObject(i);
            if (node == null) continue;
            String type = node.optString("type");
            if ("ImageCustomization".equals(type)) {
                JSONObject image = node.optJSONObject("image");
                String imageName = (image != null) ? image.optString("imageName", null) : null;
                if (imageName != null && !imageName.isBlank()) {
                    String name = node.optString("name", "").toLowerCase(Locale.ROOT);
                    String label = node.optString("label", "").toLowerCase(Locale.ROOT);
                    if (name.contains("front") || label.contains("front")) fileNames[0] = imageName;
                    if (name.contains("back") || label.contains("back")) fileNames[1] = imageName;
                }
            }
            if (node.has("children")) findImageFilesRecursively(node.getJSONArray("children"), fileNames);
        }
    }

    public static int readMugOunces(File jsonFile) throws IOException {
        String content = Files.readString(jsonFile.toPath());
        JSONObject root = new JSONObject(content);
        try {
            if (root.has("version3.0")) {
                JSONObject v3 = root.getJSONObject("version3.0");
                if (v3.has("customizationInfo")) {
                    JSONObject ci = v3.getJSONObject("customizationInfo");
                    if (ci.has("surfaces")) {
                        JSONArray surfaces = ci.getJSONArray("surfaces");
                        for (int i = 0; i < surfaces.length(); i++) {
                            String name = surfaces.getJSONObject(i).optString("name", "");
                            int oz = parseOzFromString(name);
                            if (oz > 0) return oz;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            if (root.has("customizationData")) {
                JSONObject cd = root.getJSONObject("customizationData");
                if (cd.has("children")) {
                    JSONArray ch = cd.getJSONArray("children");
                    for (int i = 0; i < ch.length(); i++) {
                        String label = ch.getJSONObject(i).optString("label", "");
                        int oz = parseOzFromString(label);
                        if (oz > 0) return oz;
                    }
                }
            }
        } catch (Exception ignored) {}
        String title = root.optString("title", "");
        int oz = parseOzFromString(title);
        if (oz > 0) return oz;
        return 11;
    }

    private static int parseOzFromString(String s) {
        if (s == null) return -1;
        String t = s.toLowerCase(Locale.ROOT);
        if (t.contains("15") && t.contains("oz")) return 15;
        if (t.contains("11") && t.contains("oz")) return 11;
        if (t.matches(".*\\b15\\b.*")) return 15;
        if (t.matches(".*\\b11\\b.*")) return 11;
        return -1;
    }

    public static OrderInfo parse(File jsonFile, File orderDirectory) throws IOException { String content = Files.readString(jsonFile.toPath()); JSONObject root = new JSONObject(content); String orderId = root.optString("orderId"); if (orderId.isEmpty()) throw new IOException("JSON is missing 'orderId' field."); String orderItemId = root.optString("orderItemId"); if (orderItemId.isEmpty()) throw new IOException("JSON is missing 'orderItemId' field."); String fontName = findFontFamilyInCustomization(root); if (fontName == null) { fontName = "Arial"; } String label = findLabelInCustomization(root); if (label == null) throw new IOException("Could not find label information in JSON."); int quantity = root.optInt("quantity", 1); String customerName = orderDirectory.getName(); return new OrderInfo(orderId, customerName, fontName, quantity, orderItemId, label); }
    public static String readDesignSide(File jsonFile) throws IOException { String content = Files.readString(jsonFile.toPath()); JSONObject root = new JSONObject(content); String raw = findDesignSideInCustomization(root); if (isNonEmpty(raw)) return normalizeDesignSide(raw); raw = findDesignSideGeneric(root); if (isNonEmpty(raw)) return normalizeDesignSide(raw); raw = findDesignSideInV3(root); if (isNonEmpty(raw)) return normalizeDesignSide(raw); return "BOTH"; }
    private static boolean isNonEmpty(String s) { return s != null && !s.isBlank(); }
    private static String normalizeDesignSide(String raw) { String s = raw.toLowerCase(Locale.ROOT); if (s.contains("both")) return "BOTH"; if (s.contains("front")) return "FRONT_ONLY"; if (s.contains("back")) return "BACK_ONLY"; return "BOTH"; }
    private static String findLabelInCustomization(JSONObject root) { if (root.has("customizationData")) { JSONObject customizationData = root.getJSONObject("customizationData"); if (customizationData.has("children")) { JSONArray children = customizationData.getJSONArray("children"); if (children.length() > 0) { JSONObject previewContainer = children.getJSONObject(0); return previewContainer.optString("label", null); } } } return null; }
    private static String findFontFamilyInCustomization(JSONObject root) { if (root.has("customizationData")) { JSONObject customizationData = root.getJSONObject("customizationData"); if (customizationData.has("children")) { return findFontRecursively(customizationData.getJSONArray("children")); } } return null; }
    private static String findFontRecursively(JSONArray jsonArray) { for (int i = 0; i < jsonArray.length(); i++) { JSONObject currentObject = jsonArray.getJSONObject(i); if ("FontCustomization".equals(currentObject.optString("type"))) { JSONObject fontSelection = currentObject.optJSONObject("fontSelection"); if (fontSelection != null && fontSelection.has("family")) { return fontSelection.getString("family"); } } if (currentObject.has("children")) { String foundFont = findFontRecursively(currentObject.getJSONArray("children")); if (foundFont != null) return foundFont; } } return null; }
    private static String findDesignSideInCustomization(JSONObject root) { if (!root.has("customizationData")) return null; JSONObject customizationData = root.getJSONObject("customizationData"); if (!customizationData.has("children")) return null; return findDesignSideRecursively(customizationData.getJSONArray("children")); }
    private static String findDesignSideRecursively(JSONArray arr) { for (int i = 0; i < arr.length(); i++) { JSONObject node = arr.getJSONObject(i); if ("OptionCustomization".equals(node.optString("type")) && "Design Side".equalsIgnoreCase(node.optString("name"))) { JSONObject sel = node.optJSONObject("optionSelection"); if (sel != null) { String name = sel.optString("name", ""); String label = sel.optString("label", ""); if (!name.isBlank()) return name; if (!label.isBlank()) return label; } String dv = node.optString("displayValue", ""); if (!dv.isBlank()) return dv; } if (node.has("children")) { String found = findDesignSideRecursively(node.getJSONArray("children")); if (found != null) return found; } } return null; }
    private static String findDesignSideGeneric(JSONObject root) { if (!root.has("customizationData")) return null; JSONObject cd = root.getJSONObject("customizationData"); if (!cd.has("children")) return null; return scanOptionsRecursively(cd.getJSONArray("children")); }
    private static String scanOptionsRecursively(JSONArray arr) { for (int i = 0; i < arr.length(); i++) { JSONObject node = arr.getJSONObject(i); if ("OptionCustomization".equals(node.optString("type"))) { JSONObject sel = node.optJSONObject("optionSelection"); if (sel != null) { String name = sel.optString("name", ""); String label = sel.optString("label", ""); String v = !name.isBlank() ? name : label; if (isNonEmpty(v) && looksLikePrintedAreaValue(v)) return v; } String dv = node.optString("displayValue", ""); if (isNonEmpty(dv) && looksLikePrintedAreaValue(dv)) return dv; } if (node.has("children")) { String found = scanOptionsRecursively(node.getJSONArray("children")); if (found != null) return found; } } return null; }
    private static boolean looksLikePrintedAreaValue(String v) { String s = v.toLowerCase(Locale.ROOT); return s.contains("front") || s.contains("back") || s.contains("both"); }
    private static String findDesignSideInV3(JSONObject root) { if (!root.has("version3.0")) return null; JSONObject v3 = root.getJSONObject("version3.0"); if (!v3.has("customizationInfo")) return null; JSONObject ci = v3.getJSONObject("customizationInfo"); if (!ci.has("surfaces")) return null; JSONArray surfaces = ci.getJSONArray("surfaces"); for (int i = 0; i < surfaces.length(); i++) { JSONObject surf = surfaces.getJSONObject(i); if (!surf.has("areas")) continue; JSONArray areas = surf.getJSONArray("areas"); for (int j = 0; j < areas.length(); j++) { JSONObject area = areas.getJSONObject(j); if (!"Options".equalsIgnoreCase(area.optString("customizationType"))) continue; String val = area.optString("optionValue", ""); if (isNonEmpty(val) && looksLikePrintedAreaValue(val)) return val; } } return null; }
}