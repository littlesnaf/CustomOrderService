package com.osman;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PackSlipExtractor {

    // Your inner classes like PackSlipData and Item do not need to change.
    // ... (Omitted for brevity, keep your existing inner classes)
    public static class Item {
        public String quantity = "";
        public String productDetails = "";
        public Map<String, String> customizations = new LinkedHashMap<>();
    }
    public static class PackSlipData {
        public String orderId = "";
        public List<Item> items = new ArrayList<>();
        // Add other fields as needed
    }

    private static final Pattern ORDER_ID_RE = Pattern.compile("\\b(\\d{3}-\\d{7}-\\d{7})\\b");

    /**
     * UPDATED: Produces a map of order IDs to pages.
     * This version is stricter and only maps a page if an Order ID is explicitly found in the page header.
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

    /**
     * Smarter function to find the main Order ID.
     * It only searches the top of the page to avoid confusion with footer text.
     */
    private static String findOrderIdInPageHeader(String text) {
        if (text == null || text.isEmpty()) return null;

        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < Math.min(lines.length, 20); i++) {
            String line = lines[i];
            if (line.toLowerCase().contains("order id:")) {
                Matcher m = ORDER_ID_RE.matcher(line);
                if (m.find()) {
                    return m.group(1); // Return the first one found in the header
                }
            }
        }
        return null; // If no ID is found in the header, ignore this page
    }


    // The other methods in this class for deep data extraction are not used by the UI
    // but can remain for other purposes.
}