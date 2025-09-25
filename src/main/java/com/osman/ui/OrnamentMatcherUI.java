package com.osman.ui;

import com.osman.PackSlipExtractor;
import com.osman.PdfLinker;
import org.apache.pdfbox.pdmodel.PDDocument;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class OrnamentMatcherUI extends JFrame {

    // --- UI Bileşenleri ---
    private final JTextField labelsField;
    private final JTextField slipsField;
    private final JButton chooseLabelsBtn;
    private final JButton chooseSlipsBtn;
    private final JButton generateBtn;
    private final JCheckBox includeUnmatchedCheck;
    private final JLabel status;

    public OrnamentMatcherUI() {
        setTitle("Ornament Label–Slip Matcher (Page-by-Page PDF)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 240);
        setLocationRelativeTo(null);

        // --- UI Kurulumu (Değişiklik yok) ---
        labelsField = new JTextField(60);
        slipsField  = new JTextField(60);
        chooseLabelsBtn = new JButton("Pick Labels PDF…");
        chooseSlipsBtn  = new JButton("Pick Packing Slip PDF…");
        generateBtn     = new JButton("Generate Matched PDF");
        includeUnmatchedCheck = new JCheckBox("Include orders missing one side (label or slip)");
        status = new JLabel("Select PDFs and click Generate.");

        JPanel rows = new JPanel(new GridBagLayout());
        rows.setBorder(new EmptyBorder(12,12,12,12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6,6,6,6);
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

        JPanel bottom = new JPanel(new BorderLayout(8,8));
        bottom.setBorder(new EmptyBorder(0,12,12,12));
        bottom.add(status, BorderLayout.WEST);
        bottom.add(generateBtn, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(rows, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        chooseLabelsBtn.addActionListener(this::pickLabels);
        chooseSlipsBtn.addActionListener(this::pickSlips);
        generateBtn.addActionListener(this::generate);

        labelsField.setText("/Users/murattuncel/Desktop/ORN 9.25.25/462ba86c-d51a-4131-b9fd-60572fad0cdb.pdf");
        slipsField.setText("/Users/murattuncel/Desktop/ORN 9.25.25/Amazon.pdf");
    }

    // --- UI Metodları (Değişiklik yok) ---
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

    // --- Ana İşlem Metodu ---
    private void generate(ActionEvent e) {
        String labelsPath = labelsField.getText().trim();
        String slipsPath  = slipsField.getText().trim();
        if (labelsPath.isEmpty() || slipsPath.isEmpty()) {
            status.setText("Pick both PDFs first.");
            return;
        }
        File labels = new File(labelsPath);
        File slips  = new File(slipsPath);
        if (!labels.isFile() || !slips.isFile()) {
            status.setText("Invalid file(s).");
            return;
        }
        generateBtn.setEnabled(false);
        status.setText("Indexing & merging...");

        SwingWorker<File, String> worker = new SwingWorker<>() {
            @Override
            protected File doInBackground() throws Exception {
                // Adım 1: PDF'leri tara ve haritaları oluştur
                publish("Indexing shipping labels...");
                Map<String, List<Integer>> labelMap = PdfLinker.buildOrderIdToPagesMap(labels);
                publish(" -> Found " + labelMap.size() + " orders in labels PDF.");

                publish("Indexing packing slips...");
                Map<String, List<Integer>> slipMap  = PackSlipExtractor.indexOrderToPages(slips);
                publish(" -> Found " + slipMap.size() + " orders in packing slip PDF.");

                // Adım 2: İşlenecek sipariş ID'lerini belirle
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

                // Adım 3: Çıktı dosyasını hazırla
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                File outDir = labels.getParentFile() != null ? labels.getParentFile() : new File(".");
                File outFile = new File(outDir, "ORN_Matched_" + stamp + ".pdf");

                // Adım 4: Sayfaları Kopyala
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
                if (!chunks.isEmpty()) status.setText(chunks.get(chunks.size()-1));
            }

            @Override protected void done() {
                generateBtn.setEnabled(true);
                try {
                    File outFile = get();
                    if (outFile != null && outFile.isFile()) {
                        status.setText("Done: " + outFile.getAbsolutePath());
                        Desktop.getDesktop().open(outFile);
                    } else {
                        status.setText("Nothing generated.");
                    }
                } catch (Exception ex) {
                    // SwingWorker'dan gelen exception'ların asıl nedenini almak için getCause() kullanılır
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText("Error: " + cause.getMessage());
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new OrnamentMatcherUI().setVisible(true));
    }
}