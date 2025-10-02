package com.osman.core.pdf;

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
public final class PackingSlipIndexService {
    private static final Pattern ORDER_ID_RE = Pattern.compile("\\b(\\d{3}-\\d{7}-\\d{7})\\b");

    private PackingSlipIndexService() {
    }

    public static Map<String, List<Integer>> indexOrderToPages(File packingSlipPdf) throws IOException {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        try (PDDocument doc = PDDocument.load(packingSlipPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String currentOrderId = null;

            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = stripper.getText(doc);
                if (pageText == null || pageText.trim().length() < 200) {
                    continue;
                }

                String foundOrderId = findOrderIdInPageHeader(pageText);
                if (foundOrderId != null) {
                    currentOrderId = foundOrderId;
                }

                if (currentOrderId != null) {
                    out.computeIfAbsent(currentOrderId, k -> new ArrayList<>()).add(p);
                }
            }
        }
        return out;
    }

    private static String findOrderIdInPageHeader(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < Math.min(lines.length, 20); i++) {
            String line = lines[i];
            if (line.toLowerCase().contains("order id:")) {
                Matcher matcher = ORDER_ID_RE.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return null;
    }
}
