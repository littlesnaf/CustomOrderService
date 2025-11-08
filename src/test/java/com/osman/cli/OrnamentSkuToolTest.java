package com.osman.cli;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    @Test
    void samplePdfNormalizesRareSku() throws Exception {
        URL pdfUrl = OrnamentSkuToolTest.class.getResource("/ordertestfiles/final_2025-09-25_14:42:41.pdf");
        assumeTrue(pdfUrl != null, "Sample PDF missing; skipping regression.");

        Path pdf = Path.of(pdfUrl.toURI());

        Method buildBundles = OrnamentSkuTool.class
            .getDeclaredMethod("buildBundles", PDDocument.class, int.class, OrnamentDebugLogger.class);
        buildBundles.setAccessible(true);

        Path debugDir = Files.createTempDirectory("ornament-debug");
        try (PDDocument doc = PDDocument.load(pdf.toFile());
             OrnamentDebugLogger debug = OrnamentDebugLogger.create(debugDir)) {

            @SuppressWarnings("unchecked")
            List<?> bundles = (List<?>) buildBundles.invoke(null, doc, 0, debug);

            boolean found = false;
            Field skusField = null;

            for (Object bundle : bundles) {
                if (skusField == null) {
                    skusField = bundle.getClass().getDeclaredField("skus");
                    skusField.setAccessible(true);
                }
                @SuppressWarnings("unchecked")
                Set<String> skus = (Set<String>) skusField.get(bundle);
                if (skus.stream().anyMatch(s -> s != null && s.contains("SKU1847-P"))) {
                    found = true;
                    assertTrue(skus.contains("SKU1847-P.OR"), "Expected normalized SKU1847-P.OR");
                    assertTrue(skus.stream().noneMatch(s -> s != null && s.contains("OR_NEW")),
                        "Unexpected OR_NEW token present");
                    assertTrue(skus.stream().noneMatch(s -> s != null && s.contains("SKU1847-.OR")),
                        "Unexpected partial SKU token present");
                    assertEquals("Section 1", OrnamentSkuSections.resolveSection(skus));
                    break;
                }
            }

            assertTrue(found, "Expected to find bundle containing SKU1847-P");
        } finally {
            deleteQuietly(debugDir);
        }
    }

    @Test
    void extractSkusMergesWrappedSku1847Tokens() throws Exception {
        Method extractSkus = OrnamentSkuTool.class
            .getDeclaredMethod("extractSkus", String.class, Pattern.class, OrnamentDebugLogger.class);
        extractSkus.setAccessible(true);

        String slip = """
            Packing Slip
            SKU1847-
            P.OR NEW
            Customizations:
            Qty: 1
            Grand Total: $20.25
            """;

        Path debugDir = Files.createTempDirectory("ornament-debug");
        try (OrnamentDebugLogger debug = OrnamentDebugLogger.create(debugDir)) {
            @SuppressWarnings("unchecked")
            Set<String> skus = (Set<String>) extractSkus.invoke(null, slip, OrnamentSkuPatterns.ANY, debug);

            assertTrue(skus.contains("SKU1847-P.OR"), "Expected normalized SKU1847-P.OR");
            assertTrue(skus.stream().noneMatch(s -> s != null && s.contains("OR_NEW")),
                "Unexpected OR_NEW token present");
            assertTrue(skus.stream().noneMatch(s -> s != null && s.contains("SKU1847-.OR")),
                "Unexpected partial SKU token present");
        } finally {
            deleteQuietly(debugDir);
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
