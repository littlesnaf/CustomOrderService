package com.osman.cli;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command-line helper that splits merged ornament PDFs into per-SKU documents.
 */
public final class OrnamentSkuTool {

    private static final String[] INPUT_PATHS = new String[]{};

    private static final Pattern SKU_PATTERN = Pattern.compile("\\bSKU[0-9A-Z\\-]+(?:\\.[A-Z]+)?\\b");
    private static final Pattern ORDER_PATTERN = Pattern.compile("Order\\s*#\\s*:\\s*([0-9\\-]+)");
    private static final String KW_PACKING_SLIP = "Packing Slip";
    private static final String KW_CONTINUED = "Continued on Next Page";
    private static final String KW_NOT_CONTINUED = "Not Continued on Next Page";

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

        List<Bundle> result = new ArrayList<>();
        Integer lastLabelPage = null;
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
                    }
                }
                if (current != null) {
                    current.slipPageIndices.add(i);
                    current.orderId = firstMatch(ORDER_PATTERN, text, current.orderId);
                    current.skus.addAll(extractSkus(text));
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

    private static Set<String> extractSkus(String text) {
        Set<String> out = new java.util.LinkedHashSet<>();
        Matcher matcher = SKU_PATTERN.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group());
        }
        return out;
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
        final Set<String> skus = new java.util.LinkedHashSet<>();
        String orderId;

        Bundle(int docId) {
            this.docId = docId;
        }
    }

    private record BundleRef(int docId, Bundle bundle) {
    }
}
