package com.osman.ui;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
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
public class OrnamentSkuUI extends JFrame {
    private final DefaultListModel<Path> inputListModel = new DefaultListModel<>();
    private final JTextField outputDirField = new JTextField();
    private final JTextArea logArea = new JTextArea();
    private JList<Path> inputList;
    private static final Pattern SKU_PATTERN = Pattern.compile("\\bSKU[0-9A-Z\\-]+(?:\\.[A-Z]+)?\\b");
    private static final Pattern ORDER_PATTERN = Pattern.compile("Order\\s*#\\s*:\\s*([0-9\\-]+)");
    private static final Pattern QTY_INLINE = Pattern.compile("(?i)\\bqty\\b\\s*[:x-]?\\s*(\\d{1,3})");
    private static final Pattern QTY_BEFORE_PRICE = Pattern.compile("(?s)\\b(\\d{1,3})\\s*\\$\\s*\\d"); // <-- güncel
    private static final Pattern STANDALONE_INT = Pattern.compile("^\\s*(\\d{1,3})\\s*$");
    private static final String KW_PACKING_SLIP = "Packing Slip";
    private static final String KW_CONTINUED = "Continued on Next Page";
    private static final String KW_NOT_CONTINUED = "Not Continued on Next Page";

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new OrnamentSkuUI().setVisible(true));
    }

    public OrnamentSkuUI() {
        setTitle("Ornament SKU Splitter");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(900, 600);
        setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);
        JPanel north = new JPanel(new GridLayout(2, 1, 8, 8));
        root.add(north, BorderLayout.NORTH);
        JPanel inputPanel = new JPanel(new BorderLayout(8, 8));
        inputPanel.setBorder(BorderFactory.createTitledBorder("Input PDFs (Drag & Drop supported)"));
        north.add(inputPanel);
        inputList = new JList<>(inputListModel);
        inputList.setVisibleRowCount(6);
        inputList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        inputPanel.add(new JScrollPane(inputList), BorderLayout.CENTER);
        JPanel inputBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JButton addBtn = new JButton("Add PDFs…");
        JButton removeBtn = new JButton("Remove Selected");
        JButton clearBtn = new JButton("Clear All");
        inputBtns.add(addBtn);
        inputBtns.add(removeBtn);
        inputBtns.add(clearBtn);
        inputPanel.add(inputBtns, BorderLayout.SOUTH);
        JPanel outPanel = new JPanel(new BorderLayout(8, 8));
        outPanel.setBorder(BorderFactory.createTitledBorder("Output Directory (Drag a folder here)"));
        north.add(outPanel);
        outputDirField.setEditable(false);
        JButton chooseOutBtn = new JButton("Choose…");
        outPanel.add(outputDirField, BorderLayout.CENTER);
        outPanel.add(chooseOutBtn, BorderLayout.EAST);
        JPanel center = new JPanel(new BorderLayout(8, 8));
        root.add(center, BorderLayout.CENTER);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        center.add(new JScrollPane(logArea), BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton runBtn = new JButton("Run");
        south.add(runBtn);
        root.add(south, BorderLayout.SOUTH);
        addBtn.addActionListener(e -> onAddPdfs());
        removeBtn.addActionListener(e -> onRemoveSelected(inputList));
        clearBtn.addActionListener(e -> inputListModel.clear());
        chooseOutBtn.addActionListener(e -> onChooseOutput());
        runBtn.addActionListener(e -> onRun());
        inputList.setTransferHandler(new FileDropToListHandler());
        outputDirField.setTransferHandler(new DirDropToFieldHandler());
        root.setTransferHandler(new FileOrDirFallbackHandler());
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
                        List<Bundle> bundles = buildBundles(doc, docId);
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
                    if (entry.getValue().size() < 3) {
                  lowQtyBundles.addAll(entry.getValue());
                    } else {

                        finalSkuIndex.put(entry.getKey(), entry.getValue());
                    }
                }

                appendLog("High-volume SKUs: " + finalSkuIndex.size());

                for (Map.Entry<String, List<BundleRef>> e : finalSkuIndex.entrySet()) {
                    String sku = e.getKey();
                    List<BundleRef> list = e.getValue();
                    Path skuOut = outDir.resolve(sanitize(sku) + ".pdf");
                    mergeBundles(singlePagesPerDoc, list, skuOut);
                    appendLog("Wrote: " + skuOut.getFileName() + " (" + list.size() + " bundles)");
                }

                if (!mixBundles.isEmpty()) {
                    Path mixOut = outDir.resolve("MIXED SKU.pdf");
                    mergeBundles(singlePagesPerDoc, mixBundles, mixOut);
                    appendLog("Wrote: MIXED SKU.pdf (" + mixBundles.size() + " bundles)");
                }


                if (!lowQtyBundles.isEmpty()) {
                    Path lowQtyOut = outDir.resolve("MIXED QTY.pdf");
                    mergeBundles(singlePagesPerDoc, lowQtyBundles, lowQtyOut);
                    appendLog("Wrote: MIXED QTY.pdf (" + lowQtyBundles.size() + " bundles from low-volume SKUs)");
                }


                renameSkuFilesWithCounts(outDir, finalSkuIndex);

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
            int totalCount = 0;
            for (BundleRef br : e.getValue()) {
                totalCount += br.bundle.skuCounts.getOrDefault(sku, 0);
            }
            if (totalCount <= 0) totalCount = e.getValue().size();
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

    private void setUIEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            for (Component c : getContentPane().getComponents()) c.setEnabled(enabled);
            logArea.setEnabled(true);
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

    private static List<Bundle> buildBundles(PDDocument doc, int docId) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
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
                    Map<String, Integer> occ = countSkuOccurrences(text);
                    current.skus.addAll(occ.keySet());
                    for (Map.Entry<String, Integer> en : occ.entrySet()) {
                        current.skuCounts.merge(en.getKey(), en.getValue(), Integer::sum);
                    }
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
                if (current != null) current.slipPageIndices.add(i);
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
        if (current != null) result.add(current);
        List<Bundle> filtered = new ArrayList<>();
        for (Bundle b : result) {
            if (b.labelPageIndex != null && !b.slipPageIndices.isEmpty()) filtered.add(b);
        }
        return filtered;
    }

    private static void mergeBundles(List<List<Path>> singlePagesPerDoc, List<BundleRef> bundles, Path outFile) throws IOException {
        if (bundles.isEmpty()) return;
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

    private static Map<String, Integer> countSkuOccurrences(String text) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        List<int[]> spans = new ArrayList<>();
        Matcher m = SKU_PATTERN.matcher(text);
        while (m.find()) spans.add(new int[]{m.start(), m.end()});
        if (spans.isEmpty()) return totals;
        for (int i = 0; i < spans.size(); i++) {
            int start = spans.get(i)[0];
            int skuEnd = spans.get(i)[1];
            int end = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : text.length();
            int cut = end;
            int p;
            p = text.indexOf("Grand Total", skuEnd);
            if (p >= 0 && p < cut) cut = p;
            p = text.indexOf("Not Continued on Next Page", skuEnd);
            if (p >= 0 && p < cut) cut = p;
            p = text.indexOf("Continued on Next Page", skuEnd);
            if (p >= 0 && p < cut) cut = p;
            String block = text.substring(start, cut);
            Matcher mSku = SKU_PATTERN.matcher(block);
            if (!mSku.find()) continue;
            String sku = mSku.group();
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

    private static class BundleRef {
        final int docId;
        final Bundle bundle;

        BundleRef(int docId, Bundle bundle) {
            this.docId = docId;
            this.bundle = bundle;
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