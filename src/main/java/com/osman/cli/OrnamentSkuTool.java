package com.osman.cli;

import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLI workflow that splits merged ornament PDFs exported from Amazon into per-SKU packets.
 */
public final class OrnamentSkuTool {

    private static final String[] INPUT_PATHS = new String[]{};

    private static final Pattern ORDER_PATTERN = Pattern.compile("Order\\s*#\\s*:\\s*([0-9\\-]+)");
    private static final Pattern RE_CONT =
            Pattern.compile("\\bcontinued\\s*on\\s*next\\s*page\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    private static final Pattern RE_NOT_CONT =
            Pattern.compile("\\bnot\\s*continued\\s*on\\s*next\\s*page\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    private static final Pattern SKU1847_EXCEPTION_PATTERN =
            Pattern.compile("(?i)SKU1847-\\s*P\\.OR[_\\s]?NEW");
    private static final String SKU1847_EXCEPTION_TOKEN =
            OrnamentSkuNormalizer.normalizeToken("SKU1847-P.OR");

    private OrnamentSkuTool() {}

    public static void main(String[] args) throws Exception {
        List<Path> inputs = resolveInputs(args);
        if (inputs.isEmpty()) throw new IOException("No input file");

        Path outDir = inputs.get(0).getParent().resolve("ready-ornaments");
        Files.createDirectories(outDir);

        Path tmpRoot = outDir.resolve("_tmp_multi");
        cleanDir(tmpRoot);
        Files.createDirectories(tmpRoot);

        try (OrnamentDebugLogger debug = OrnamentDebugLogger.create(outDir)) {
            List<List<Path>> singlePagesPerDoc = new ArrayList<>();
            List<List<Bundle>> bundlesPerDoc = new ArrayList<>();

            for (int docId = 0; docId < inputs.size(); docId++) {
                Path input = inputs.get(docId);
                Path tmpDir = tmpRoot.resolve("doc_" + docId);
                Files.createDirectories(tmpDir);

                try (PDDocument doc = PDDocument.load(input.toFile())) {
                    List<Path> singlePageFiles = splitToSinglePages(doc, tmpDir);
                    singlePagesPerDoc.add(singlePageFiles);
                    List<Bundle> bundles = buildBundles(doc, docId, debug);
                    bundlesPerDoc.add(bundles);
                }
            }

            Map<String, Integer> rawTotals = new LinkedHashMap<>();

            for (List<Bundle> docBundles : bundlesPerDoc) {
                for (Bundle b : docBundles) {
                    LinkedHashSet<String> canonical = new LinkedHashSet<>(OrnamentSkuNormalizer.canonicalizeTokens(b.skus));
                    b.skus.clear();
                    b.skus.addAll(canonical);
                    for (String sku : canonical) {
                        rawTotals.merge(sku, 1, Integer::sum);
                    }
                }
            }

            Map<String, Integer> skuTotals = OrnamentSkuNormalizer.canonicalizeTotals(rawTotals);
            LinkedHashSet<String> bigSkus = new LinkedHashSet<>();
            for (Map.Entry<String, Integer> e : skuTotals.entrySet()) {
                Integer qty = e.getValue();
                if (qty != null && qty >= 3) {
                    bigSkus.add(e.getKey());
                }
            }

            Map<String, List<BundleRef>> finalSkuIndex = new LinkedHashMap<>();
            List<BundleRef> mixedOutput = new ArrayList<>();

            for (int docId = 0; docId < bundlesPerDoc.size(); docId++) {
                for (Bundle b : bundlesPerDoc.get(docId)) {
                    BundleRef ref = new BundleRef(docId, b);
                    LinkedHashSet<String> matchedBig = new LinkedHashSet<>();
                    for (String sku : b.skus) {
                        if (bigSkus.contains(sku)) {
                            matchedBig.add(sku);
                        }
                    }
                    if (matchedBig.size() == 1) {
                        String targetSku = matchedBig.iterator().next();
                        finalSkuIndex.computeIfAbsent(targetSku, k -> new ArrayList<>()).add(ref);
                    } else {
                        mixedOutput.add(ref);
                    }
                }
            }

            for (Map.Entry<String, List<BundleRef>> entry : finalSkuIndex.entrySet()) {
                String sku = entry.getKey();
                List<BundleRef> list = entry.getValue();
                if (!list.isEmpty()) {
                    Path skuOut = outDir.resolve(sanitize(sku) + ".pdf");
                    Integer total = skuTotals.get(sku);
                    int qty = total == null ? list.size() : total;
                    OrnamentBundleMerger.SummaryPage summary =
                            new OrnamentBundleMerger.SummaryPage(sku, qty);
                    OrnamentBundleMerger.merge(singlePagesPerDoc, list, skuOut, summary);
                }
            }

            if (!mixedOutput.isEmpty()) {
                writeMixedSectionBundles(outDir, singlePagesPerDoc, mixedOutput);
            }

        } finally {
            cleanDir(tmpRoot);
        }
    }

    private static List<Path> resolveInputs(String[] args) throws IOException {
        List<Path> inputs = new ArrayList<>();

        // 1) CLI arguments
        if (args != null && args.length > 0) {
            for (String p : args) {
                if (p == null || p.isBlank()) continue;
                Path path = Path.of(p.trim());
                if (Files.exists(path)) inputs.add(path);
            }
        }

        // 2) System property (comma-separated): -Dornament.inputs=/path/a.pdf,/path/b.pdf
        if (inputs.isEmpty()) {
            String csv = System.getProperty("ornament.inputs");
            if (csv != null && !csv.isBlank()) {
                for (String p : csv.split(",")) {
                    String s = p.trim();
                    if (s.isEmpty()) continue;
                    Path path = Path.of(s);
                    if (Files.exists(path)) inputs.add(path);
                }
            }
        }

        // 3) Fallback constant (if populated)
        if (inputs.isEmpty() && INPUT_PATHS.length > 0) {
            for (String p : INPUT_PATHS) {
                Path path = Path.of(p);
                if (Files.exists(path)) inputs.add(path);
            }
        }

        if (inputs.isEmpty()) throw new IOException("No input file");
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

    /**
     * Rule:
     * - If the previous page had "Continued on Next Page", the current page is slip (regardless of flags).
     * - If current page has "Continued on Next Page" or "Not Continued on Next Page", it is slip.
     * - Otherwise, it is a shipping label.
     * - "Not Continued on Next Page" closes the bundle immediately.
     */
    private static List<Bundle> buildBundles(PDDocument doc, int docId, OrnamentDebugLogger debug)
            throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        int total = doc.getNumberOfPages();

        Pattern skuPattern = OrnamentSkuPatterns.ANY;

        List<Bundle> result = new ArrayList<>();
        Bundle current = null;
        Integer lastLabelPage = null;
        String lastLabelRaw = null;
        boolean prevWasContinued = false;

        for (int i = 0; i < total; i++) {
            stripper.setStartPage(i + 1);
            stripper.setEndPage(i + 1);

            String raw = safe(stripper.getText(doc));
            String text = norm(raw);
            debug.logPage(docId, i, text);

            boolean curHasCont = hasContinuedFlag(text);
            boolean curHasNotCont = hasNotContinuedFlag(text);
            boolean curHasAnyFlag = curHasCont || curHasNotCont;

            boolean treatAsSlip = prevWasContinued || curHasAnyFlag;

            if (!treatAsSlip) {
                if (current != null) {
                    finalizeBundle(result, current, debug);
                    current = null;
                }
                lastLabelPage = i;
                lastLabelRaw = raw;
                prevWasContinued = false;
                continue;
            }

            if (current == null) {
                if (lastLabelPage != null) {
                    current = new Bundle(docId);
                    current.labelPageIndex = lastLabelPage;
                    current.orderId = firstMatch(ORDER_PATTERN, lastLabelRaw, current.orderId);
                    current.skus.addAll(extractSkus(lastLabelRaw, skuPattern, debug));
                    debug.logSkuSet("Label page " + (lastLabelPage + 1), current.skus);
                } else {
                    prevWasContinued = curHasCont;
                    continue;
                }
            }

            current.slipPageIndices.add(i);
            current.orderId = firstMatch(ORDER_PATTERN, raw, current.orderId);
            current.skus.addAll(extractSkus(raw, skuPattern, debug));
            debug.logSkuSet("Slip page " + (i + 1), current.skus);

            if (curHasNotCont) {
                finalizeBundle(result, current, debug);
                current = null;
                prevWasContinued = false;
            } else {
                prevWasContinued = curHasCont;
            }
        }

        if (current != null && !current.slipPageIndices.isEmpty()) {
            finalizeBundle(result, current, debug);
        }

        return result;
    }

    private static String norm(String text) {
        if (text == null) return "";
        return text.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean hasContinuedFlag(String text) {
        return RE_CONT.matcher(text).find();
    }

    private static boolean hasNotContinuedFlag(String text) {
        return RE_NOT_CONT.matcher(text).find();
    }

    private static Set<String> extractSkus(String text, Pattern skuPattern, OrnamentDebugLogger debug) {
        Set<String> out = new LinkedHashSet<>();
        String scan = OrnamentSkuNormalizer.normalizeForScan(text);
        Matcher matcher = skuPattern.matcher(scan);
        while (matcher.find()) {
            String raw = matcher.group();
            String tok = OrnamentSkuNormalizer.normalizeToken(raw);
            debug.logToken(raw, tok);
            if (tok != null && !tok.isBlank()) out.add(tok);
        }
        addSku1847Exception(scan, out, debug);
        return OrnamentSkuNormalizer.canonicalizeTokens(out);
    }

    private static String firstMatch(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static void addSku1847Exception(String text, Set<String> out, OrnamentDebugLogger debug) {
        if (SKU1847_EXCEPTION_TOKEN == null || SKU1847_EXCEPTION_TOKEN.isBlank()) {
            return;
        }
        if (!SKU1847_EXCEPTION_PATTERN.matcher(text).find()) {
            return;
        }

        String canonical = SKU1847_EXCEPTION_TOKEN.toUpperCase(Locale.ROOT);

        debug.logToken("SKU1847-P.OR_NEW override", SKU1847_EXCEPTION_TOKEN);
        out.add(SKU1847_EXCEPTION_TOKEN);

        out.removeIf(token -> {
            if (token == null) {
                return false;
            }
            String upper = token.toUpperCase(Locale.ROOT);

            return upper.contains("SKU1847") && !upper.equals(canonical);
        });
    }

    private static String safe(String text) { return text == null ? "" : text; }

    private static String sanitize(String name) { return name.replaceAll("[^a-zA-Z0-9._-]", "_"); }

    private static void finalizeBundle(List<Bundle> result, Bundle bundle, OrnamentDebugLogger debug) {
        if (bundle == null) return;
        if (bundle.labelPageIndex != null && !bundle.slipPageIndices.isEmpty()) {
            debug.logBundleCompleted(bundle.orderId, bundle.skus);
            result.add(bundle);
        }
    }

    private static void cleanDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
        }
    }

    private static void writeMixedSectionBundles(Path outDir,
                                                 List<List<Path>> singlePagesPerDoc,
                                                 List<BundleRef> bundles) throws IOException {
        Path mixDir = outDir.resolve("mix");
        cleanDir(mixDir);
        Files.createDirectories(mixDir);

        Map<String, List<BundleRef>> grouped = new LinkedHashMap<>();
        for (String section : OrnamentSkuSections.primarySections()) {
            grouped.put(section, new ArrayList<>());
        }

        for (BundleRef ref : bundles) {
            String section = OrnamentSkuSections.resolveSection(ref.bundle().skus);
            grouped.computeIfAbsent(section, k -> new ArrayList<>()).add(ref);
        }

        Set<String> usedNames = new HashSet<>();

        for (Map.Entry<String, List<BundleRef>> entry : grouped.entrySet()) {
            List<BundleRef> refs = entry.getValue();
            if (refs.isEmpty()) {
                continue;
            }
            String section = entry.getKey();
            String fileName = section.replaceAll("[^A-Za-z0-9._-]", "_");
            if (fileName.isBlank()) {
                fileName = "Section";
            }
            String baseName = fileName;
            String candidate = baseName;
            int suffix = 1;
            while (!usedNames.add(candidate)) {
                candidate = baseName + "_" + suffix;
                suffix++;
            }
            Path sectionFile = mixDir.resolve(candidate + ".pdf");
            String summaryLabel = sectionFile.getFileName().toString();
            if (summaryLabel.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                summaryLabel = summaryLabel.substring(0, summaryLabel.length() - 4);
            }
            OrnamentBundleMerger.SummaryPage summary = new OrnamentBundleMerger.SummaryPage(summaryLabel);
            OrnamentBundleMerger.merge(singlePagesPerDoc, refs, sectionFile, summary);
        }
    }

    private static final class Bundle {
        final int docId;
        Integer labelPageIndex;
        final List<Integer> slipPageIndices = new ArrayList<>();
        final Set<String> skus = new LinkedHashSet<>();
        String orderId;
        Bundle(int docId) { this.docId = docId; }
    }

    private record BundleRef(int docId, Bundle bundle) implements OrnamentBundleMerger.BundlePages {
        @Override
        public int labelPageIndex() {
            if (bundle.labelPageIndex == null) {
                throw new IllegalStateException("Bundle missing label index");
            }
            return bundle.labelPageIndex;
        }

        @Override
        public List<Integer> slipPageIndices() {
            return bundle.slipPageIndices;
        }
    }
}
