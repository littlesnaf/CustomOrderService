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

    private OrnamentSkuTool() {}

    public static void main(String[] args) throws Exception {
        List<Path> inputs = resolveInputs();
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
                Path mixOut = outDir.resolve("MIXED.pdf");
                mergeBundles(singlePagesPerDoc, mixBundles, mixOut);
            }
        } finally {
            cleanDir(tmpRoot);
        }
    }

    private static List<Path> resolveInputs() throws IOException {
        List<Path> inputs = new ArrayList<>();
        if (INPUT_PATHS.length > 0) {
            for (String p : INPUT_PATHS) {
                Path path = Path.of(p);
                if (Files.exists(path)) inputs.add(path);
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

    private static void mergeBundles(List<List<Path>> singlePagesPerDoc, List<BundleRef> bundles, Path outFile) throws IOException {
        if (bundles.isEmpty()) return;
        PDFMergerUtility mu = new PDFMergerUtility();
        mu.setDestinationFileName(outFile.toString());
        for (BundleRef ref : bundles) {
            List<Path> files = singlePagesPerDoc.get(ref.docId);
            Bundle b = ref.bundle;
            mu.addSource(files.get(b.labelPageIndex).toFile());
            for (Integer pi : b.slipPageIndices) mu.addSource(files.get(pi).toFile());
        }
        mu.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
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
        return OrnamentSkuNormalizer.canonicalizeTokens(out);
    }

    private static String firstMatch(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : fallback;
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

    private static final class Bundle {
        final int docId;
        Integer labelPageIndex;
        final List<Integer> slipPageIndices = new ArrayList<>();
        final Set<String> skus = new LinkedHashSet<>();
        String orderId;
        Bundle(int docId) { this.docId = docId; }
    }

    private record BundleRef(int docId, Bundle bundle) {}
}
