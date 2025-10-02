package com.osman.core.json;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Shared logic for counting the total quantity for single and multi-design orders.
 */
public final class QuantityCalculator {
    private QuantityCalculator() {
    }

    public static int calculate(File orderRoot) {
        if (orderRoot == null || !orderRoot.isDirectory()) {
            return 1;
        }

        File customerFolder;
        if (isDesignIdentifier(orderRoot.getName())) {
            customerFolder = orderRoot.getParentFile();
        } else {
            customerFolder = orderRoot;
        }

        if (customerFolder == null || !customerFolder.isDirectory()) {
            return 1;
        }

        File[] designFolders = customerFolder.listFiles(file -> file.isDirectory() && folderContainsJson(file));
        if (designFolders != null && designFolders.length > 0) {
            int totalQuantity = 0;
            for (File subfolder : designFolders) {
                File jsonFile = findFirstJsonIn(subfolder);
                totalQuantity += readQuantity(jsonFile);
            }
            return Math.max(totalQuantity, 1);
        }

        File jsonFile = findFirstJsonIn(customerFolder);
        return Math.max(readQuantity(jsonFile), 1);
    }

    private static boolean isDesignIdentifier(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String baseName = name.replaceAll("\\s*\\(x\\d+\\)$", "").trim();
        return baseName.matches("\\d+");
    }

    private static boolean folderContainsJson(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return false;
        }
        File[] jsonFiles = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        return jsonFiles != null && jsonFiles.length > 0;
    }

    private static File findFirstJsonIn(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        File[] jsonFiles = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (jsonFiles != null && jsonFiles.length > 0) {
            return jsonFiles[0];
        }
        return null;
    }

    private static int readQuantity(File jsonFile) {
        if (jsonFile == null) {
            return 0;
        }
        try {
            String content = Files.readString(jsonFile.toPath());
            JSONObject root = new JSONObject(content);
            return root.optInt("quantity", 1);
        } catch (IOException | JSONException e) {
            return 1;
        }
    }
}
