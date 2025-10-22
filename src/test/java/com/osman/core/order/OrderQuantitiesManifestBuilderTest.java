package com.osman.core.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderQuantitiesManifestBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void collectsAndWritesManifest() throws IOException {
        Path orderDir = tempDir.resolve("customer/images/order");
        Files.createDirectories(orderDir);

        Files.writeString(orderDir.resolve("item-one.json"), sampleJson("114-0000000-0000000", "ITEM-1234567890", 2));
        Files.writeString(orderDir.resolve("item-two.json"), sampleJson("114-0000000-0000000", "ITEM-0987654321", 1));

        OrderQuantitiesManifestBuilder builder = new OrderQuantitiesManifestBuilder();
        builder.collectFromFolder(orderDir.toFile());

        Path manifestPath = tempDir.resolve(OrderQuantitiesManifest.DEFAULT_FILENAME);
        builder.mergeInto(manifestPath);

        assertTrue(Files.exists(manifestPath), "Manifest should be written");

        OrderQuantitiesManifest manifest = OrderQuantitiesManifest.load(manifestPath);
        OrderQuantitiesManifest.OrderSummary summary = manifest.find("114-0000000-0000000");
        assertNotNull(summary, "Order summary must exist");
        assertEquals(3, summary.orderQuantity(), "Order quantity should match summed items");
        assertEquals(2, summary.items().size(), "Two items should be tracked");
        assertEquals(2, summary.items().stream().filter(item -> item.orderItemId().equals("ITEM-1234567890")).findFirst().orElseThrow().itemQuantity());
        assertEquals(1, summary.items().stream().filter(item -> item.orderItemId().equals("ITEM-0987654321")).findFirst().orElseThrow().itemQuantity());

        // Manifest JSON should be ignored when scanning entire temp directory.
        OrderQuantitiesManifestBuilder secondBuilder = new OrderQuantitiesManifestBuilder();
        secondBuilder.collectFromFolder(tempDir.toFile());
        Path manifestPathTwo = tempDir.resolve("second-" + OrderQuantitiesManifest.DEFAULT_FILENAME);
        secondBuilder.mergeInto(manifestPathTwo);

        OrderQuantitiesManifest manifestTwo = OrderQuantitiesManifest.load(manifestPathTwo);
        OrderQuantitiesManifest.OrderSummary summaryTwo = manifestTwo.find("114-0000000-0000000");
        assertNotNull(summaryTwo, "Order summary must exist in second manifest");
        assertEquals(3, summaryTwo.orderQuantity(), "Order quantity should remain unchanged");

        // Merging again should overwrite the order entry rather than append duplicates.
        OrderQuantitiesManifestBuilder updateBuilder = new OrderQuantitiesManifestBuilder();
        updateBuilder.addContribution(new OrderContribution("114-0000000-0000000", "ITEM-1234567890", 5));
        updateBuilder.addContribution(new OrderContribution("114-0000000-0000000", "ITEM-0987654321", 2));
        updateBuilder.mergeInto(manifestPath);

        OrderQuantitiesManifest updatedManifest = OrderQuantitiesManifest.load(manifestPath);
        OrderQuantitiesManifest.OrderSummary updatedSummary = updatedManifest.find("114-0000000-0000000");
        assertNotNull(updatedSummary, "Updated order summary must exist");
        assertEquals(7, updatedSummary.orderQuantity(), "Order quantity should reflect overwritten totals");
        assertEquals(5, updatedSummary.items().stream()
            .filter(item -> item.orderItemId().equals("ITEM-1234567890"))
            .findFirst().orElseThrow().itemQuantity());
    }

    private String sampleJson(String orderId, String itemId, int quantity) {
        return "{"
            + "\"orderId\":\"" + orderId + "\","
            + "\"orderItemId\":\"" + itemId + "\","
            + "\"quantity\":" + quantity
            + "}";
    }
}
