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
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OrnamentSkuUI extends JFrame {

    private final DefaultListModel<Path> inputListModel = new DefaultListModel<>();
    private final JTextField outputDirField = new JTextField();
    private final JTextArea logArea = new JTextArea();

    private static final Pattern SKU_PATTERN = Pattern.compile("\\bSKU[0-9A-Z\\-]+(?:\\.[A-Z]+)?\\b");
    private static final Pattern ORDER_PATTERN = Pattern.compile("Order\\s*#\\s*:\\s*([0-9\\-]+)");
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
        inputPanel.setBorder(BorderFactory.createTitledBorder("Input PDFs"));
        north.add(inputPanel);

        JList<Path> inputList = new JList<>(inputListModel);
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
        outPanel.setBorder(BorderFactory.createTitledBorder("Output Directory"));
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
                        if (b.skus.isEmpty() || b.skus.size() > 1) {
                            mixBundles.add(new BundleRef(docId, b));
                        }
                        for (String sku : b.skus) {
                            skuIndex.computeIfAbsent(sku, k -> new ArrayList<>()).add(new BundleRef(docId, b));
                        }
                    }
                }

                appendLog("Unique SKUs: " + skuIndex.size());

                for (Map.Entry<String, List<BundleRef>> e : skuIndex.entrySet()) {
                    String sku = e.getKey();
                    List<BundleRef> list = e.getValue();
                    Path skuOut = outDir.resolve(sanitize(sku) + ".pdf");
                    mergeBundles(singlePagesPerDoc, list, skuOut);
                    appendLog("Wrote: " + skuOut.getFileName() + " (" + list.size() + " bundles)");
                }

                if (!mixBundles.isEmpty()) {
                    Path mixOut = outDir.resolve("MIX.pdf");
                    mergeBundles(singlePagesPerDoc, mixBundles, mixOut);
                    appendLog("Wrote: MIX.pdf (" + mixBundles.size() + " bundles)");
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

    private void setUIEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            for (Component c : getContentPane().getComponents()) c.setEnabled(enabled);
            // Keep log scroll usable
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
            for (Integer pi : b.slipPageIndices) mu.addSource(files.get(pi).toFile());
        }

        mu.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
    }

    private static Set<String> extractSkus(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = SKU_PATTERN.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static String firstMatch(Pattern p, String text, String fallback) {
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1);
        return fallback;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String sanitize(String s) { return s.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private static void cleanDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private static class Bundle {
        final int docId;
        Integer labelPageIndex;
        final List<Integer> slipPageIndices = new ArrayList<>();
        final Set<String> skus = new LinkedHashSet<>();
        String orderId;
        Bundle(int docId) { this.docId = docId; }
    }

    private static class BundleRef {
        final int docId;
        final Bundle bundle;
        BundleRef(int docId, Bundle bundle) { this.docId = docId; this.bundle = bundle; }
    }
}
