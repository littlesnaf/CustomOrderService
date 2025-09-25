package com.osman.ui;

import com.osman.PackSlipExtractor;
import com.osman.PdfLinker;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;

public class OrnamentMatcherUI extends JFrame {

    // --- UI Bileşenleri ---
    private final JTextField labelsField;
    private final JTextField slipsField;
    private final JButton chooseLabelsBtn;
    private final JButton chooseSlipsBtn;

    private final JButton generateMatchedBtn; // order-by-order tek PDF
    private final JButton generateBySkuBtn;   // SKU'ya göre çoklu PDF
    private final JCheckBox includeUnmatchedCheck;

    private final JTextArea statusArea;

    public OrnamentMatcherUI() {
        setTitle("Ornament Label–Slip Matcher");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(980, 420);
        setLocationRelativeTo(null);

        labelsField = new JTextField(60);
        slipsField  = new JTextField(60);
        chooseLabelsBtn = new JButton("Pick Labels PDF…");
        chooseSlipsBtn  = new JButton("Pick Packing Slip PDF…");

        generateMatchedBtn = new JButton("Generate Matched PDF");
        generateBySkuBtn   = new JButton("Generate PDFs Grouped by SKU");
        includeUnmatchedCheck = new JCheckBox("Include orders missing one side (label or slip)");

        statusArea = new JTextArea(10, 80);
        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);

        // --- Üst form ---
        JPanel rows = new JPanel(new GridBagLayout());
        rows.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);

        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
        rows.add(new JLabel("Labels PDF:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0;
        rows.add(labelsField, c);
        c.gridx = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        rows.add(chooseLabelsBtn, c);

        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        rows.add(new JLabel("Packing Slip PDF:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0;
        rows.add(slipsField, c);
        c.gridx = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        rows.add(chooseSlipsBtn, c);

        c.gridx = 1; c.gridy = 2; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST;
        rows.add(includeUnmatchedCheck, c);

        // --- Alt butonlar + status ---
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(generateMatchedBtn);
        buttons.add(generateBySkuBtn);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.setBorder(new EmptyBorder(0, 12, 12, 12));
        bottom.add(new JScrollPane(statusArea), BorderLayout.CENTER);
        bottom.add(buttons, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(rows, BorderLayout.NORTH);
        add(bottom, BorderLayout.CENTER);

        // Events
        chooseLabelsBtn.addActionListener(this::pickLabels);
        chooseSlipsBtn.addActionListener(this::pickSlips);
        generateMatchedBtn.addActionListener(this::generateMatched);
        generateBySkuBtn.addActionListener(this::generateBySku);

        // Örnek yollar (değiştirilebilir)
        labelsField.setText("/Users/murattuncel/Desktop/ORN 9.25.25/462ba86c-d51a-4131-b9fd-60572fad0cdb.pdf");
        slipsField.setText("/Users/murattuncel/Desktop/ORN 9.25.25/Amazon.pdf");
    }

    private void pickLabels(ActionEvent e) {
        JFileChooser fc = new JFileChooser(pathOf(labelsField.getText()));
        fc.setDialogTitle("Pick Ornament Labels PDF");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            labelsField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void pickSlips(ActionEvent e) {
        JFileChooser fc = new JFileChooser(pathOf(slipsField.getText()));
        fc.setDialogTitle("Pick Ornament Packing Slip PDF");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            slipsField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private File pathOf(String s) {
        if (s == null || s.isBlank()) return new File(System.getProperty("user.home"));
        File f = new File(s);
        return f.isDirectory() ? f : f.getParentFile();
    }

    // --------------------------------------------------------------------------------
    // 1) Order-by-order TEK PDF oluştur (label sayfaları, ardından slip sayfaları; her biri ayrı sayfa)
    // --------------------------------------------------------------------------------
    private void generateMatched(ActionEvent e) {
        String labelsPath = labelsField.getText().trim();
        String slipsPath  = slipsField.getText().trim();
        if (labelsPath.isEmpty() || slipsPath.isEmpty()) {
            appendStatus("Pick both PDFs first.");
            return;
        }
        File labels = new File(labelsPath);
        File slips  = new File(slipsPath);
        if (!labels.isFile() || !slips.isFile()) {
            appendStatus("Invalid file(s).");
            return;
        }

        setButtonsEnabled(false);
        appendStatus("Indexing & merging (order-by-order)...");

        SwingWorker<File, String> worker = new SwingWorker<>() {
            @Override
            protected File doInBackground() throws Exception {
                publish("Indexing shipping labels...");
                Map<String, List<Integer>> labelMap = PdfLinker.buildOrderIdToPagesMap(labels);
                publish(" -> Found " + labelMap.size() + " orders in labels PDF.");

                publish("Indexing packing slips...");
                Map<String, List<Integer>> slipMap  = PackSlipExtractor.indexOrderToPages(slips);
                publish(" -> Found " + slipMap.size() + " orders in packing slip PDF.");

                Set<String> orderIds = new LinkedHashSet<>();
                orderIds.addAll(labelMap.keySet());
                orderIds.addAll(slipMap.keySet());

                if (!includeUnmatchedCheck.isSelected()) {
                    orderIds = orderIds.stream()
                            .filter(id -> labelMap.containsKey(id) && slipMap.containsKey(id))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                }

                if (orderIds.isEmpty()) {
                    publish("No matched orders found.");
                    return null;
                }
                publish("Found " + orderIds.size() + " total orders to process.");

                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                File outDir = resolveOrdersDirUI(labels);
                File outFile = new File(outDir, "ORN_Matched_" + stamp + ".pdf");

                try (PDDocument labelsDoc = PDDocument.load(labels);
                     PDDocument slipsDoc  = PDDocument.load(slips);
                     PDDocument outDoc    = new PDDocument()) {

                    int count = 0;
                    for (String id : orderIds) {
                        publish("Processing order " + (++count) + "/" + orderIds.size() + ": " + id);

                        // Kargo etiketi sayfalarını kopyala
                        List<Integer> labelPages = labelMap.getOrDefault(id, Collections.emptyList());
                        for (int pageNum : labelPages) {
                            outDoc.addPage(labelsDoc.getPage(pageNum - 1));
                        }

                        // Paket fişi sayfalarını kopyala
                        List<Integer> slipPages  = slipMap.getOrDefault(id, Collections.emptyList());
                        for (int pageNum : slipPages) {
                            outDoc.addPage(slipsDoc.getPage(pageNum - 1));
                        }
                    }
                    outDoc.save(outFile);
                }
                return outFile;
            }

            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) appendStatus(chunks.get(chunks.size()-1));
            }

            @Override protected void done() {
                setButtonsEnabled(true);
                try {
                    File outFile = get();
                    if (outFile != null && outFile.isFile()) {
                        appendStatus("Done: " + outFile.getAbsolutePath());
                        Desktop.getDesktop().open(outFile);
                    } else {
                        appendStatus("Nothing generated.");
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    appendStatus("Error: " + cause.getMessage());
                    JOptionPane.showMessageDialog(
                            OrnamentMatcherUI.this,
                            "An error occurred:\n" + cause.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        };
        worker.execute();
    }
    private File resolveOrdersDirUI(File referencePdf) {
        File base = referencePdf.getParentFile() != null ? referencePdf.getParentFile() : new File(".");
        File orders = new File(base, "Orders");
        if (!orders.exists()) orders.mkdirs();
        return orders;
    }

    // --------------------------------------------------------------------------------
    // 2) SKU'ya göre AYRI PDF'ler oluştur
    // --------------------------------------------------------------------------------
    private void generateBySku(ActionEvent e) {
        String labelsPath = labelsField.getText().trim();
        String slipsPath  = slipsField.getText().trim();
        if (labelsPath.isEmpty() || slipsPath.isEmpty()) {
            appendStatus("Pick both PDFs first.");
            return;
        }
        File labels = new File(labelsPath);
        File slips  = new File(slipsPath);
        if (!labels.isFile() || !slips.isFile()) {
            appendStatus("Invalid file(s).");
            return;
        }

        setButtonsEnabled(false);
        appendStatus("Generating PDFs grouped by SKU...");

        SwingWorker<File, String> worker = new SwingWorker<>() {
            @Override
            protected File doInBackground() throws Exception {
                try {
                    Map<String, File> outputs = com.osman.OrnamentProcessor.generateSkuPdfs(
                            labels, slips, includeUnmatchedCheck.isSelected()
                    );
                    if (outputs.isEmpty()) {
                        publish("No PDFs produced.");
                        return null;
                    }
                    StringBuilder sb = new StringBuilder("Created PDFs:\n");
                    outputs.forEach((k, f) -> sb.append("  ").append(k).append(" -> ").append(f.getAbsolutePath()).append("\n"));
                    publish(sb.toString());
                    // İlk dosyayı döndürelim (UI otomatik açabilsin)
                    return outputs.values().iterator().next();
                } catch (Exception ex) {
                    publish("Error: " + ex.getMessage());
                    return null;
                }
            }

            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) appendStatus(chunks.get(chunks.size()-1));
            }

            @Override protected void done() {
                setButtonsEnabled(true);
                try {
                    File first = get();
                    if (first != null && first.isFile()) {
                        Desktop.getDesktop().open(first);
                    }
                } catch (Exception ignored) {}
            }
        };
        worker.execute();
    }

    private void setButtonsEnabled(boolean enabled) {
        chooseLabelsBtn.setEnabled(enabled);
        chooseSlipsBtn.setEnabled(enabled);
        generateMatchedBtn.setEnabled(enabled);
        generateBySkuBtn.setEnabled(enabled);
        includeUnmatchedCheck.setEnabled(enabled);
    }

    private void appendStatus(String line) {
        statusArea.append(line);
        if (!line.endsWith("\n")) statusArea.append("\n");
        statusArea.setCaretPosition(statusArea.getDocument().getLength());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new OrnamentMatcherUI().setVisible(true));
    }
}
