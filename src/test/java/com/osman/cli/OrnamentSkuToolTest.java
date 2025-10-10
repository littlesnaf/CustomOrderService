package com.osman.cli;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrnamentSkuToolTest {

    @Test
    void bundlesSingleOrderFromLabelAndSlipPages() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            addPageWithLines(doc,
                "Order #: 555-1234567-8901234",
                "SKU-ORNAMENT123",
                "Customer: Jane Smith"
            );

            addPageWithLines(doc,
                "Order #: 555-1234567-8901234",
                "SKU-ORNAMENT123",
                "Not Continued on Next Page"
            );

            Method buildBundles = OrnamentSkuTool.class
                .getDeclaredMethod("buildBundles", PDDocument.class, int.class, OrnamentDebugLogger.class);
            buildBundles.setAccessible(true);

            Path debugDir = Files.createTempDirectory("ornament-debug");
            try (OrnamentDebugLogger debug = OrnamentDebugLogger.create(debugDir)) {
                @SuppressWarnings("unchecked")
                List<?> bundles = (List<?>) buildBundles.invoke(null, doc, 0, debug);

                assertEquals(1, bundles.size(), "Expected a single bundle for the order");
                Object bundle = bundles.get(0);

                Field orderIdField = bundle.getClass().getDeclaredField("orderId");
                orderIdField.setAccessible(true);
                assertEquals("555-1234567-8901234", orderIdField.get(bundle));

                Field skusField = bundle.getClass().getDeclaredField("skus");
                skusField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Set<String> skus = (Set<String>) skusField.get(bundle);
                assertTrue(skus.contains("SKU-ORNAMENT123.OR"));

                Field slipsField = bundle.getClass().getDeclaredField("slipPageIndices");
                slipsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Integer> slipPageIndices = (List<Integer>) slipsField.get(bundle);
                assertTrue(slipPageIndices.contains(1), "Slip should reference the second page");
            } finally {
                deleteQuietly(debugDir);
            }
        }
    }

    private static void addPageWithLines(PDDocument doc, String... lines) throws Exception {
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
            stream.beginText();
            stream.setFont(PDType1Font.HELVETICA, 12);
            stream.newLineAtOffset(50, 700);
            for (String line : lines) {
                stream.showText(line);
                stream.newLineAtOffset(0, -16);
            }
            stream.endText();
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
        } catch (Exception ignored) {
        }
    }
}
