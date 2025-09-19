package com.osman;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PackSlipExtractor {

    public static class Item {
        public String quantity = "";
        public String productDetails = "";
        public String unitPrice = "";
        public String itemSubtotal = "";
        public Map<String, String> customizations = new LinkedHashMap<>();
    }

    public static class PackSlipData {
        public String orderId = "";
        public String orderDate = "";
        public String shippingService = "";
        public String buyerName = "";
        public String sellerName = "";
        public String shippingAddress = "";
        public String grandTotal = "";
        public final List<Item> items = new ArrayList<>();
    }

    private static class Token {
        final String text;
        final float x, y;
        Token(String text, float x, float y) { this.text = text; this.x = x; this.y = y; }
    }

    private static class PageBuffer extends PDFTextStripper {
        final List<Token> tokens = new ArrayList<>();
        PageBuffer() throws IOException { setSortByPosition(true); }
        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (text != null && !text.isBlank()) {
                tokens.add(new Token(normalize(text), textPositions.get(0).getXDirAdj(), textPositions.get(0).getYDirAdj()));
            }
        }
    }

    public List<PackSlipData> extract(File packingSlipPdf) throws IOException {
        List<PackSlipData> allPagesData = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(packingSlipPdf)) {
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                PageBuffer buffer = new PageBuffer();
                buffer.setStartPage(p);
                buffer.setEndPage(p);
                buffer.getText(doc);
                PackSlipData pageData = parsePage(buffer.tokens);
                if (pageData != null) {
                    allPagesData.add(pageData);
                }
            }
        }
        return mergeConsecutiveOrders(allPagesData);
    }

    private PackSlipData parsePage(List<Token> tokens) {
        if (tokens.isEmpty()) return null;

        PackSlipData data = new PackSlipData();
        String lastSeenHeader = "";
        Map<String, Float> columnX = new HashMap<>();

        for (Token tk : tokens) {
            String text = tk.text.trim();
            String lowerText = text.toLowerCase(Locale.ROOT);

            if (lowerText.startsWith("ship to:")) {
                lastSeenHeader = "shipto";
                data.shippingAddress = "";
                continue;
            } else if (lowerText.startsWith("order id:")) {
                data.orderId = text.substring(text.indexOf(':') + 1).trim();
                lastSeenHeader = "";
                continue;
            } else if ("order date:".equals(lowerText)) {
                lastSeenHeader = "orderdate";
                continue;
            } else if ("shipping service:".equals(lowerText)) {
                lastSeenHeader = "shippingservice";
                continue;
            } else if ("buyer name:".equals(lowerText)) {
                lastSeenHeader = "buyername";
                continue;
            } else if ("seller name:".equals(lowerText)) {
                lastSeenHeader = "sellername";
                continue;
            } else if (lowerText.startsWith("grand total:")) {
                data.grandTotal = text.substring(text.indexOf(':') + 1).trim();
                lastSeenHeader = "grandtotal";
                continue;
            }

            switch (lastSeenHeader) {
                case "shipto": data.shippingAddress = appendToken(data.shippingAddress, text); break;
                case "orderdate": data.orderDate = appendToken(data.orderDate, text); lastSeenHeader = ""; break;
                case "shippingservice": data.shippingService = appendToken(data.shippingService, text); lastSeenHeader = ""; break;
                case "buyername": data.buyerName = appendToken(data.buyerName, text); lastSeenHeader = ""; break;
                case "sellername": data.sellerName = appendToken(data.sellerName, text); lastSeenHeader = ""; break;
            }

            if ("quantity".equals(lowerText)) columnX.put("qty", tk.x);
            if ("product details".equals(lowerText)) columnX.put("prod", tk.x);
            if ("unit price".equals(lowerText)) columnX.put("unit", tk.x);
            if ("order totals".equals(lowerText) || "item subtotal".equals(lowerText)) columnX.put("total", tk.x);
        }

        if (columnX.containsKey("qty") && columnX.containsKey("prod") && columnX.containsKey("unit")) {
            data.items.addAll(collectItems(tokens, columnX));
        }

        return isAllBlank(data) ? null : data;
    }

    private List<Item> collectItems(List<Token> tokens, Map<String, Float> columnX) {
        float qL = columnX.get("qty"), qR = columnX.get("prod");
        float pL = columnX.get("prod"), pR = columnX.get("unit");
        float uL = columnX.get("unit"), uR = columnX.getOrDefault("total", Float.MAX_VALUE);
        float tL = columnX.getOrDefault("total", -1f);

        List<List<Token>> lines = groupByLine(tokens);
        List<Item> items = new ArrayList<>();

        for (List<Token> line : lines) {
            String q = "", pd = "", up = "", tot = "";
            for (Token tk : line) {
                if (tk.x >= qL && tk.x < qR) q = appendToken(q, tk.text);
                else if (tk.x >= pL && tk.x < pR) pd = appendToken(pd, tk.text);
                else if (tk.x >= uL && tk.x < uR) up = appendToken(up, tk.text);
                else if (tL != -1 && tk.x >= tL) tot = appendToken(tot, tk.text);
            }

            if (!q.isBlank() && !pd.isBlank()) {
                Item item = new Item();
                item.quantity = q.trim();
                item.productDetails = pd.trim();
                item.unitPrice = up.trim();
                item.itemSubtotal = tot.trim();
                items.add(item);
            }
        }
        return items;
    }

    private List<List<Token>> groupByLine(List<Token> tokens) {
        Map<Float, List<Token>> linesMap = new TreeMap<>();
        for (Token t : tokens) {
            linesMap.computeIfAbsent(t.y, k -> new ArrayList<>()).add(t);
        }
        return new ArrayList<>(linesMap.values());
    }

    private static String appendToken(String base, String add) {
        if (base.isEmpty()) return add;
        return base + " " + add;
    }

    private static String normalize(String s) {
        s = s.replace('–', '-').replace('—', '-');
        return Normalizer.normalize(s, Normalizer.Form.NFKC);
    }

    private boolean isAllBlank(PackSlipData d) {
        return d.orderId.isBlank() && d.shippingAddress.isBlank() && d.items.isEmpty();
    }

    private List<PackSlipData> mergeConsecutiveOrders(List<PackSlipData> pages) {
        List<PackSlipData> merged = new ArrayList<>();
        if (pages.isEmpty()) return merged;
        PackSlipData currentOrder = pages.get(0);
        for (int i = 1; i < pages.size(); i++) {
            PackSlipData nextPage = pages.get(i);
            if (currentOrder.orderId.equals(nextPage.orderId)) {
                currentOrder.items.addAll(nextPage.items);
            } else {
                merged.add(currentOrder);
                currentOrder = nextPage;
            }
        }
        merged.add(currentOrder);
        return merged;
    }

    private static final Pattern ORDER_ID_RE =
            Pattern.compile("\\b\\d{3}-\\d{7}-\\d{7}\\b");

    private static String normalizePageText(String raw) {
        if (raw == null) return "";
        String s = normalize(raw);
        s = s.replace('\u00A0', ' ');
        s = s.replaceAll("[ \\t]+", " ").trim();
        return s;
    }

    public static Map<String, List<Integer>> indexOrderToPages(File packingSlipPdf) throws IOException {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        try (PDDocument doc = PDDocument.load(packingSlipPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            String currentOrderId = null;

            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = normalizePageText(stripper.getText(doc));

                String found = findOrderIdInPage(pageText);
                if (found != null) {
                    currentOrderId = found;
                    out.computeIfAbsent(currentOrderId, k -> new ArrayList<>()).add(p);
                } else if (currentOrderId != null) {
                    out.computeIfAbsent(currentOrderId, k -> new ArrayList<>()).add(p);
                }
            }
        }
        return out;
    }

    private static String findOrderIdInPage(String text) {
        if (text == null || text.isEmpty()) return null;

        int idx = indexOfIgnoreCase(text, "order id:");
        if (idx >= 0) {
            String tail = text.substring(idx + "order id:".length()).trim();
            Matcher m = ORDER_ID_RE.matcher(tail);
            if (m.find()) return m.group();
        }

        Matcher any = ORDER_ID_RE.matcher(text);
        if (any.find()) return any.group();

        return null;
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }
}
