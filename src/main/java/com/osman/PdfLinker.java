package com.osman;

import com.osman.core.pdf.ShippingLabelExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Backwards-compatible wrapper around {@link ShippingLabelExtractor}.
 */
public final class PdfLinker {

    private PdfLinker() {
    }

    public static Map<String, List<Integer>> buildOrderIdToPagesMap(File labelsPdf) throws IOException {
        return ShippingLabelExtractor.extractOrderIdToPages(labelsPdf.toPath());
    }

    public static Map<String, List<Integer>> buildOrderIdToPagesMap(PDDocument doc) throws IOException {
        return ShippingLabelExtractor.extractOrderIdToPages(doc);
    }
}
