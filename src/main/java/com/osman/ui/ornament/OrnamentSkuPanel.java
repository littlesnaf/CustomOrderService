package com.osman.ui.ornament;

import com.osman.cli.OrnamentBundleMerger;
import com.osman.cli.OrnamentDebugLogger;
import com.osman.cli.OrnamentSkuNormalizer;
import com.osman.cli.OrnamentSkuPatterns;
import com.osman.cli.OrnamentSkuSections;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Swing front-end for splitting merged ornament PDFs into per-SKU files.
 * Operators can drag-and-drop PDFs, choose an output folder, and launch the processing run.
 */
public class OrnamentSkuPanel extends JPanel {
    private final DefaultListModel<Path> inputListModel = new DefaultListModel<>();
    private final JTextField outputDirField = new JTextField();
    private final JTextArea logArea = new JTextArea();
    private JButton addBtn;
    private JButton removeBtn;
    private JButton clearBtn;
    private JButton chooseOutBtn;
    private JButton runBtn;
    private JList<Path> inputList;
    private static final Pattern SKU_PATTERN = OrnamentSkuPatterns.ANY;
    private static final Pattern ORDER_PATTERN = Pattern.compile("Order\\s*#\\s*:\\s*([0-9\\-]+)");
    private static final Pattern QTY_INLINE = Pattern.compile("(?i)\\bqty\\b\\s*[:x-]?\\s*(\\d{1,3})");
    private static final Pattern QTY_BEFORE_PRICE = Pattern.compile("(?s)\\b(\\d{1,3})\\s*\\$\\s*\\d"); // <-- güncel
    private static final Pattern STANDALONE_INT = Pattern.compile("^\\s*(\\d{1,3})\\s*$");
    private static final Pattern SKU1847_EXCEPTION_PATTERN =
            Pattern.compile("(?i)SKU1847-\\s*P\\.OR[_\\s]?NEW");
    private static final String SKU1847_EXCEPTION_TOKEN =
            OrnamentSkuNormalizer.normalizeToken("SKU1847-P.OR");

    public OrnamentSkuPanel() {
        setLayout(new BorderLayout(12, 12));
        setBorder(new EmptyBorder(12, 12, 12, 12));
        JPanel north = new JPanel(new GridLayout(2, 1, 8, 8));
        add(north, BorderLayout.NORTH);
        JPanel inputPanel = new JPanel(new BorderLayout(8, 8));
        inputPanel.setBorder(BorderFactory.createTitledBorder("Input PDFs (Drag & Drop supported)"));
        north.add(inputPanel);
        inputList = new JList<>(inputListModel);
        inputList.setVisibleRowCount(6);
        inputList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        inputPanel.add(new JScrollPane(inputList), BorderLayout.CENTER);
        JPanel inputBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        addBtn = new JButton("Add PDFs…");
        removeBtn = new JButton("Remove Selected");
        clearBtn = new JButton("Clear All");
        inputBtns.add(addBtn);
        inputBtns.add(removeBtn);
        inputBtns.add(clearBtn);
        inputPanel.add(inputBtns, BorderLayout.SOUTH);
        JPanel outPanel = new JPanel(new BorderLayout(8, 8));
        outPanel.setBorder(BorderFactory.createTitledBorder("Output Directory (Drag a folder here)"));
        north.add(outPanel);
        outputDirField.setEditable(false);
        chooseOutBtn = new JButton("Choose…");
        outPanel.add(outputDirField, BorderLayout.CENTER);
        outPanel.add(chooseOutBtn, BorderLayout.EAST);
        JPanel center = new JPanel(new BorderLayout(8, 8));
        add(center, BorderLayout.CENTER);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        center.add(new JScrollPane(logArea), BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        runBtn = new JButton("Run");
        south.add(runBtn);
        add(south, BorderLayout.SOUTH);
        addBtn.addActionListener(e -> onAddPdfs());
        removeBtn.addActionListener(e -> onRemoveSelected(inputList));
        clearBtn.addActionListener(e -> inputListModel.clear());
        chooseOutBtn.addActionListener(e -> onChooseOutput());
        runBtn.addActionListener(e -> onRun());
        inputList.setTransferHandler(new FileDropToListHandler());
        outputDirField.setTransferHandler(new DirDropToFieldHandler());
        setTransferHandler(new FileOrDirFallbackHandler());
        appendLog("Select merged PDFs and an output directory. Results will be written under: <output>/ready-orders/");
    }

    private void onAddPdfs() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("PDF files", "pdf"));
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("Select Merged PDFs");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            int added = 0;
            for (File f : fc.getSelectedFiles()) {
                Path p = f.toPath();
                if (Files.exists(p) && p.toString().toLowerCase().endsWith(".pdf")) {
                    inputListModel.addElement(p);
                    added++;
                }
            }
            appendLog("Added " + added + " file(s).");
        }
    }

    private void onRemoveSelected(JList<Path> list) {
        List<Path> sel = list.getSelectedValuesList();
        for (Path p : sel) inputListModel.removeElement(p);
        appendLog("Removed " + sel.size() + " item(s).");
    }

    private void onChooseOutput() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose Output Directory");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            outputDirField.setText(fc.getSelectedFile().getAbsolutePath());
            appendLog("Output directory: " + fc.getSelectedFile().getAbsolutePath());
        }
    }

    private static final int MIN_UNITS_FOR_DEDICATED_SKU = 3;

    private void onRun() {
        if (inputListModel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please add at least one input PDF.", "Missing Inputs", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (outputDirField.getText().isBlank()) {
            JOptionPane.showMessageDialog(this, "Please choose an output directory.", "Missing Output Directory", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<Path> inputs = Collections.list(inputListModel.elements());
        Path outRoot = Paths.get(outputDirField.getText());
        Path outDir = outRoot.resolve("ready-orders");
        new Thread(() -> {
            setUIEnabled(false);
            try {
                Files.createDirectories(outDir);
                appendLog("Output base: " + outDir);
                Path tmpRoot = outDir.resolve("_tmp_multi");
                cleanDir(tmpRoot);
                Files.createDirectories(tmpRoot);
                try (OrnamentDebugLogger debug = OrnamentDebugLogger.create(outDir)) {
                    List<List<Path>> singlePagesPerDoc = new ArrayList<>();
                    List<List<Bundle>> bundlesPerDoc = new ArrayList<>();
                    for (int docId = 0; docId < inputs.size(); docId++) {
                        Path input = inputs.get(docId);
                        if (!Files.exists(input)) {
                            appendLog("Skip (missing): " + input);
                            continue;
                        }
                        appendLog("Processing: " + input);
                        Path tmpDir = tmpRoot.resolve("doc_" + docId);
                        Files.createDirectories(tmpDir);
                        try (PDDocument doc = PDDocument.load(input.toFile())) {
                            List<Path> singlePageFiles = splitToSinglePages(doc, tmpDir);
                            singlePagesPerDoc.add(singlePageFiles);
                            List<Bundle> bundles = buildBundles(doc, docId, debug);
                            bundlesPerDoc.add(bundles);
                            appendLog("Bundles found in doc " + docId + ": " + bundles.size());
                        }
                    }

                    Map<String, List<BundleRef>> skuIndex = new LinkedHashMap<>();
                    List<BundleRef> mixBundles = new ArrayList<>();
                    for (int docId = 0; docId < bundlesPerDoc.size(); docId++) {
                        for (Bundle b : bundlesPerDoc.get(docId)) {
                            if (b.skus.size() != 1) {
                                mixBundles.add(new BundleRef(docId, b));
                            } else {
                                String sku = b.skus.iterator().next();
                                skuIndex.computeIfAbsent(sku, k -> new ArrayList<>()).add(new BundleRef(docId, b));
                            }
                        }
                    }


                    appendLog("Checking for low quantity SKUs...");
                    List<BundleRef> lowQtyBundles = new ArrayList<>();
                    Map<String, List<BundleRef>> finalSkuIndex = new LinkedHashMap<>();

                    for (Map.Entry<String, List<BundleRef>> entry : skuIndex.entrySet()) {
                        String sku = entry.getKey();
                        List<BundleRef> refs = entry.getValue();
                        int totalUnits = calculateTotalUnitsForSku(sku, refs);
                        if (totalUnits < MIN_UNITS_FOR_DEDICATED_SKU) {
                            lowQtyBundles.addAll(refs);
                            appendLog("  -> SKU " + sku + " has " + totalUnits + " unit(s); moving to mix.");
                        } else {
                            finalSkuIndex.put(sku, refs);
                            appendLog("  -> SKU " + sku + " has " + totalUnits + " unit(s); dedicating output PDF.");
                        }
                    }

                    appendLog("High-volume SKUs: " + finalSkuIndex.size());

                    for (Map.Entry<String, List<BundleRef>> e : finalSkuIndex.entrySet()) {
                        String sku = e.getKey();
                        List<BundleRef> list = e.getValue();
                        Path skuOut = outDir.resolve(sanitize(sku) + ".pdf");
                        int totalUnits = calculateTotalUnitsForSku(sku, list);
                        OrnamentBundleMerger.SummaryPage summary =
                                new OrnamentBundleMerger.SummaryPage(sku, totalUnits);
                        OrnamentBundleMerger.merge(singlePagesPerDoc, list, skuOut, summary);
                        appendLog("Wrote: " + skuOut.getFileName() + " (" + list.size() + " bundles)");
                    }

                    List<BundleRef> mixedOutput = new ArrayList<>(mixBundles.size() + lowQtyBundles.size());
                    mixedOutput.addAll(mixBundles);
                    mixedOutput.addAll(lowQtyBundles);
                    if (!mixedOutput.isEmpty()) {
                        writeMixedSectionBundles(outDir, singlePagesPerDoc, mixedOutput);
                        appendLog("Wrote: mix sections (" + mixedOutput.size()
                                + " bundles from mixed or low-quantity SKUs)");
                    }
                    renameSkuFilesWithCounts(outDir, finalSkuIndex);
                }

                cleanDir(tmpRoot);
                appendLog("Done.");
                JOptionPane.showMessageDialog(this, "Finished.", "OK", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                appendLog("ERROR: " + ex.getMessage());
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            } finally {
                setUIEnabled(true);
            }
        }).start();
    }

    private void renameSkuFilesWithCounts(Path outDir, Map<String, List<BundleRef>> skuIndex) {
        for (Map.Entry<String, List<BundleRef>> e : skuIndex.entrySet()) {
            String sku = e.getKey();
            int totalCount = calculateTotalUnitsForSku(sku, e.getValue());
            String base = sanitize(sku);
            Path oldFile = outDir.resolve(base + ".pdf");
            if (!Files.exists(oldFile)) continue;
            String targetName = "x" + totalCount + "-" + base + ".pdf";
            Path target = outDir.resolve(targetName);
            int suffix = 1;
            while (Files.exists(target)) {
                targetName = "x" + totalCount + "-" + base + "_" + suffix + ".pdf";
                target = outDir.resolve(targetName);
                suffix++;
            }
            try {
                Files.move(oldFile, target, StandardCopyOption.ATOMIC_MOVE);
                appendLog("Renamed: " + oldFile.getFileName() + " -> " + target.getFileName());
            } catch (IOException ioe) {
                appendLog("Rename failed for " + oldFile.getFileName() + ": " + ioe.getMessage());
            }
        }
    }

    private static int calculateTotalUnitsForSku(String sku, List<BundleRef> refs) {
        int total = 0;
        for (BundleRef ref : refs) {
            total += ref.bundle.skuCounts.getOrDefault(sku, 0);
        }
        if (total <= 0) {
            total = refs.size();
        }
        return total;
    }

    private void writeMixedSectionBundles(Path outDir,
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
            String section = OrnamentSkuSections.resolveSection(ref.bundle.skus);
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
            appendLog("Wrote: mix/" + sectionFile.getFileName());
        }
    }

    private void setUIEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            addBtn.setEnabled(enabled);
            removeBtn.setEnabled(enabled);
            clearBtn.setEnabled(enabled);
            chooseOutBtn.setEnabled(enabled);
            runBtn.setEnabled(enabled);
            inputList.setEnabled(enabled);
        });
    }

    private void appendLog(String s) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(s + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
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

    private static List<Bundle> buildBundles(PDDocument doc, int docId, OrnamentDebugLogger debug) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        int total = doc.getNumberOfPages();
        List<Bundle> result = new ArrayList<>();
        Bundle current = null;
        Integer lastLabelPage = null;
        String lastLabelText = null;
        boolean pendingContinuation = false;

        for (int i = 0; i < total; i++) {
            stripper.setStartPage(i + 1);
            stripper.setEndPage(i + 1);

            String pageText = safe(stripper.getText(doc));
            debug.logPage(docId, i, pageText);

            String normalized = normalizeForMarkers(pageText);
            boolean hasContinued = normalized.contains("continued on next page");
            boolean hasNotContinued = normalized.contains("not continued on next page");
            boolean hasPackingSlip = normalized.contains("packing slip");
            boolean isSlipMarker = hasPackingSlip || hasContinued || hasNotContinued;
            boolean isBlank = normalized.isEmpty();

            if (isSlipMarker || (pendingContinuation && isBlank)) {
                if (current == null && lastLabelPage != null && lastLabelText != null) {
                    current = new Bundle(docId);
                    current.labelPageIndex = lastLabelPage;
                    current.orderId = firstMatch(ORDER_PATTERN, lastLabelText, current.orderId);
                    Map<String, Integer> labelOcc = countSkuOccurrences(lastLabelText, debug);
                    current.skus.addAll(labelOcc.keySet());
                    for (Map.Entry<String, Integer> en : labelOcc.entrySet()) {
                        current.skuCounts.merge(en.getKey(), en.getValue(), Integer::sum);
                    }
                    debug.logSkuSet("Label page " + (lastLabelPage + 1), current.skus);
                }

                if (current == null) {
                    pendingContinuation = hasContinued;
                    continue;
                }

                current.slipPageIndices.add(i);
                current.orderId = firstMatch(ORDER_PATTERN, pageText, current.orderId);
                Map<String, Integer> occ = countSkuOccurrences(pageText, debug);
                current.skus.addAll(occ.keySet());
                for (Map.Entry<String, Integer> en : occ.entrySet()) {
                    current.skuCounts.merge(en.getKey(), en.getValue(), Integer::sum);
                }
                debug.logSkuSet("Slip page " + (i + 1), current.skus);
                pendingContinuation = hasContinued;

                if (hasNotContinued) {
                    finalizeBundle(result, current, debug);
                    current = null;
                    pendingContinuation = false;
                }
                continue;
            }

            if (current != null) {
                finalizeBundle(result, current, debug);
                current = null;
            }
            lastLabelPage = i;
            lastLabelText = pageText;
            pendingContinuation = false;
        }

        if (current != null) {
            finalizeBundle(result, current, debug);
        }
        return result;
    }
    private static String normalizeForMarkers(String text) {
        if (text == null) return "";
        return text.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static void finalizeBundle(List<Bundle> result, Bundle bundle, OrnamentDebugLogger debug) {
        if (bundle == null) return;
        if (bundle.labelPageIndex != null && !bundle.slipPageIndices.isEmpty()) {
            debug.logBundleCompleted(bundle.orderId, bundle.skus);
            result.add(bundle);
        }
    }

    private static Map<String, Integer> countSkuOccurrences(String text, OrnamentDebugLogger debug) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        String scan = OrnamentSkuNormalizer.normalizeForScan(text);
        List<int[]> spans = new ArrayList<>();
        Matcher m = SKU_PATTERN.matcher(scan);
        while (m.find()) spans.add(new int[]{m.start(), m.end()});
        if (spans.isEmpty()) return totals;
        for (int i = 0; i < spans.size(); i++) {
            int start = spans.get(i)[0];
            int skuEnd = spans.get(i)[1];
            int end = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : scan.length();
            int cut = end;
            int p;
            p = scan.indexOf("Grand Total", skuEnd);
            if (p >= 0 && p < cut) cut = p;
            p = scan.indexOf("Not Continued on Next Page", skuEnd);
            if (p >= 0 && p < cut) cut = p;
            p = scan.indexOf("Continued on Next Page", skuEnd);
            if (p >= 0 && p < cut) cut = p;
            String block = scan.substring(start, cut);
            Matcher mSku = SKU_PATTERN.matcher(block);
            if (!mSku.find()) continue;
            String rawSku = mSku.group();
            String sku = OrnamentSkuNormalizer.normalizeToken(rawSku);
            debug.logToken(rawSku, sku);
            int qty = 1;
            Matcher q1 = QTY_INLINE.matcher(block);
            if (q1.find()) {
                try {
                    qty = Math.max(1, Integer.parseInt(q1.group(1)));
                } catch (NumberFormatException ignored) {
                }
            } else {
                Matcher q2 = QTY_BEFORE_PRICE.matcher(block);
                if (q2.find()) {
                    try {
                        qty = Math.max(1, Integer.parseInt(q2.group(1)));
                    } catch (NumberFormatException ignored) {
                    }
                } else {
                    int repeats = 0;
                    Matcher again = SKU_PATTERN.matcher(block);
                    while (again.find()) repeats++;
                    if (repeats >= 2) {
                        qty = repeats;
                    } else {
                        String[] lines = block.split("\\R+");
                        for (String ln : lines) {
                            String t = ln.trim();
                            if (t.isEmpty()) continue;
                            if (t.contains("/")) continue;
                            if (t.toLowerCase().contains("sku")) continue;
                            if (t.startsWith("Grand Total") || t.startsWith("Not Continued") || t.startsWith("Continued"))
                                break;
                            Matcher solo = STANDALONE_INT.matcher(t);
                            if (solo.matches()) {
                                try {
                                    int n = Integer.parseInt(solo.group(1));
                                    if (n >= 2 && n <= 999) {
                                        qty = n;
                                        break;
                                    }
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }
                }
            }
            totals.merge(sku, qty, Integer::sum);
        }
        applySku1847Override(scan, totals, debug);
        return totals;
    }

    private static String firstMatch(Pattern p, String text, String fallback) {
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1);
        return fallback;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void cleanDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void applySku1847Override(String text,
                                             Map<String, Integer> totals,
                                             OrnamentDebugLogger debug) {
        if (SKU1847_EXCEPTION_TOKEN == null || SKU1847_EXCEPTION_TOKEN.isBlank()) {
            return;
        }
        if (!SKU1847_EXCEPTION_PATTERN.matcher(text).find()) {
            return;
        }
        int qty = 0;
        Iterator<Map.Entry<String, Integer>> it = totals.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> entry = it.next();
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String upper = key.toUpperCase(Locale.ROOT);
            if (upper.contains("SKU1847-P.OR_NEW")
                    || upper.contains("SKU1847-P.ORNEW")
                    || upper.contains("SKU1847-.OR")) {
                qty += Math.max(0, entry.getValue());
                it.remove();
            }
        }
        if (qty <= 0) {
            qty = 1;
        }
        totals.merge(SKU1847_EXCEPTION_TOKEN, qty, Integer::sum);
        debug.logToken("SKU1847-P.OR_NEW override", SKU1847_EXCEPTION_TOKEN);
    }

    private static class Bundle {
        final int docId;
        Integer labelPageIndex;
        final List<Integer> slipPageIndices = new ArrayList<>();
        final Set<String> skus = new LinkedHashSet<>();
        final Map<String, Integer> skuCounts = new LinkedHashMap<>();
        String orderId;

        Bundle(int docId) {
            this.docId = docId;
        }
    }

    private static class BundleRef implements OrnamentBundleMerger.BundlePages {
        final int docId;
        final Bundle bundle;

        BundleRef(int docId, Bundle bundle) {
            this.docId = docId;
            this.bundle = bundle;
        }

        @Override
        public int docId() {
            return docId;
        }

        @Override
        public int labelPageIndex() {
            if (bundle.labelPageIndex == null) {
                throw new IllegalStateException("Bundle missing label page index");
            }
            return bundle.labelPageIndex;
        }

        @Override
        public List<Integer> slipPageIndices() {
            return bundle.slipPageIndices;
        }
    }

    private class FileDropToListHandler extends TransferHandler {
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                Transferable t = support.getTransferable();
                List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                int added = 0;
                for (File f : files) {
                    if (f.isDirectory()) {
                        try (var walk = Files.walk(f.toPath())) {
                            for (Path p : (Iterable<Path>) walk::iterator) {
                                if (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".pdf")) {
                                    inputListModel.addElement(p);
                                    added++;
                                }
                            }
                        }
                    } else if (f.getName().toLowerCase().endsWith(".pdf")) {
                        inputListModel.addElement(f.toPath());
                        added++;
                    }
                }
                if (added > 0) appendLog("Dropped " + added + " PDF(s).");
                return added > 0;
            } catch (Exception ex) {
                appendLog("Drop error: " + ex.getMessage());
                return false;
            }
        }
    }

    private class DirDropToFieldHandler extends TransferHandler {
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                Transferable t = support.getTransferable();
                List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                for (File f : files) {
                    if (f.isDirectory()) {
                        outputDirField.setText(f.getAbsolutePath());
                        appendLog("Output directory set via drop: " + f.getAbsolutePath());
                        return true;
                    }
                }
                appendLog("Drop a folder to set output directory.");
                return false;
            } catch (Exception ex) {
                appendLog("Drop error: " + ex.getMessage());
                return false;
            }
        }
    }

    private class FileOrDirFallbackHandler extends TransferHandler {
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                Transferable t = support.getTransferable();
                List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                boolean any = false;
                for (File f : files) {
                    if (f.isDirectory()) {
                        outputDirField.setText(f.getAbsolutePath());
                        appendLog("Output directory set via drop: " + f.getAbsolutePath());
                        any = true;
                    } else if (f.getName().toLowerCase().endsWith(".pdf")) {
                        inputListModel.addElement(f.toPath());
                        any = true;
                    }
                }
                if (any) appendLog("Drop handled on window.");
                return any;
            } catch (Exception ex) {
                appendLog("Drop error: " + ex.getMessage());
                return false;
            }
        }
    }
}
