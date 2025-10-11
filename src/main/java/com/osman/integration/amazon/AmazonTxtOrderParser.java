package com.osman.integration.amazon;

import com.osman.logging.AppLogger;
import com.osman.integration.amazon.ItemTypeCategorizer.Category;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Parses Amazon TXT order exports (tab-separated) and converts them into {@link AmazonOrderRecord}s.
 */
public class AmazonTxtOrderParser {
    private static final Logger LOGGER = AppLogger.get();

    private static final String HEADER_ORDER_ID = "order-id";
    private static final String HEADER_ORDER_ITEM_ID = "order-item-id";
    private static final String HEADER_PURCHASE_DATE = "purchase-date";
    private static final String HEADER_BUYER_NAME = "buyer-name";
    private static final String HEADER_BUYER_EMAIL = "buyer-email";
    private static final String HEADER_BUYER_PHONE = "buyer-phone-number";
    private static final String HEADER_SKU = "sku";
    private static final String HEADER_CUSTOM_URL = "customized-url";
    private static final String HEADER_PRODUCT_NAME = "product-name";
    private static final String HEADER_QUANTITY_PURCHASED = "quantity-purchased";
    private static final String HEADER_QUANTITY_SHIPPED = "quantity-shipped";
    private static final String HEADER_RECIPIENT_NAME = "recipient-name";
    private static final String HEADER_SHIP_ADDRESS1 = "ship-address-1";
    private static final String HEADER_SHIP_ADDRESS2 = "ship-address-2";
    private static final String HEADER_SHIP_ADDRESS3 = "ship-address-3";
    private static final String HEADER_SHIP_CITY = "ship-city";
    private static final String HEADER_SHIP_STATE = "ship-state";
    private static final String HEADER_SHIP_POSTAL_CODE = "ship-postal-code";
    private static final String HEADER_SHIP_COUNTRY = "ship-country";
    private static final String HEADER_SHIP_SERVICE_LEVEL = "ship-service-level";
    private static final String HEADER_SHIP_SERVICE_NAME = "ship-service-name";
    private static final String HEADER_VERGE_OF_LATE_SHIPMENT = "verge-of-lateshipment";
    private static final String HEADER_IS_BUYER_REQUESTED_CANCELLATION = "is-buyer-requested-cancellation";
    private volatile boolean includeLateShipmentRows = false;
    private volatile int lastSkippedLateShipmentCount;
    private volatile List<String> lastLateShipmentOrderIds = List.of();

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
            Set<String> lateShipmentOrderIds = new LinkedHashSet<>();
            String line;
            int rowIndex = 1;
            int skippedLateShipment = 0;

            while ((line = buffered.readLine()) != null) {
                rowIndex++;
                if (line.isBlank()) {
                    continue;
                }

                List<String> columns = splitLine(line);

                Boolean buyerCancel = resolveBuyerRequestedCancellationFlag(columns, headerIndex);
                if (Boolean.TRUE.equals(buyerCancel)) {
                    LOGGER.fine("Skipping row %d (buyer requested cancellation).".formatted(rowIndex));
                    continue;
                }


                try {
                    AmazonOrderRecord record = toRecord(columns, headerIndex);
                    Category category = ItemTypeCategorizer.resolveCategory(record);
                    if (category != Category.MUG_CUSTOM) {
                        LOGGER.fine("Skipping non-mug item type for order " + record.orderId() + ": " + record.rawItemType());
                        continue;
                    }

                    Boolean lateShipment = resolveLateShipmentFlag(columns, headerIndex);
                    boolean isLateShipment = Boolean.TRUE.equals(lateShipment);
                    if (isLateShipment) {
                        lateShipmentOrderIds.add(record.orderId());
                    }
                    if (!includeLateShipmentRows && isLateShipment) {
                        LOGGER.fine("Skipping row %d (late shipment) due to filter.".formatted(rowIndex));
                        skippedLateShipment++;
                        continue;
                    }

                    records.add(record);
                } catch (IllegalArgumentException ex) {
                    LOGGER.warning("Skipping row %d: %s".formatted(rowIndex, ex.getMessage()));
                }
            }

            lastSkippedLateShipmentCount = skippedLateShipment;
            lastLateShipmentOrderIds = List.copyOf(lateShipmentOrderIds);
            return records;
        }
    }

    private AmazonOrderRecord toRecord(List<String> columns, Map<String, Integer> headerIndex) {
        String orderId = getValue(columns, headerIndex, HEADER_ORDER_ID);
        String orderItemId = getValue(columns, headerIndex, HEADER_ORDER_ITEM_ID);
        String purchaseDate = getOptionalValue(columns, headerIndex, HEADER_PURCHASE_DATE);
        String buyerName = getValue(columns, headerIndex, HEADER_BUYER_NAME);
        String buyerEmail = getOptionalValue(columns, headerIndex, HEADER_BUYER_EMAIL);
        String buyerPhone = getOptionalValue(columns, headerIndex, HEADER_BUYER_PHONE);
        String rawSku = getValue(columns, headerIndex, HEADER_SKU);
        String downloadUrl = getValue(columns, headerIndex, HEADER_CUSTOM_URL);
        String productName = getOptionalValue(columns, headerIndex, HEADER_PRODUCT_NAME);
        int quantityPurchased = getIntValue(columns, headerIndex, HEADER_QUANTITY_PURCHASED);
        int quantityShipped = getIntValue(columns, headerIndex, HEADER_QUANTITY_SHIPPED);
        String recipientName = getOptionalValue(columns, headerIndex, HEADER_RECIPIENT_NAME);
        String shipAddress1 = getOptionalValue(columns, headerIndex, HEADER_SHIP_ADDRESS1);
        String shipAddress2 = getOptionalValue(columns, headerIndex, HEADER_SHIP_ADDRESS2);
        String shipAddress3 = getOptionalValue(columns, headerIndex, HEADER_SHIP_ADDRESS3);
        String shipCity = getOptionalValue(columns, headerIndex, HEADER_SHIP_CITY);
        String shipState = getOptionalValue(columns, headerIndex, HEADER_SHIP_STATE);
        String shipPostalCode = getOptionalValue(columns, headerIndex, HEADER_SHIP_POSTAL_CODE);
        String shipCountry = getOptionalValue(columns, headerIndex, HEADER_SHIP_COUNTRY);
        String shipServiceLevel = getOptionalValue(columns, headerIndex, HEADER_SHIP_SERVICE_LEVEL);
        String shipServiceName = getOptionalValue(columns, headerIndex, HEADER_SHIP_SERVICE_NAME);

        if (downloadUrl.startsWith("https://a.co")) {
            // Usually the download link we need lives under customized-url, but fall back to the page url if needed.
            LOGGER.info("Row for orderItemId %s references product page URL; keeping value as-is.".formatted(orderItemId));
        }

        String normalizedItemType = normalizeItemType(rawSku);
        boolean customizable = isCustomizable(rawSku);

        return new AmazonOrderRecord(
            orderId.trim(),
            orderItemId.trim(),
            purchaseDate,
            buyerName.trim(),
            buyerEmail,
            buyerPhone,
            recipientName,
            shipAddress1,
            shipAddress2,
            shipAddress3,
            shipCity,
            shipState,
            shipPostalCode,
            shipCountry,
            shipServiceLevel,
            shipServiceName,
            productName,
            quantityPurchased,
            quantityShipped,
            rawSku.trim(),
            normalizedItemType,
            downloadUrl.trim(),
            customizable
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

    private static Boolean resolveLateShipmentFlag(List<String> columns, Map<String, Integer> headerIndex) {
        Integer index = headerIndex.get(HEADER_VERGE_OF_LATE_SHIPMENT);
        if (index == null || index < 0 || index >= columns.size()) {
            return null;
        }
        String value = columns.get(index);
        if (value == null || value.isBlank()) {
            return null;
        }
        return !value.trim().equalsIgnoreCase("true");
    }

    private static String getOptionalValue(List<String> columns, Map<String, Integer> headerIndex, String key) {
        Integer index = headerIndex.get(key);
        if (index == null || index < 0 || index >= columns.size()) {
            return "";
        }
        String value = columns.get(index);
        return value == null ? "" : value.trim();
    }

    private static int getIntValue(List<String> columns, Map<String, Integer> headerIndex, String key) {
        String raw = getOptionalValue(columns, headerIndex, key);
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            LOGGER.fine(() -> "Unable to parse integer for column '" + key + "': " + raw);
            return 0;
        }
    }

    public int getLastSkippedLateShipmentCount() {
        return lastSkippedLateShipmentCount;
    }

    public List<String> getLastLateShipmentOrderIds() {
        return lastLateShipmentOrderIds;
    }

    public void setIncludeLateShipmentRows(boolean include) {
        this.includeLateShipmentRows = include;
    }

    public boolean isIncludeLateShipmentRows() {
        return includeLateShipmentRows;
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
     *     <li>Split on '-' and '_' to isolate SKU segments.</li>
     *     <li>Ignore the leading SKU identifier when present (e.g. {@code SKU004}).</li>
     *     <li>Score the remaining segments, preferring compact codes that include digits (11W, 15W) or product tokens (TMBLR).</li>
     *     <li>Return the highest scoring segment uppercase, or the sanitized SKU as a final fallback.</li>
     * </ul>
     */
    public String normalizeItemType(String sku) {
        if (sku == null || sku.isBlank()) {
            return "UNKNOWN";
        }

        String normalizedSeparators = sku.trim().replace('_', '-');
        List<String> segments = Arrays.stream(normalizedSeparators.split("-"))
            .map(value -> value == null ? "" : value.trim())
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toCollection(ArrayList::new));

        if (!segments.isEmpty()) {
            String first = segments.get(0).toUpperCase(Locale.ROOT);
            if (first.startsWith("SKU") && first.length() > 3) {
                segments.remove(0);
            }
        }

        String explicit = extractExplicitMugCode(segments);
        if (explicit != null) {
            return explicit;
        }

        String bestCandidate = findBestSegment(segments);
        if (bestCandidate != null) {
            return bestCandidate;
        }

        String fallback = sku.replaceAll("[^A-Za-z0-9]", "");
        return fallback.isBlank() ? "UNKNOWN" : fallback.toUpperCase(Locale.ROOT);
    }

    private static String findBestSegment(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }

        String best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = segments.size() - 1; i >= 0; i--) {
            String rawSegment = segments.get(i);
            if (rawSegment == null || rawSegment.isBlank()) {
                continue;
            }

            String sanitized = rawSegment.chars()
                .filter(Character::isLetterOrDigit)
                .mapToObj(c -> String.valueOf((char) c))
                .collect(Collectors.joining());

            if (sanitized.isBlank()) {
                continue;
            }

            String upper = sanitized.toUpperCase(Locale.ROOT);
            if (upper.startsWith("SKU") && upper.length() > 3) {
                continue;
            }

            int score = scoreSegment(upper);
            if (score > bestScore) {
                bestScore = score;
                best = upper;
            }
        }

        return best;
    }

    private static String extractExplicitMugCode(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        Pattern pattern = Pattern.compile("(?i)(11|15)[A-Z]{0,3}");
        for (int i = segments.size() - 1; i >= 0; i--) {
            String segment = segments.get(i);
            if (segment == null || segment.isBlank()) {
                continue;
            }
            String cleaned = segment.replaceAll("[^A-Za-z0-9]", "");
            if (cleaned.isBlank()) {
                continue;
            }
            Matcher matcher = pattern.matcher(cleaned);
            if (matcher.find()) {
                String match = matcher.group();
                String normalized = match.replace("OZ", "").toUpperCase(Locale.ROOT);
                return normalized;
            }
        }
        return null;
    }

    private static int scoreSegment(String segment) {
        int score = 0;

        if (segment.chars().anyMatch(Character::isDigit)) {
            score += 2;
        }

        if (segment.length() <= 5) {
            score += 1;
        }

        if (segment.contains("OZ") || segment.contains("ML")) {
            score += 1;
        }

        return score;
    }

    private static boolean isCustomizable(String sku) {
        if (sku == null) {
            return false;
        }
        String normalized = sku.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("SKU004")) {
            return true;
        }
        if (normalized.contains("PHOTOMUG")) {
            return true;
        }
        return false;
    }
    private static Boolean resolveBuyerRequestedCancellationFlag(List<String> columns, Map<String, Integer> headerIndex) {
        Integer index = headerIndex.get(HEADER_IS_BUYER_REQUESTED_CANCELLATION);
        if (index == null || index < 0 || index >= columns.size()) {
            return null;
        }
        String value = columns.get(index);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().equalsIgnoreCase("true");
    }
}
