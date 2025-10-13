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
            assertEquals(2, records.size());

            Map<String, Long> grouped = records.stream()
                .collect(Collectors.groupingBy(AmazonOrderRecord::normalizedItemType, Collectors.counting()));

            assertEquals(2, grouped.size(), "Types: " + grouped.keySet());
            assertEquals(1L, grouped.get("11R"));
            assertEquals(1L, grouped.get("11W"));

            AmazonOrderRecord white = records.stream()
                .filter(record -> record.normalizedItemType().equals("11W"))
                .findFirst()
                .orElseThrow();
            assertTrue(white.customizable());
            assertEquals("NextDay", white.shipServiceLevel());
            assertEquals("John Doe", white.recipientName());

            AmazonOrderRecord red = records.stream()
                .filter(record -> record.normalizedItemType().equals("11R"))
                .findFirst()
                .orElseThrow();
            assertTrue(red.customizable());
            assertEquals(1, parser.getLastSkippedLateShipmentCount());
            assertEquals(List.of("222-2222222-2222222"), parser.getLastLateShipmentOrderIds());
        }
    }

    @Test
    void normalizesMugSkuToSizeCode() {
        assertEquals("11W", parser.normalizeItemType("SKU004-Photo-11W"));
    }

    @Test
    void parsesGiftbeesFormatSku() throws IOException {
        String data = String.join("\n",
            "order-id\torder-item-id\tbuyer-name\tsku\tcustomized-url\tproduct-name\tquantity-purchased\tquantity-shipped",
            "114-7254822-1733000\t142047923340841\tjohn Hawkins\tSKU.PhotoMug.11W\thttps://zme-caps.amazon.com/t/xxxx/Wk-6AkCKTSsgMiHvDu6n2n1rQU3l_rVpf5o1IenJqGg/23\tGiftbees Personalized Coffee Mug, Custom Picture Text or Logo Ceramic Mug\t1\t0"
        );

        List<AmazonOrderRecord> records = parser.parse(new java.io.StringReader(data));

        assertEquals(1, records.size());
        AmazonOrderRecord record = records.get(0);
        assertEquals("11W", record.normalizedItemType());
        assertTrue(record.customizable());
        assertEquals("SKU.PhotoMug.11W", record.rawItemType());
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

        parser.setIncludeLateShipmentRows(true);
        List<AmazonOrderRecord> records = parser.parse(new java.io.StringReader(data));

        assertEquals(2, records.size());
        assertEquals(List.of("ORDER-1"), parser.getLastLateShipmentOrderIds());
        assertEquals(0, parser.getLastSkippedLateShipmentCount());
    }

    @Test
    void skipsBuyerRequestedCancellationRows() throws IOException {
        String data = String.join("\n",
            "order-id\torder-item-id\tbuyer-name\tsku\tcustomized-url\tis-buyer-requested-cancellation",
            "ORDER-1\tITEM-1\tAlice Example\tSKU004-Photo-11W\thttps://example.com/custom/1\ttrue",
            "ORDER-2\tITEM-2\tBob Example\tSKU004-Photo-11W\thttps://example.com/custom/2\tfalse"
        );

        List<AmazonOrderRecord> records = parser.parse(new java.io.StringReader(data));

        assertEquals(1, records.size());
        assertEquals("ORDER-2", records.get(0).orderId());
    }

    private InputStream getResource(String path) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
    }
}
