package com.osman.cli;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OrnamentSkuTool {

    private static final String[] INPUT_PATHS = new String[]{};

    private static final Pattern ORDER_PATTERN = Pattern.compile("Order\\s*#\\s*:\\s*([0-9\\-]+)");
    private static final String KW_PACKING_SLIP = "Packing Slip";
    private static final String KW_CONTINUED = "Continued on Next Page";
    private static final String KW_NOT_CONTINUED = "Not Continued on Next Page";

    private enum Account { UNIQXMAS, OTHER }

    private OrnamentSkuTool() {
    }

    public static void main(String[] args) throws Exception {
        List<Path> inputs = resolveInputs();
        if (inputs.isEmpty()) {
            throw new IOException("No input file");
        }

        Path outDir = inputs.get(0).getParent().resolve("ready-ornaments");
        Files.createDirectories(outDir);

        Path tmpRoot = outDir.resolve("_tmp_multi");
        cleanDir(tmpRoot);
        Files.createDirectories(tmpRoot);

        List<List<Path>> singlePagesPerDoc = new ArrayList<>();
        List<List<Bundle>> bundlesPerDoc = new ArrayList<>();

        for (int docId = 0; docId < inputs.size(); docId++) {
            Path input = inputs.get(docId);
            Path tmpDir = tmpRoot.resolve("doc_" + docId);
            Files.createDirectories(tmpDir);

            try (PDDocument doc = PDDocument.load(input.toFile())) {
                List<Path> singlePageFiles = splitToSinglePages(doc, tmpDir);
                singlePagesPerDoc.add(singlePageFiles);
                List<Bundle> bundles = buildBundles(doc, docId);
                bundlesPerDoc.add(bundles);
            }
        }

        Map<String, List<BundleRef>> skuIndex = new LinkedHashMap<>();
        List<BundleRef> mixBundles = new ArrayList<>();

        for (int docId = 0; docId < bundlesPerDoc.size(); docId++) {
            for (Bundle b : bundlesPerDoc.get(docId)) {
                if (b.skus.isEmpty() || b.skus.size() > 1) {
                    mixBundles.add(new BundleRef(docId, b));
                }
                for (String sku : b.skus) {
                    skuIndex.computeIfAbsent(sku, k -> new ArrayList<>()).add(new BundleRef(docId, b));
                }
            }
        }

        for (Map.Entry<String, List<BundleRef>> entry : skuIndex.entrySet()) {
            String sku = entry.getKey();
            List<BundleRef> list = entry.getValue();
            Path skuOut = outDir.resolve(sanitize(sku) + ".pdf");
            mergeBundles(singlePagesPerDoc, list, skuOut);
        }

        if (!mixBundles.isEmpty()) {
            Path mixOut = outDir.resolve("MIX.pdf");
            mergeBundles(singlePagesPerDoc, mixBundles, mixOut);
        }

        cleanDir(tmpRoot);
    }

    private static List<Path> resolveInputs() throws IOException {
        List<Path> inputs = new ArrayList<>();
        if (INPUT_PATHS.length > 0) {
            for (String p : INPUT_PATHS) {
                Path path = Path.of(p);
                if (Files.exists(path)) {
                    inputs.add(path);
                }
            }
        }
        return inputs;
    }

    private static List<Path> splitToSinglePages(PDDocument doc, Path tmpDir) throws IOException {
        Splitter splitter = new Splitter();
        splitter.setSplitAtPage(1);
        List<PDDocument> pages = splitter.split(doc);
        List<Path> files = new ArrayList<>(pages.size());
        int idx = 1;
        for (PDDocument p : pages) {
            Path f = tmpDir.resolve(String.format("page_%05d.pdf", idx));
            p.save(f.toFile());
            p.close();
            files.add(f);
            idx++;
        }
        return files;
    }

    private static List<Bundle> buildBundles(PDDocument doc, int docId) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        int total = doc.getNumberOfPages();

        Account account = detectAccount(doc);
        Pattern skuPattern = (account == Account.UNIQXMAS)
                ? OrnamentSkuPatterns.ACCOUNT1
                : OrnamentSkuPatterns.ACCOUNT2;

        List<Bundle> result = new ArrayList<>();
        Integer lastLabelPage = null;
        String lastLabelText = null;
        Bundle current = null;
        boolean inSlip = false;
        boolean prevContinued = false;

        for (int i = 0; i < total; i++) {
            stripper.setStartPage(i + 1);
            stripper.setEndPage(i + 1);
            String text = safe(stripper.getText(doc));
            String trimmed = text.trim();

            boolean isSlip = text.contains(KW_PACKING_SLIP);
            boolean isBlank = trimmed.isEmpty();
            boolean hasCont = text.contains(KW_CONTINUED);
            boolean hasNotCont = text.contains(KW_NOT_CONTINUED);

            if (isSlip) {
                if (current == null) {
                    if (lastLabelPage != null) {
                        current = new Bundle(docId);
                        current.labelPageIndex = lastLabelPage;
                        inSlip = true;
                        prevContinued = false;
                        if (lastLabelText != null) {
                            current.skus.addAll(extractSkus(lastLabelText, skuPattern, account));
                        }
                    }
                }
                if (current != null) {
                    current.slipPageIndices.add(i);
                    current.orderId = firstMatch(ORDER_PATTERN, text, current.orderId);
                    current.skus.addAll(extractSkus(text, skuPattern, account));
                    prevContinued = hasCont;
                    if (hasNotCont) {
                        result.add(current);
                        current = null;
                        inSlip = false;
                        prevContinued = false;
                    }
                }
                continue;
            }

            if (inSlip && prevContinued && isBlank) {
                if (current != null) {
                    current.slipPageIndices.add(i);
                }
                prevContinued = false;
                continue;
            }

            if (current != null) {
                result.add(current);
                current = null;
                inSlip = false;
                prevContinued = false;
            }
            lastLabelPage = i;
            lastLabelText = text;
        }

        if (current != null) {
            result.add(current);
        }

        List<Bundle> filtered = new ArrayList<>();
        for (Bundle b : result) {
            if (b.labelPageIndex != null && !b.slipPageIndices.isEmpty()) {
                filtered.add(b);
            }
        }
        return filtered;
    }

    private static Account detectAccount(PDDocument doc) throws IOException {
        PDFTextStripper s = new PDFTextStripper();
        int pages = Math.min(3, doc.getNumberOfPages());
        s.setStartPage(1);
        s.setEndPage(pages);
        String t = normalizeForSkuScan(s.getText(doc)).toLowerCase(Locale.ROOT);
        return t.contains("uniqxmas") ? Account.UNIQXMAS : Account.OTHER;
    }

    private static void mergeBundles(List<List<Path>> singlePagesPerDoc, List<BundleRef> bundles, Path outFile) throws IOException {
        if (bundles.isEmpty()) {
            return;
        }
        PDFMergerUtility mu = new PDFMergerUtility();
        mu.setDestinationFileName(outFile.toString());

        for (BundleRef ref : bundles) {
            List<Path> files = singlePagesPerDoc.get(ref.docId);
            Bundle b = ref.bundle;
            mu.addSource(files.get(b.labelPageIndex).toFile());
            for (Integer pi : b.slipPageIndices) {
                mu.addSource(files.get(pi).toFile());
            }
        }

        mu.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
    }

    private static Set<String> extractSkus(String text, Pattern skuPattern, Account account) {
        Set<String> out = new LinkedHashSet<>();
        String scan = normalizeForSkuScan(text);
        Matcher matcher = skuPattern.matcher(scan);
        while (matcher.find()) {
            String tok = normalizeSkuToken(matcher.group(), account);
            if (tok != null && !tok.isBlank()) {
                out.add(tok);
            }
        }
        return out;
    }

    private static String normalizeForSkuScan(String text) {
        if (text == null) return "";
        String t = text.replace("\u00AD", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\uFEFF", "");
        t = t.replaceAll("[\\t\\f\\r]+", " ");
        return t;
    }

    private static String normalizeSkuToken(String raw, Account account) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (account == Account.UNIQXMAS) {
            s = s.replaceFirst("^SLU\\b", "SKU").replaceFirst("^SKY\\b", "SKU");
        }
        s = s.replaceAll("\\s+", "");
        return s;
    }

    private static String firstMatch(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return fallback;
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void cleanDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private static final class Bundle {
        final int docId;
        Integer labelPageIndex;
        final List<Integer> slipPageIndices = new ArrayList<>();
        final Set<String> skus = new LinkedHashSet<>();
        String orderId;
        Bundle(int docId) {
            this.docId = docId;
        }
    }

    private record BundleRef(int docId, Bundle bundle) {
    }
}
