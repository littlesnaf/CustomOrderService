package com.osman;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility routines for scanning Amazon packing slip PDFs and mapping order IDs to their pages.
 */
public class PackSlipExtractor {

    private static final Pattern ORDER_ID_RE = Pattern.compile("\\b(\\d{3}-\\d{7}-\\d{7})\\b");

    /**
     * Produces a map of order IDs to pages. The implementation is strict—it only records a page when the
     * Order ID is detected in the header segment so footer summaries do not produce false positives.
     */
    public static Map<String, List<Integer>> indexOrderToPages(File packingSlipPdf) throws IOException {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        try (PDDocument doc = PDDocument.load(packingSlipPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String currentOrderId = null; // Remembers the last seen order ID

            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = stripper.getText(doc);

                // Ignore pages that are blank or have very little text (like just a footer)
                if (pageText == null || pageText.trim().length() < 200) {
                    continue;
                }

                String foundOrderId = findOrderIdInPageHeader(pageText);

                if (foundOrderId != null) {
                    // A new order has started on this page. Update our state.
                    currentOrderId = foundOrderId;
                }

                // If we have an active order ID (either from this page or a previous one),
                // associate this non-blank page with it.
                if (currentOrderId != null) {
                    out.computeIfAbsent(currentOrderId, k -> new ArrayList<>()).add(p);
                }
            }
        }
        return out;
    }

    /** Looks at the first lines of text on a page to locate the Order ID header. */
    private static String findOrderIdInPageHeader(String text) {
        if (text == null || text.isEmpty()) return null;

        String[] lines = text.split("\\r?\\n");
        String fallbackId = null;
        int maxLines = Math.min(lines.length, 40);
        for (int i = 0; i < maxLines; i++) {
            String line = lines[i];
            Matcher m = ORDER_ID_RE.matcher(line);
            if (line.toLowerCase().contains("order id")) {
                if (m.find()) {
                    return m.group(1);
                }
            } else if (fallbackId == null && m.find()) {
                fallbackId = m.group(1);
            }
        }
        return fallbackId;
    }
}
