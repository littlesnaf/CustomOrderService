package com.osman;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PackSlipExtractor {

    private static final Pattern ORDER_ID_RE = Pattern.compile("\\b(\\d{3}-\\d{7}-\\d{7})\\b");
    private static final Pattern SHIP_TO_RE = Pattern.compile("(?i)\\bship\\s*to\\b");

    public static Map<String, List<Integer>> indexOrderToPages(File packingSlipPdf) throws IOException {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        try (PDDocument doc = PDDocument.load(packingSlipPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();

            String currentOrderId = null;
            List<Integer> pendingPages = new ArrayList<>();

            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = stripper.getText(doc);

                String trimmed = (pageText == null) ? "" : pageText.trim();
                if (trimmed.isEmpty()) continue;

                String lower = trimmed.toLowerCase(Locale.ROOT);
                boolean hasShipTo = SHIP_TO_RE.matcher(lower).find();
                boolean looksLikeHeader = hasShipTo || lower.contains("order id") || ORDER_ID_RE.matcher(trimmed).find();
                if (trimmed.length() < 120 && !looksLikeHeader) continue;

                String headerOrderId = findOrderIdInPageHeader(pageText);
                String anyOrderId = (headerOrderId != null) ? headerOrderId : extractFirstOrderId(pageText);

                if (hasShipTo) {
                    flushPending(out, currentOrderId, pendingPages);
                    currentOrderId = (headerOrderId != null) ? headerOrderId : anyOrderId;
                    pendingPages = new ArrayList<>();
                    pendingPages.add(p);
                    if (currentOrderId == null && anyOrderId != null) {
                        currentOrderId = anyOrderId;
                        flushPending(out, currentOrderId, pendingPages);
                        pendingPages = new ArrayList<>();
                    }
                    continue;
                }

                if (currentOrderId == null && anyOrderId != null) {
                    currentOrderId = anyOrderId;
                    pendingPages.add(p);
                    flushPending(out, currentOrderId, pendingPages);
                    pendingPages = new ArrayList<>();
                    continue;
                }

                pendingPages.add(p);
            }

            flushPending(out, currentOrderId, pendingPages);
        }
        return out;
    }

    private static void flushPending(Map<String, List<Integer>> out, String orderId, List<Integer> pendingPages) {
        if (orderId == null || pendingPages == null || pendingPages.isEmpty()) return;
        out.computeIfAbsent(orderId, k -> new ArrayList<>()).addAll(pendingPages);
    }

    private static String findOrderIdInPageHeader(String text) {
        if (text == null || text.isEmpty()) return null;
        String[] lines = text.split("\\r?\\n");
        String fallbackId = null;
        int maxLines = Math.min(lines.length, 40);
        for (int i = 0; i < maxLines; i++) {
            String line = lines[i];
            Matcher m = ORDER_ID_RE.matcher(line);
            if (line.toLowerCase(Locale.ROOT).contains("order id")) {
                if (m.find()) return m.group(1);
            } else if (fallbackId == null && m.find()) {
                fallbackId = m.group(1);
            }
        }
        return fallbackId;
    }

    private static String extractFirstOrderId(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher matcher = ORDER_ID_RE.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
