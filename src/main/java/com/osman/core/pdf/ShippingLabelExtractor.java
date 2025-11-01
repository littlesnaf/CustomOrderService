package com.osman.core.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility routines for extracting order IDs from Amazon shipping label PDFs.
 */
public final class ShippingLabelExtractor {

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("\\b\\d{3}-\\d{7}-\\d{7}\\b");
    private static final Pattern PACKING_SLIP_PATTERN =
        Pattern.compile("(?i)(?:^(?:\\d{2}[PRWB]|mix)(\\s*\\(\\d+\\))?\\.pdf$)|(?:^amazon.*\\.pdf$)");
    private static final Pattern PACKING_SLIP_FOLDER_PATTERN =
        Pattern.compile("^(?:\\d{2}\\s*[PRWB]|mix).*", Pattern.CASE_INSENSITIVE);
    private static final String[] INDEX_KEYWORDS = {
        "successful label purchase",
        "list of orders"
    };

    private ShippingLabelExtractor() {
    }

    public static Map<String, List<Integer>> extractOrderIdToPages(Path pdf) throws IOException {
        if (pdf == null || !Files.isRegularFile(pdf)) {
            throw new IOException("Labels PDF not found: " + pdf);
        }
        if (isPackingSlipFile(pdf)) {
            return new LinkedHashMap<>();
        }

        try (PDDocument document = PDDocument.load(pdf.toFile())) {
            return extractOrderIdToPages(document);
        }
    }

    public static Map<String, List<Integer>> extractOrderIdToPages(PDDocument doc) throws IOException {
        Map<String, List<Integer>> map = new LinkedHashMap<>();
        int pageCount = doc.getNumberOfPages();
        if (pageCount <= 0) {
            return map;
        }

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);

        if (pageCount == 1) {
            String pageText = extractPageText(stripper, doc, 1);
            collectOrderIdsFromPage(map, pageText, 1);
            return map;
        }

        int indexStartPage = -1;
        for (int p = pageCount; p >= 1; p--) {
            String pageText = extractPageText(stripper, doc, p);
            if (pageText == null) {
                continue;
            }
            String lower = pageText.toLowerCase(Locale.ROOT);
            boolean matchesKeyword = false;
            for (String keyword : INDEX_KEYWORDS) {
                if (lower.contains(keyword)) {
                    matchesKeyword = true;
                    break;
                }
            }
            if (matchesKeyword) {
                indexStartPage = p;
                break;
            }
        }

        if (indexStartPage == -1) {
            for (int p = 1; p <= pageCount; p++) {
                String pageText = extractPageText(stripper, doc, p);
                collectOrderIdsFromPage(map, pageText, p);
            }
            return map;
        }

        StringBuilder fullIndexText = new StringBuilder();
        for (int p = indexStartPage; p <= pageCount; p++) {
            String pageText = extractPageText(stripper, doc, p);
            if (pageText != null) {
                fullIndexText.append(pageText).append(System.lineSeparator());
            }
        }

        List<String> orderIds = new ArrayList<>();
        Matcher matcher = ORDER_ID_PATTERN.matcher(fullIndexText.toString());
        while (matcher.find()) {
            orderIds.add(matcher.group());
        }

        if (!orderIds.isEmpty()) {
            int labelPageCount = indexStartPage - 1;
            int ordersToMapCount = Math.min(orderIds.size(), labelPageCount);
            for (int i = 0; i < ordersToMapCount; i++) {
                String orderId = orderIds.get(i);
                int pageNum = i + 1;
                map.computeIfAbsent(orderId, k -> new ArrayList<>()).add(pageNum);
            }
        }

        return map;
    }

    private static void collectOrderIdsFromPage(Map<String, List<Integer>> target, String pageText, int pageNumber) {
        if (pageText == null || pageText.isBlank()) {
            return;
        }
        Matcher matcher = ORDER_ID_PATTERN.matcher(pageText);
        while (matcher.find()) {
            String orderId = matcher.group();
            List<Integer> pages = target.computeIfAbsent(orderId, key -> new ArrayList<>());
            if (!pages.contains(pageNumber)) {
                pages.add(pageNumber);
            }
        }
    }

    public static ScanResult scan(Path input) throws IOException {
        if (input == null) {
            return new ScanResult(Map.of(), Map.of(), List.of(), List.of());
        }
        return scan(List.of(input));
    }

    public static ScanResult scan(Collection<Path> inputs) throws IOException {
        if (inputs == null || inputs.isEmpty()) {
            return new ScanResult(Map.of(), Map.of(), List.of(), List.of());
        }

        Map<String, LabelEntry> labels = new LinkedHashMap<>();
        Map<String, List<LabelEntry>> duplicates = new LinkedHashMap<>();
        List<Path> skippedPackingSlips = new ArrayList<>();
        List<ScanFailure> failures = new ArrayList<>();

        for (Path input : inputs) {
            if (input == null) {
                continue;
            }
            if (Files.isDirectory(input)) {
                try (Stream<Path> stream = Files.walk(input)) {
                    stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                        .forEach(p -> processFile(p, labels, duplicates, skippedPackingSlips, failures));
                }
            } else if (Files.isRegularFile(input)
                && input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                processFile(input, labels, duplicates, skippedPackingSlips, failures);
            }
        }

        Map<String, LabelEntry> immutableLabels = Collections.unmodifiableMap(labels);
        Map<String, List<LabelEntry>> immutableDuplicates = duplicates.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> List.copyOf(entry.getValue())
            ));

        return new ScanResult(
            immutableLabels,
            immutableDuplicates,
            List.copyOf(skippedPackingSlips),
            List.copyOf(failures)
        );
    }

    private static void processFile(Path pdf,
                                    Map<String, LabelEntry> labels,
                                    Map<String, List<LabelEntry>> duplicates,
                                    List<Path> skippedPackingSlips,
                                    List<ScanFailure> failures) {
        try {
            if (isPackingSlipFile(pdf)) {
                skippedPackingSlips.add(pdf);
                return;
            }
            Map<String, List<Integer>> orderPages = extractOrderIdToPages(pdf);
            for (Map.Entry<String, List<Integer>> entry : orderPages.entrySet()) {
                LabelEntry candidate = new LabelEntry(pdf, List.copyOf(entry.getValue()));
                LabelEntry existing = labels.putIfAbsent(entry.getKey(), candidate);
                if (existing != null) {
                    labels.put(entry.getKey(), existing);
                    duplicates.computeIfAbsent(entry.getKey(), key -> {
                        List<LabelEntry> list = new ArrayList<>();
                        list.add(existing);
                        return list;
                    }).add(candidate);
                }
            }
        } catch (IOException ex) {
            failures.add(new ScanFailure(pdf, ex.getMessage()));
        }
    }

    public static boolean isPackingSlipFile(Path pdf) {
        if (pdf == null) {
            return false;
        }
        String name = pdf.getFileName().toString();
        if (PACKING_SLIP_PATTERN.matcher(name).matches()) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("amazon")) {
            Path parent = pdf.getParent();
            if (parent != null) {
                Path parentName = parent.getFileName();
                if (parentName != null && PACKING_SLIP_FOLDER_PATTERN.matcher(parentName.toString()).matches()) {
                    return true;
                }
            }
            return true;
        }
        return false;
    }

    private static String extractPageText(PDFTextStripper stripper, PDDocument doc, int pageIndex1Based) throws IOException {
        stripper.setStartPage(pageIndex1Based);
        stripper.setEndPage(pageIndex1Based);
        return stripper.getText(doc);
    }

    public record LabelEntry(Path pdfPath, List<Integer> pages) {
        public LabelEntry {
            Objects.requireNonNull(pdfPath, "pdfPath");
            Objects.requireNonNull(pages, "pages");
        }
    }

    public record ScanFailure(Path pdfPath, String message) {
        public ScanFailure {
            Objects.requireNonNull(pdfPath, "pdfPath");
            Objects.requireNonNull(message, "message");
        }
    }

    public record ScanResult(Map<String, LabelEntry> labelsByOrder,
                             Map<String, List<LabelEntry>> duplicateLabels,
                             List<Path> skippedPackingSlips,
                             List<ScanFailure> failures) {
    }
}
