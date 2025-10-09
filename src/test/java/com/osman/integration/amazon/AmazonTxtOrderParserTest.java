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

class AmazonTxtOrderParserTest {

    private final AmazonTxtOrderParser parser = new AmazonTxtOrderParser();

    @Test
    void parsesSampleFile() throws IOException {
        try (InputStream stream = getResource("ordertestfiles/sample-amazon-orders.txt")) {
            assertNotNull(stream, "Sample resource is missing");
            List<AmazonOrderRecord> records = parser.parse(new InputStreamReader(stream, StandardCharsets.UTF_8));

            assertEquals(5, records.size());

            Map<String, Long> grouped = records.stream()
                .collect(Collectors.groupingBy(AmazonOrderRecord::normalizedItemType, Collectors.counting()));

            assertEquals(2, grouped.size(), "Types: " + grouped.keySet());
            assertEquals(4L, grouped.get("11W"));
            assertEquals(1L, grouped.get("VACAYBEACH"));
        }
    }

    @Test
    void throwsWhenHeadersMissing() {
        String invalid = "order-id\tsku\n1\tSKU001";
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new java.io.StringReader(invalid)));
    }

    private InputStream getResource(String path) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
    }
}
