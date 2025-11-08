package com.osman.cli;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrnamentBundleMergerTest {

    @Test
    void mergeAppendsSummaryPageAtEnd() throws Exception {
        Path tempDir = Files.createTempDirectory("ornament-bundle");
        try {
            Path label = tempDir.resolve("label.pdf");
            Path slip = tempDir.resolve("slip.pdf");
            writePdfWithLine(label, "Label Page");
            writePdfWithLine(slip, "Slip Page");

            List<List<Path>> singlePages = List.of(List.of(label, slip));
            OrnamentBundleMerger.BundlePages bundle = new OrnamentBundleMerger.BundlePages() {
                @Override public int docId() { return 0; }
                @Override public int labelPageIndex() { return 0; }
                @Override public List<Integer> slipPageIndices() { return Collections.singletonList(1); }
            };

            Path outFile = tempDir.resolve("bundle.pdf");
            OrnamentBundleMerger.SummaryPage summary =
                new OrnamentBundleMerger.SummaryPage("SKU-TEST", 5);

            OrnamentBundleMerger.merge(singlePages, List.of(bundle), outFile, summary);

            try (PDDocument doc = PDDocument.load(outFile.toFile())) {
                assertEquals(3, doc.getNumberOfPages(), "Summary page should add one extra page");
                var lastPage = doc.getPage(2);
                assertEquals(4 * 72, lastPage.getMediaBox().getWidth(), 0.1, "Summary width should be 4\"");
                assertEquals(6 * 72, lastPage.getMediaBox().getHeight(), 0.1, "Summary height should be 6\"");
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(3);
                stripper.setEndPage(3);
                String text = stripper.getText(doc);
                assertTrue(text.contains("SKU-TEST"));
                assertTrue(text.contains("Qty: 5"));
            }
        } finally {
            deleteQuietly(tempDir);
        }
    }

    private static void writePdfWithLine(Path path, String line) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(line);
                stream.endText();
            }
            doc.save(path.toFile());
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {
        }
    }
}
