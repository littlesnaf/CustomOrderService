package com.osman;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class JsonDataReader {

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

    private static String findFontFamilyInCustomization(JSONObject root) {
        if (root.has("customizationData")) {
            JSONObject customizationData = root.getJSONObject("customizationData");
            if (customizationData.has("children")) {
                return findFontRecursively(customizationData.getJSONArray("children"));
            }
        }
        return null;
    }

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
}
