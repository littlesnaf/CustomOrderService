package com.osman.core.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Thin wrapper around PDFBox rendering so UIs can request page previews safely.
 */
public final class PdfPageRenderer {
    private PdfPageRenderer() {
    }

    public static BufferedImage renderPage(File pdfFile, int pageIndex, float dpi) throws IOException {
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            return renderPage(doc, pageIndex, dpi);
        }
    }

    public static BufferedImage renderPage(PDDocument document, int pageIndex, float dpi) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        return renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
    }
}
