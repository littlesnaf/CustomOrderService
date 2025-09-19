package com.osman;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PdfLinker {

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("\\b\\d{3}-\\d{7}-\\d{7}\\b");
    private static final Pattern PACKING_SLIP_PATTERN = Pattern.compile("^11[PRWB](\\s*\\(\\d+\\))?\\.pdf$", Pattern.CASE_INSENSITIVE);

    private static boolean isPackingSlipFile(File pdfFile) {
        if (pdfFile == null) return false;
        return PACKING_SLIP_PATTERN.matcher(pdfFile.getName()).matches();
    }

    public static Map<String, List<Integer>> buildOrderIdToPagesMap(File labelsPdf) throws IOException {
        if (labelsPdf == null || !labelsPdf.isFile()) {
            throw new IOException("Labels PDF not found: " + labelsPdf);
        }
        if (isPackingSlipFile(labelsPdf)) {
            return new LinkedHashMap<>();
        }
        try (PDDocument doc = PDDocument.load(labelsPdf)) {
            return buildOrderIdToPagesMap(doc);
        }
    }

    public static Map<String, List<Integer>> buildOrderIdToPagesMap(PDDocument doc) throws IOException {
        Map<String, List<Integer>> map = new LinkedHashMap<>();
        int pageCount = doc.getNumberOfPages();
        if (pageCount < 2) return map;

        String lastPageText = extractPageText(doc, pageCount);
        if (lastPageText != null && lastPageText.contains("List of orders")) {
            List<String> orderIds = new ArrayList<>();
            Matcher m = ORDER_ID_PATTERN.matcher(lastPageText);
            while (m.find()) orderIds.add(m.group());

            if (!orderIds.isEmpty()) {
                int labelPages = pageCount - 1;
                int n = Math.min(orderIds.size(), labelPages);
                for (int i = 0; i < n; i++) {
                    String orderId = orderIds.get(i);
                    int pageNum = i + 1;
                    map.computeIfAbsent(orderId, k -> new ArrayList<>()).add(pageNum);
                }
            }
        }
        return map;
    }

    private static String extractPageText(PDDocument doc, int pageIndex1Based) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex1Based);
        stripper.setEndPage(pageIndex1Based);
        return stripper.getText(doc);
    }
}
