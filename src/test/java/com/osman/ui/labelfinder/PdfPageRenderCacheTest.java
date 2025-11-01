package com.osman.ui.labelfinder;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfPageRenderCacheTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearCache() {
        PdfPageRenderCache.clear();
    }

    @Test
    void cachesRenderedPagesPerDpi() throws Exception {
        Path pdfPath = createSinglePagePdf("cache.pdf");

        BufferedImage first = PdfPageRenderCache.getOrRenderPage(pdfPath.toFile(), 0, 100);
        BufferedImage second = PdfPageRenderCache.getOrRenderPage(pdfPath.toFile(), 0, 100);
        assertSame(first, second, "Expected cached image for identical DPI");

        BufferedImage highRes = PdfPageRenderCache.getOrRenderPage(pdfPath.toFile(), 0, 150);
        assertNotSame(first, highRes, "Different DPI should render a new image");
        assertTrue(highRes.getWidth() > first.getWidth(), "Higher DPI should produce wider bitmap");
    }

    @Test
    void renderPagesSkipsInvalidIndices() throws Exception {
        Path pdfPath = createSinglePagePdf("invalid-pages.pdf");
        List<BufferedImage> pages = PdfPageRenderCache.renderPages(
            pdfPath.toFile(),
            List.of(-1, 0, 1, 2),
            100
        );
        assertEquals(1, pages.size(), "Only valid 1-based indexes should be rendered");
    }

    private Path createSinglePagePdf(String name) throws IOException {
        Path pdf = tempDir.resolve(name);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            document.save(pdf.toFile());
        }
        return pdf;
    }
}
