package com.osman.integration.amazon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AmazonTxtOrderParserTest {

    private final AmazonTxtOrderParser parser = new AmazonTxtOrderParser();

    @Test
    void parsesSampleFile() throws IOException {
        try (InputStream stream = getResource("ordertestfiles/sample-amazon-orders.txt")) {
            assertNotNull(stream, "Sample resource is missing");
            List<AmazonOrderRecord> records = parser.parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals(4, records.size());

            Map<String, Long> grouped = records.stream()
                .collect(Collectors.groupingBy(AmazonOrderRecord::normalizedItemType, Collectors.counting()));

            assertEquals(1, grouped.size(), "Types: " + grouped.keySet());
            assertEquals(4L, grouped.get("11W"));

            AmazonOrderRecord customRecord = records.stream()
                .filter(record -> record.rawItemType().startsWith("SKU004"))
                .findFirst()
                .orElseThrow();
            assertTrue(customRecord.customizable());
            assertEquals("2025-09-24T12:02:50+00:00", customRecord.purchaseDate());
            assertEquals("John Doe", customRecord.recipientName());
            assertEquals("DAWSONVILLE", customRecord.shipCity());
            assertEquals("GA", customRecord.shipState());
            assertEquals("US", customRecord.shipCountry());
            assertEquals(1, customRecord.quantityPurchased());
            assertTrue(customRecord.productName().startsWith("HomeBee Personalized"));
        }
    }

    @Test
    void normalizesMugSkuToSizeCode() {
        assertEquals("11W", parser.normalizeItemType("SKU004-Photo-11W"));
    }

    @Test
    void normalizesTumblerSkuToProductCode() {
        assertEquals("TMBLR", parser.normalizeItemType("SKU001-TMBLR-Vacay.Beach"));
    }

    @Test
    void throwsWhenHeadersMissing() {
        String invalid = "order-id\tsku\n1\tSKU001";
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new java.io.StringReader(invalid)));
    }

    @Test
    void includesOnlyLateShipmentRows() throws IOException {
        String data = String.join("\n",
            "order-id\torder-item-id\tbuyer-name\tsku\tcustomized-url\tverge-of-lateShipment",
            "ORDER-1\tITEM-1\tAlice Example\tSKU004-Photo-11W\thttps://example.com/custom/1\tfalse",
            "ORDER-2\tITEM-2\tBob Example\tSKU004-Photo-11W\thttps://example.com/custom/2\ttrue"
        );

        List<AmazonOrderRecord> records = parser.parse(new java.io.StringReader(data));

        assertEquals(1, records.size());
        assertEquals("ORDER-2", records.get(0).orderId());
        assertEquals(1, parser.getLastSkippedNonLateShipmentCount());
        assertEquals(List.of("ORDER-2"), parser.getLastLateShipmentOrderIds());
    }

    private InputStream getResource(String path) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
    }
}
