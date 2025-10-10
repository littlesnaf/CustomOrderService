package com.osman.integration.amazon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmazonOrderGroupingServiceTest {

    private AmazonTxtOrderParser parser;
    private AmazonOrderGroupingService groupingService;

    @BeforeEach
    void setUp() {
        parser = new AmazonTxtOrderParser();
        groupingService = new AmazonOrderGroupingService();
    }

    @Test
    void groupsByItemTypeAndCustomer() throws IOException {
        List<AmazonOrderRecord> records = parseSample();
        OrderBatch batch = groupingService.group(records);

        assertEquals(1, batch.groups().size(), "Groups: " + batch.groups().keySet());
        ItemTypeGroup elevenW = batch.group("11W");
        assertNotNull(elevenW);
        assertEquals(ItemTypeCategorizer.Category.MUG_CUSTOM, elevenW.category());
        assertEquals(2, elevenW.customers().size());

        CustomerGroup john = elevenW.customers().get("John_Doe");
        assertNotNull(john);
        assertEquals("John Doe", john.originalBuyerName());
        assertEquals(1, john.orders().size());
        CustomerOrder johnOrder = john.orders().values().iterator().next();
        assertEquals("111-0687106-4490606", johnOrder.orderId());
        assertEquals(1, johnOrder.items().size());

        CustomerGroup alex = elevenW.customers().get("Alex_Brown");
        assertNotNull(alex);
        assertEquals(1, alex.orders().size());
        CustomerOrder alexOrder = alex.orders().values().iterator().next();
        assertEquals(3, alexOrder.items().size());

        assertNull(batch.group("TMBLR"), "Non-mug item types should be filtered out");
    }

    @Test
    void emptyInputProducesEmptyBatch() {
        OrderBatch batch = groupingService.group(List.of());
        assertTrue(batch.isEmpty());
        assertEquals(0, batch.groups().size());
    }

    private List<AmazonOrderRecord> parseSample() throws IOException {
        try (InputStream stream = getResource("ordertestfiles/sample-amazon-orders.txt")) {
            assertNotNull(stream, "Sample resource missing");
            return parser.parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    private InputStream getResource(String path) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
    }
}
