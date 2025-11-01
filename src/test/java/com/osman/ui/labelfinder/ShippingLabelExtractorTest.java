package com.osman.ui.labelfinder;

import com.osman.core.pdf.ShippingLabelExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShippingLabelExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsFromIndexedBundle_indexFirst() throws Exception {
        // pages: [label1, label2, index]
        Path pdf = tempDir.resolve("labels-indexed.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPageWithText(doc, "UPS LABEL — order 111-1111111-1111111");
            addPageWithText(doc, "UPS LABEL — order 222-2222222-2222222");
            addPageWithText(doc,
                    "Successful Label Purchase\n" +
                            "List of Orders\n" +
                            "111-1111111-1111111\n" +
                            "222-2222222-2222222"
            );
            doc.save(pdf.toFile());
        }

        Map<String, List<Integer>> map = ShippingLabelExtractor.extractOrderIdToPages(pdf);

        assertEquals(List.of(1), map.get("111-1111111-1111111"), "First index entry maps to page 1");
        assertEquals(List.of(2), map.get("222-2222222-2222222"), "Second index entry maps to page 2");
        assertEquals(2, map.size(), "Only label pages before index are mapped");
    }

    @Test
    void fallsBackWhenNoIndex_singlePageLabel() throws Exception {
        // single page, no index keywords; fallback should read the ID from the page
        Path pdf = tempDir.resolve("single-label-no-index.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPageWithText(doc, "SHIP TO JOHN DOE\nORDER: 333-3333333-3333333\nTRACKING ...");
            doc.save(pdf.toFile());
        }

        Map<String, List<Integer>> map = ShippingLabelExtractor.extractOrderIdToPages(pdf);

        assertEquals(List.of(1), map.get("333-3333333-3333333"), "Fallback maps the only page");
        assertEquals(1, map.size(), "Exactly one order detected on single page");
    }

    @Test
    void returnsEmptyForPackingSlipByFileName() throws Exception {
        // Name matches PACKING_SLIP_PATTERN (e.g., 12P.pdf)
        Path pdf = tempDir.resolve("12P.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPageWithText(doc, "Some packing slip text");
            doc.save(pdf.toFile());
        }

        Map<String, List<Integer>> map = ShippingLabelExtractor.extractOrderIdToPages(pdf);

        assertTrue(map.isEmpty(), "Packing slip filenames should be skipped");
    }

    @Test
    void returnsEmptyForPackingSlipByParentFolder() throws Exception {
        // Parent folder matches PACKING_SLIP_FOLDER_PATTERN and file name starts with 'amazon'
        Path folder = tempDir.resolve("12 P");
        Files.createDirectories(folder);
        Path pdf = folder.resolve("amazon_batch.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPageWithText(doc, "Packing slip batch text");
            doc.save(pdf.toFile());
        }

        Map<String, List<Integer>> map = ShippingLabelExtractor.extractOrderIdToPages(pdf);

        assertTrue(map.isEmpty(), "amazon*.pdf inside packing slip folder should be skipped");
    }

    @Test
    void scanAggregatesDuplicatesAndFailures() throws Exception {
        // Make two valid PDFs with the same order ID (duplicates)
        Path pdf1 = tempDir.resolve("bundle1.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPageWithText(doc, "LABEL 1");
            addPageWithText(doc, "Successful Label Purchase\nList of Orders\n444-4444444-4444444");
            doc.save(pdf1.toFile());
        }
        Path pdf2 = tempDir.resolve("bundle2.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPageWithText(doc, "LABEL 1");
            addPageWithText(doc, "Successful Label Purchase\nList of Orders\n444-4444444-4444444");
            doc.save(pdf2.toFile());
        }

        // Make a "broken" PDF (actually a text file with .pdf extension)
        Path broken = tempDir.resolve("broken.pdf");
        Files.writeString(broken, "NOT A REAL PDF");

        ShippingLabelExtractor.ScanResult result =
                ShippingLabelExtractor.scan(List.of(pdf1, pdf2, broken));

        assertTrue(result.labelsByOrder().containsKey("444-4444444-4444444"),
                "Order should be discovered");
        assertTrue(result.duplicateLabels().containsKey("444-4444444-4444444"),
                "Duplicate map should list the second occurrence");
        assertEquals(1, result.failures().size(),
                "Broken file should be reported as a failure");
        assertTrue(result.failures().get(0).pdfPath().endsWith("broken.pdf"),
                "Failure should reference the broken PDF");
    }

    @Test
    void invalidPathThrowsIOException() {
        Path missing = tempDir.resolve("missing.pdf");
        IOException ex = assertThrows(IOException.class,
                () -> ShippingLabelExtractor.extractOrderIdToPages(missing));
        assertTrue(ex.getMessage().contains("Labels PDF not found"),
                "Should throw a clear not-found IOException");
    }

    private static void addPageWithText(PDDocument doc, String text) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA, 11);
            cs.newLineAtOffset(72, 720); // 1 inch margin, near top
            for (String line : text.split("\\R")) {
                cs.showText(line);
                cs.newLineAtOffset(0, -14);
            }
            cs.endText();
        }
    }
}
