package com.osman;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

public class JsonDataReader {

    // YENİ: Resim dosyası adlarını tutan veri sınıfı
    public record ImageFileInfo(String frontImageFile, String backImageFile) {}

    /**
     * YENİ METOD: JSON'u tarar ve ön/arka yüzdeki resimlerin dosya adlarını döndürür.
     */
    public static ImageFileInfo readImageFileNames(File jsonFile) throws IOException {
        String content = Files.readString(jsonFile.toPath());
        JSONObject root = new JSONObject(content);
        String[] fileNames = {null, null}; // Index 0: Front, Index 1: Back

        if (root.has("customizationData")) {
            JSONObject customizationData = root.getJSONObject("customizationData");
            if (customizationData.has("children")) {
                findImageFilesRecursively(customizationData.getJSONArray("children"), fileNames);
            }
        }
        return new ImageFileInfo(fileNames[0], fileNames[1]);
    }

    /**
     * YENİ YARDIMCI METOD: JSON ağacında gezinerek resim dosyası adlarını bulur.
     */
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
                    if (name.contains("front") || label.contains("front")) {
                        fileNames[0] = imageName; // Ön yüz resmi bulundu
                    }
                    if (name.contains("back") || label.contains("back")) {
                        fileNames[1] = imageName; // Arka yüz resmi bulundu
                    }
                }
            }

            if (node.has("children")) {
                findImageFilesRecursively(node.getJSONArray("children"), fileNames);
            }
        }
    }
    public static int readMugOunces(File jsonFile) throws IOException {
        String content = Files.readString(jsonFile.toPath());
        JSONObject root = new JSONObject(content);

        // 1) v3 surfaces name
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

        // 2) preview label
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

        // 3) title fallback
        String title = root.optString("title", "");
        int oz = parseOzFromString(title);
        if (oz > 0) return oz;

        // Varsayılan: 11 oz
        return 11;
    }

    private static int parseOzFromString(String s) {
        if (s == null) return -1;
        String t = s.toLowerCase(Locale.ROOT);
        if (t.contains("15") && t.contains("oz")) return 15;
        if (t.contains("11") && t.contains("oz")) return 11;
        // Bazı listelerde "11 WHITE" / "15 WHITE" gibi yazımlar:
        if (t.matches(".*\\b15\\b.*")) return 15;
        if (t.matches(".*\\b11\\b.*")) return 11;
        return -1;
    }


    // --- MEVCUT DİĞER METODLAR (DEĞİŞİKLİK YOK) ---
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