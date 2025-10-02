package com.osman.core.json;

import com.osman.core.model.OrderInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Parses Amazon Custom JSON payloads and produces a shared {@link OrderPayload} instance.
 */
public final class JsonOrderLoader {
    private JsonOrderLoader() {
    }

    public static OrderPayload load(File jsonFile, File orderDirectory) throws IOException {
        if (jsonFile == null || orderDirectory == null) {
            throw new IllegalArgumentException("JSON file and order directory are required");
        }

        String content = Files.readString(jsonFile.toPath());
        JSONObject root = new JSONObject(content);

        String orderId = root.optString("orderId");
        if (orderId.isEmpty()) {
            throw new IOException("JSON is missing 'orderId' field.");
        }
        String orderItemId = root.optString("orderItemId");
        if (orderItemId.isEmpty()) {
            throw new IOException("JSON is missing 'orderItemId' field.");
        }

        String fontName = findFontFamily(root);
        if (fontName == null) {
            fontName = "Arial";
        }

        String label = findLabel(root);
        if (label == null) {
            throw new IOException("Could not find label information in JSON.");
        }

        int quantity = root.optInt("quantity", 1);
        String customerName = orderDirectory.getName();

        OrderInfo info = new OrderInfo(orderId, customerName, fontName, quantity, orderItemId, label);
        int totalQuantity = QuantityCalculator.calculate(orderDirectory);
        String designSide = DesignSideResolver.resolveDesignSide(root);
        ImageFileInfo images = readImageFileNames(jsonFile);
        int mugOunces = readMugOunces(root);

        return new OrderPayload(info, totalQuantity, designSide, images, mugOunces);
    }

    public static ImageFileInfo readImageFileNames(File jsonFile) throws IOException {
        String content = Files.readString(jsonFile.toPath());
        JSONObject root = new JSONObject(content);
        return readImageFileNames(root);
    }

    private static ImageFileInfo readImageFileNames(JSONObject root) {
        String[] names = {null, null};
        if (root.has("customizationData")) {
            JSONObject customizationData = root.getJSONObject("customizationData");
            if (customizationData.has("children")) {
                findImageFilesRecursively(customizationData.getJSONArray("children"), names);
            }
        }
        return new ImageFileInfo(names[0], names[1]);
    }

    private static void findImageFilesRecursively(JSONArray array, String[] fileNames) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject node = array.optJSONObject(i);
            if (node == null) {
                continue;
            }
            String type = node.optString("type");
            if ("ImageCustomization".equals(type)) {
                JSONObject image = node.optJSONObject("image");
                String imageName = (image != null) ? image.optString("imageName", null) : null;
                if (imageName != null && !imageName.isBlank()) {
                    String name = node.optString("name", "").toLowerCase(Locale.ROOT);
                    String label = node.optString("label", "").toLowerCase(Locale.ROOT);
                    if (name.contains("front") || label.contains("front")) {
                        fileNames[0] = imageName;
                    }
                    if (name.contains("back") || label.contains("back")) {
                        fileNames[1] = imageName;
                    }
                }
            }
            if (node.has("children")) {
                findImageFilesRecursively(node.getJSONArray("children"), fileNames);
            }
        }
    }

    private static int readMugOunces(JSONObject root) {
        try {
            if (root.has("version3.0")) {
                JSONObject v3 = root.getJSONObject("version3.0");
                if (v3.has("customizationInfo")) {
                    JSONObject ci = v3.getJSONObject("customizationInfo");
                    if (ci.has("surfaces")) {
                        JSONArray surfaces = ci.getJSONArray("surfaces");
                        for (int i = 0; i < surfaces.length(); i++) {
                            JSONObject surface = surfaces.optJSONObject(i);
                            if (surface == null) {
                                continue;
                            }
                            String name = surface.optString("name", "");
                            int oz = parseOz(name);
                            if (oz > 0) {
                                return oz;
                            }
                        }
                    }
                }
            }
        } catch (JSONException ignored) {
        }

        try {
            if (root.has("customizationData")) {
                JSONObject cd = root.getJSONObject("customizationData");
                if (cd.has("children")) {
                    JSONArray children = cd.getJSONArray("children");
                    for (int i = 0; i < children.length(); i++) {
                        JSONObject child = children.optJSONObject(i);
                        if (child == null) {
                            continue;
                        }
                        String label = child.optString("label", "");
                        int oz = parseOz(label);
                        if (oz > 0) {
                            return oz;
                        }
                    }
                }
            }
        } catch (JSONException ignored) {
        }

        String title = root.optString("title", "");
        int oz = parseOz(title);
        if (oz > 0) {
            return oz;
        }
        return 11;
    }

    private static int parseOz(String value) {
        if (value == null) {
            return -1;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("15") && normalized.contains("oz")) {
            return 15;
        }
        if (normalized.contains("11") && normalized.contains("oz")) {
            return 11;
        }
        if (normalized.matches(".*\\b15\\b.*")) {
            return 15;
        }
        if (normalized.matches(".*\\b11\\b.*")) {
            return 11;
        }
        return -1;
    }

    private static String findLabel(JSONObject root) {
        if (!root.has("customizationData")) {
            return null;
        }
        JSONObject customizationData = root.getJSONObject("customizationData");
        if (!customizationData.has("children")) {
            return null;
        }
        JSONArray children = customizationData.getJSONArray("children");
        if (children.length() == 0) {
            return null;
        }
        JSONObject preview = children.optJSONObject(0);
        if (preview == null) {
            return null;
        }
        return preview.optString("label", null);
    }

    private static String findFontFamily(JSONObject root) {
        if (!root.has("customizationData")) {
            return null;
        }
        JSONObject customizationData = root.getJSONObject("customizationData");
        if (!customizationData.has("children")) {
            return null;
        }
        return findFontRecursively(customizationData.getJSONArray("children"));
    }

    private static String findFontRecursively(JSONArray array) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject node = array.optJSONObject(i);
            if (node == null) {
                continue;
            }
            if ("FontCustomization".equals(node.optString("type"))) {
                JSONObject selection = node.optJSONObject("fontSelection");
                if (selection != null && selection.has("family")) {
                    return selection.getString("family");
                }
            }
            if (node.has("children")) {
                String found = findFontRecursively(node.getJSONArray("children"));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public record ImageFileInfo(String frontImageFile, String backImageFile) {
    }
}
