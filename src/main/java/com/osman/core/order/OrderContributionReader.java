package com.osman.core.order;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Utility for reading Amazon Custom JSON exports and extracting order contribution details.
 */
public final class OrderContributionReader {

    private OrderContributionReader() {
    }

    /**
     * Reads a single JSON file and extracts order + item quantity information.
     * @param jsonFile JSON file exported from Amazon Custom.
     * @return an {@link OrderContribution} or {@code null} if the file does not contain the expected fields.
     */
    public static OrderContribution readFromFile(File jsonFile) {
        if (jsonFile == null || !jsonFile.isFile()) {
            return null;
        }
        try {
            String content = Files.readString(jsonFile.toPath());
            JSONObject root = new JSONObject(content);
            String orderId = root.optString("orderId", "").trim();
            String itemId = root.optString("orderItemId", "").trim();
            if (orderId.isEmpty() || itemId.isEmpty()) {
                return null;
            }
            int quantity = Math.max(root.optInt("quantity", 1), 1);
            return new OrderContribution(orderId, itemId, quantity);
        } catch (IOException | JSONException ignored) {
            return null;
        }
    }

    /**
     * Recursively walks a folder and pulls all detectable order contributions.
     *
     * @param folder   folder that may contain Amazon Custom JSON exports.
     * @param maxDepth maximum depth to scan (inclusive).
     * @return list of contributions (possibly empty).
     */
    public static List<OrderContribution> readAllFromFolder(File folder, int maxDepth) {
        if (folder == null || !folder.isDirectory()) {
            return List.of();
        }
        List<OrderContribution> contributions = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(folder.toPath(), Math.max(0, maxDepth))) {
            stream.filter(Files::isRegularFile)
                .filter(OrderContributionReader::isPotentialOrderJson)
                .map(Path::toFile)
                .map(OrderContributionReader::readFromFile)
                .filter(Objects::nonNull)
                .forEach(contributions::add);
        } catch (IOException ignored) {
        }
        return contributions;
    }

    private static boolean isPotentialOrderJson(Path path) {
        String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".json")) {
            return false;
        }
        return !lower.equals(OrderQuantitiesManifest.DEFAULT_FILENAME);
    }
}
