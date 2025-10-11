package com.osman.integration.amazon;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.osman.integration.amazon.CustomerGroup;
import com.osman.integration.amazon.CustomerOrder;
import com.osman.integration.amazon.ItemTypeCategorizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmazonPackingSlipGeneratorTest {

    private final AmazonTxtOrderParser parser = new AmazonTxtOrderParser();
    private final AmazonOrderGroupingService groupingService = new AmazonOrderGroupingService();
    private final AmazonPackingSlipGenerator generator = new AmazonPackingSlipGenerator();

    @Test
    void generatesPackingSlipsPerOrder() throws Exception {
        parser.setIncludeLateShipmentRows(true);
        List<AmazonOrderRecord> records;
        try (InputStream stream = getResource("ordertestfiles/sample-amazon-orders.txt")) {
            assertNotNull(stream, "Sample resource is missing");
            records = parser.parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        OrderBatch batch = groupingService.group(records);
        Path runRoot = Files.createTempDirectory("packing-slips-test");

        // Mirror expected folder structure
        Map<String, ItemTypeGroup> groups = new LinkedHashMap<>(batch.groups());
        for (ItemTypeGroup group : groups.values()) {
            if (group.category() != ItemTypeCategorizer.Category.MUG_CUSTOM) {
                continue;
            }
            Path itemTypeRoot = runRoot.resolve(group.itemType());
            Path imagesRoot = ItemTypeCategorizer.resolveImagesFolder(itemTypeRoot);
            for (CustomerGroup customer : group.customers().values()) {
                for (CustomerOrder order : customer.orders().values()) {
                    String folderName = customer.sanitizedBuyerName() + "_" + order.orderId().replaceAll("[^A-Za-z0-9-]", "_");
                    Files.createDirectories(imagesRoot.resolve(folderName));
                }
            }
        }

        List<Path> slips = generator.generatePackingSlips(batch, runRoot);

        Set<String> uniqueItemTypes = groups.values().stream()
            .filter(group -> group.category() == ItemTypeCategorizer.Category.MUG_CUSTOM)
            .map(ItemTypeGroup::itemType)
            .collect(Collectors.toCollection(HashSet::new));

        int expectedSingles = groups.values().stream()
            .filter(group -> group.category() == ItemTypeCategorizer.Category.MUG_CUSTOM)
            .mapToInt(group -> group.customers().values().stream()
                .mapToInt(customer -> customer.orders().size())
                .sum())
            .sum();

        int expectedTotal = expectedSingles + uniqueItemTypes.size();
        assertEquals(expectedTotal, slips.size());

        Path combined11W = runRoot.resolve("Mugs/11/11W/11W-packing-slips.pdf");
        assertTrue(Files.exists(combined11W), "Combined packing slip for 11W missing");
        try (PDDocument doc = PDDocument.load(combined11W.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Order ID: 111-0687106-4490606"));
        }

        Path combined15R = runRoot.resolve("Mugs/15/15R/15R-packing-slips.pdf");
        assertTrue(Files.exists(combined15R), "Combined packing slip for 15R missing");
        try (PDDocument doc = PDDocument.load(combined15R.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Order ID: 222-2222222-2222222"));
        }

        Path singleJohn = runRoot.resolve("Mugs/11/11W/Images/John_Doe_111-0687106-4490606/packing-slip.pdf");
        assertTrue(Files.exists(singleJohn), "Single slip missing for John Doe order");

        Path singleJane = runRoot.resolve("Mugs/15/15R/Images/Jane_Smith_222-2222222-2222222/packing-slip.pdf");
        assertTrue(Files.exists(singleJane), "Single slip missing for Jane Smith order");
        try (PDDocument doc = PDDocument.load(singleJane.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Packing Slip"));
            assertTrue(text.contains("Order ID: 222-2222222-2222222"));
            assertTrue(text.contains("240278395364561"));
        }
    }

    private InputStream getResource(String path) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
    }
}
