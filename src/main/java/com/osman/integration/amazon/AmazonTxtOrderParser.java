package com.osman.integration.amazon;

import com.osman.logging.AppLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Parses Amazon TXT order exports (tab-separated) and converts them into {@link AmazonOrderRecord}s.
 */
public class AmazonTxtOrderParser {
    private static final Logger LOGGER = AppLogger.get();

    private static final String HEADER_ORDER_ID = "order-id";
    private static final String HEADER_ORDER_ITEM_ID = "order-item-id";
    private static final String HEADER_BUYER_NAME = "buyer-name";
    private static final String HEADER_SKU = "sku";
    private static final String HEADER_CUSTOM_URL = "customized-url";

    /**
     * Parse the provided file path.
     *
     * @param file source TXT file (tab separated)
     * @return list of parsed order records
     * @throws IOException if the file cannot be read
     */
    public List<AmazonOrderRecord> parse(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    /**
     * Parse the provided reader (expects tab separated values with headers).
     */
    public List<AmazonOrderRecord> parse(Reader reader) throws IOException {
        try (BufferedReader buffered = new BufferedReader(reader)) {
            String headerLine = buffered.readLine();
            if (headerLine == null) {
                return List.of();
            }

            List<String> headers = splitLine(headerLine);
            Map<String, Integer> headerIndex = mapHeaderIndexes(headers);
            validateRequiredHeaders(headerIndex.keySet());

            List<AmazonOrderRecord> records = new ArrayList<>();
            String line;
            int rowIndex = 1;

            while ((line = buffered.readLine()) != null) {
                rowIndex++;
                if (line.isBlank()) {
                    continue;
                }

                List<String> columns = splitLine(line);
                try {
                    AmazonOrderRecord record = toRecord(columns, headerIndex);
                    records.add(record);
                } catch (IllegalArgumentException ex) {
                    LOGGER.warning("Skipping row %d: %s".formatted(rowIndex, ex.getMessage()));
                }
            }

            return records;
        }
    }

    private AmazonOrderRecord toRecord(List<String> columns, Map<String, Integer> headerIndex) {
        String orderId = getValue(columns, headerIndex, HEADER_ORDER_ID);
        String orderItemId = getValue(columns, headerIndex, HEADER_ORDER_ITEM_ID);
        String buyerName = getValue(columns, headerIndex, HEADER_BUYER_NAME);
        String rawSku = getValue(columns, headerIndex, HEADER_SKU);
        String downloadUrl = getValue(columns, headerIndex, HEADER_CUSTOM_URL);

        if (downloadUrl.startsWith("https://a.co")) {
            // Usually the download link we need lives under customized-url, but fall back to the page url if needed.
            LOGGER.info("Row for orderItemId %s references product page URL; keeping value as-is.".formatted(orderItemId));
        }

        String normalizedItemType = normalizeItemType(rawSku);

        return new AmazonOrderRecord(
            orderId.trim(),
            orderItemId.trim(),
            buyerName.trim(),
            rawSku.trim(),
            normalizedItemType,
            downloadUrl.trim()
        );
    }

    private static List<String> splitLine(String line) {
        return Arrays.stream(line.split("\t", -1))
            .map(value -> value == null ? "" : value)
            .collect(Collectors.toList());
    }

    private static Map<String, Integer> mapHeaderIndexes(List<String> headers) {
        Map<String, Integer> indexMap = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (header == null) continue;
            String normalized = header.trim().toLowerCase(Locale.ROOT);
            indexMap.putIfAbsent(normalized, i);
        }
        return indexMap;
    }

    private static void validateRequiredHeaders(Iterable<String> headers) {
        List<String> missing = new ArrayList<>();
        List<String> required = List.of(
            HEADER_ORDER_ID,
            HEADER_ORDER_ITEM_ID,
            HEADER_BUYER_NAME,
            HEADER_SKU,
            HEADER_CUSTOM_URL
        );

        for (String requiredHeader : required) {
            boolean present = false;
            for (String header : headers) {
                if (Objects.equals(requiredHeader, header)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                missing.add(requiredHeader);
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing required columns: " + missing);
        }
    }

    private static String getValue(List<String> columns, Map<String, Integer> headerIndex, String key) {
        Integer index = headerIndex.get(key);
        if (index == null || index < 0 || index >= columns.size()) {
            throw new IllegalArgumentException("Missing value for column '%s'".formatted(key));
        }

        String value = columns.get(index);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Empty value for column '%s'".formatted(key));
        }

        return value;
    }

    /**
     * Converts a full SKU into a canonical item type token used for grouping.
     * <p>
     * Strategy:
     * <ul>
     *     <li>Take the segment after the last '-' or '_' (whichever is later in the string).</li>
     *     <li>Strip any characters that are not alpha-numeric.</li>
     *     <li>Upper-case the result for consistent grouping.</li>
     * </ul>
     */
    public String normalizeItemType(String sku) {
        if (sku == null || sku.isBlank()) {
            return "UNKNOWN";
        }

        String trimmed = sku.trim();
        int lastHyphen = trimmed.lastIndexOf('-');
        int lastUnderscore = trimmed.lastIndexOf('_');
        int separatorIndex = Math.max(lastHyphen, lastUnderscore);
        String candidate = separatorIndex >= 0 ? trimmed.substring(separatorIndex + 1) : trimmed;
        candidate = candidate.replace('.', '-'); // keep delimited segments consistent

        String alphanumeric = candidate.chars()
            .filter(Character::isLetterOrDigit)
            .mapToObj(c -> String.valueOf((char) c))
            .collect(Collectors.joining());

        if (alphanumeric.isBlank()) {
            return trimmed.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        }

        return alphanumeric.toUpperCase(Locale.ROOT);
    }
}
