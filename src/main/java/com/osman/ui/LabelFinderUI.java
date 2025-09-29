package com.osman.ui;

import com.osman.PackSlipExtractor;
import com.osman.PdfLinker;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.print.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Swing UI that helps operators locate shipping labels, packing slips, and photos for Amazon orders.
 * <p>Supports two workflows:</p>
 * <ul>
 *   <li><b>Single order</b>: looks up matching label and slip pages by Order ID.</li>
 *   <li><b>Bulk mode</b>: browses every PDF page and related product photos.</li>
 * </ul>
 * The class coordinates filesystem scanning, PDF rendering, photo previews, and printing.
 */
public class LabelFinderUI extends JFrame {
    /** Holds the document and page number where a label was rendered. */
    private static class LabelLocation {
        final File pdfFile;
        final int pageNumber;
        LabelLocation(File pdfFile, int pageNumber) { this.pdfFile = pdfFile; this.pageNumber = pageNumber; }
    }
    private static class PageGroup {
        final File file;
        final List<Integer> pages;
        PageGroup(File file, List<Integer> pages) { this.file = file; this.pages = pages; }
    }

    private final JTextField orderIdField;
    private final JButton findButton;
    private final JButton printButton;
    private final JButton chooseBaseBtn;
    private final JCheckBox bulkModeCheck;
    private final JLabel statusLabel;

    private final ImagePanel combinedPanel;

    private final DefaultListModel<LabelRef> labelRefsModel;
    private final JList<LabelRef> labelRefsList;
    private final DefaultListModel<Path> photosModel;
    private final JList<Path> photosList;
    private final DualImagePanel photoView;

    private File baseDir;

    private Map<String, PageGroup> labelGroups;
    private Map<String, PageGroup> slipGroups;

    private LabelLocation currentLabelLocation;
    private BufferedImage combinedPreview;

    private java.util.List<BufferedImage> labelPagesToPrint;
    private java.util.List<BufferedImage> slipPagesToPrint;

    public LabelFinderUI() {
        setTitle("Label & Photo Viewer");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1600, 900);
        setLocationRelativeTo(null);

        orderIdField = new JTextField(20);
        findButton = new JButton("Find");
        printButton = new JButton("Print Combined");
        printButton.setEnabled(false);
        chooseBaseBtn = new JButton("Choose File Path");
        bulkModeCheck = new JCheckBox("Bulk Order (no matching)");

        JPanel top = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        top.setBorder(new EmptyBorder(6, 6, 6, 6));
        top.add(new JLabel("Order ID:"));
        top.add(orderIdField);
        top.add(findButton);
        top.add(printButton);
        top.add(chooseBaseBtn);
        top.add(bulkModeCheck);

        labelRefsModel = new DefaultListModel<>();
        labelRefsList = new JList<>(labelRefsModel);
        labelRefsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        labelRefsList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                LabelRef ref = (LabelRef) value;
                l.setText(ref.toDisplayString());
                l.setToolTipText(ref.file.getAbsolutePath());
                return l;
            }
        });
        JScrollPane labelsScroll = new JScrollPane(labelRefsList);
        labelsScroll.setBorder(BorderFactory.createTitledBorder("PDF Pages (Bulk)"));

        photosModel = new DefaultListModel<>();
        photosList = new JList<>(photosModel);
        photosList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        photosList.setVisibleRowCount(8);
        photosList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                Path p = (Path) value;
                l.setText(p.getFileName().toString());
                l.setToolTipText(p.toAbsolutePath().toString());
                return l;
            }
        });
        JScrollPane photosScroll = new JScrollPane(photosList);
        photosScroll.setBorder(BorderFactory.createTitledBorder("Photos (select 1–2)"));

        JPanel listsPanel = new JPanel(new GridLayout(1, 2, 8, 8));
        listsPanel.add(labelsScroll);
        listsPanel.add(photosScroll);

        combinedPanel = new ImagePanel();
        JScrollPane combinedScroll = new JScrollPane(combinedPanel);
        combinedScroll.setBorder(BorderFactory.createTitledBorder("Shipping Label & Slip"));

        JPanel leftPanel = new JPanel(new BorderLayout(8, 8));
        leftPanel.add(listsPanel, BorderLayout.NORTH);
        leftPanel.add(combinedScroll, BorderLayout.CENTER);

        photoView = new DualImagePanel();
        JScrollPane photoViewScroll = new JScrollPane(photoView);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, photoViewScroll);
        mainSplit.setResizeWeight(0.45);

        statusLabel = new JLabel("Scan barcode → prints automatically. Choose base folder first.");

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        chooseBaseBtn.addActionListener(e -> chooseBaseFolder());
        findButton.addActionListener(e -> onFind());
        printButton.addActionListener(e -> printCombined());
        orderIdField.addActionListener(e -> onFind());
        bulkModeCheck.addItemListener(e -> onModeChanged(e.getStateChange() == ItemEvent.SELECTED));
        labelRefsList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) renderSelectedBulkPage(); });
        photosList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) renderSelectedPhotos(); });

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { cleanupAndExit(); }
            @Override public void windowOpened(WindowEvent e) { orderIdField.requestFocusInWindow(); }
            @Override public void windowActivated(WindowEvent e) { orderIdField.requestFocusInWindow(); }
        });

        orderIdField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                SwingUtilities.invokeLater(orderIdField::selectAll);
            }
        });

        addPrintShortcut(getRootPane());
        applyModeUI();
    }

    private void onModeChanged(boolean bulk) {
        applyModeUI();
        clearAllViews();
        if (baseDir != null && baseDir.isDirectory()) {
            if (bulk) populateBulkFromBase();
            else buildLabelAndSlipIndices();
        }
    }

    private void applyModeUI() {
        boolean bulk = bulkModeCheck.isSelected();
        orderIdField.setEnabled(!bulk);
        findButton.setEnabled(true);
        labelRefsList.setEnabled(bulk);
    }

    private void clearAllViews() {
        combinedPreview = null;
        combinedPanel.setImage(null);
        photoView.setImages(null, null);
        labelRefsModel.clear();
        photosModel.clear();
        currentLabelLocation = null;
        printButton.setEnabled(false);
        labelPagesToPrint = new ArrayList<>();
        slipPagesToPrint  = new ArrayList<>();
    }

    private void chooseBaseFolder() {
        JFileChooser chooser = new JFileChooser(baseDir);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose Base Folder (e.g., 'Orders')");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File selectedDir = chooser.getSelectedFile();
        if (selectedDir == null || !selectedDir.isDirectory()) return;

        baseDir = selectedDir;
        clearAllViews();

        setUIEnabled(false);
        statusLabel.setText("Scanning folder...");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override protected Void doInBackground() {
                if (bulkModeCheck.isSelected()) populateBulkFromBase();
                else buildLabelAndSlipIndices();
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    if (bulkModeCheck.isSelected()) {
                        statusLabel.setText("BULK loaded: " + labelRefsModel.size() + " pages, " + photosModel.size() + " photos.");
                    } else {
                        statusLabel.setText("Indexed " + labelGroups.size() + " labels, " + slipGroups.size() + " packing slips.");
                    }
                } catch (Exception e) {
                    statusLabel.setText("Error: " + e.getCause().getMessage());
                    JOptionPane.showMessageDialog(LabelFinderUI.this,
                            "Error while scanning:\n" + e.getCause().getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    setUIEnabled(true);
                    orderIdField.requestFocusInWindow();
                    orderIdField.selectAll();
                }
            }
        };
        worker.execute();
    }

    private void setUIEnabled(boolean enabled) {
        findButton.setEnabled(enabled);
        chooseBaseBtn.setEnabled(enabled);
        orderIdField.setEnabled(enabled && !bulkModeCheck.isSelected());
        bulkModeCheck.setEnabled(enabled);
        labelRefsList.setEnabled(enabled);
        photosList.setEnabled(enabled);
    }

    private void buildLabelAndSlipIndices() {
        labelGroups = new HashMap<>();
        slipGroups  = new HashMap<>();
        if (baseDir == null || !baseDir.isDirectory()) {
            statusLabel.setText("Base folder invalid.");
            return;
        }

        List<File> pdfs = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(baseDir.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .forEach(p -> pdfs.add(p.toFile()));
        } catch (IOException e) {
            statusLabel.setText("Error reading PDFs: " + e.getMessage());
            return;
        }

        Pattern packingSlipName = Pattern.compile("(?i)^Amazon\\.pdf$");
        Pattern packingSlipFolder = Pattern.compile("(?i)^(?:\\d{2}\\s*[PRWB]|mix).*");

        for (File pdf : pdfs) {
            String name = pdf.getName();
            File parent = pdf.getParentFile();
            String parentName = parent != null ? parent.getName() : "";
            boolean looksLikePackingSlip =
                    packingSlipName.matcher(name).matches() ||
                            (name.equalsIgnoreCase("Amazon.pdf") && packingSlipFolder.matcher(parentName).matches());

            if (looksLikePackingSlip) {
                try {
                    Map<String, List<Integer>> m = PackSlipExtractor.indexOrderToPages(pdf);
                    for (Map.Entry<String, List<Integer>> e : m.entrySet()) {
                        slipGroups.put(e.getKey(), new PageGroup(pdf, new ArrayList<>(e.getValue())));
                    }
                } catch (IOException ignored) {}
            } else {
                try {
                    Map<String, List<Integer>> m = PdfLinker.buildOrderIdToPagesMap(pdf);
                    for (Map.Entry<String, List<Integer>> e : m.entrySet()) {
                        labelGroups.put(e.getKey(), new PageGroup(pdf, new ArrayList<>(e.getValue())));
                    }
                } catch (IOException ignored) {}
            }
        }

        statusLabel.setText("Indexed " + labelGroups.size() + " labels, " + slipGroups.size() + " packing slips.");
    }

    private void onFind() {
        if (bulkModeCheck.isSelected()) {
            populateBulkFromBase();
            return;
        }
        findSingleOrderFlow();
    }

    private void findSingleOrderFlow() {
        String orderId = orderIdField.getText().trim();
        if (orderId.isEmpty()) {
            statusLabel.setText("Please enter an Order ID.");
            orderIdField.requestFocusInWindow();
            return;
        }
        combinedPreview = null;
        combinedPanel.setImage(null);
        printButton.setEnabled(false);

        BufferedImage labelImg = null;
        BufferedImage slipImg  = null;

        labelPagesToPrint = new ArrayList<>();
        slipPagesToPrint  = new ArrayList<>();

        PageGroup lg = (labelGroups != null) ? labelGroups.get(orderId) : null;
        if (lg != null) {
            java.util.List<BufferedImage> labelPages = renderPdfPagesList(lg.file, lg.pages, 150);
            labelPagesToPrint.addAll(labelPages);
            labelImg = stackMany(withBorder(labelPages, Color.RED, 8), 12, Color.WHITE);
            currentLabelLocation = new LabelLocation(lg.file, lg.pages.get(0));
        } else {
            statusLabel.setText("Shipping label not found for: " + orderId);
        }

        PageGroup sg = (slipGroups != null) ? slipGroups.get(orderId) : null;
        if (sg != null) {
            java.util.List<BufferedImage> slipPages = renderPdfPagesList(sg.file, sg.pages, 150);
            slipPagesToPrint.addAll(slipPages);
            slipImg = stackMany(withBorder(slipPages, new Color(0,120,215), 8), 12, Color.WHITE);
        } else if (lg != null) {
            statusLabel.setText("Packing slip not found for: " + orderId);
        }

        combinedPreview = stackImagesVertically(labelImg, slipImg, 12, Color.WHITE);
        if (combinedPreview != null) {
            combinedPanel.setImage(combinedPreview);
            printButton.setEnabled(true);
        }

        photosModel.clear();
        photoView.setImages(null, null);
        if (baseDir != null && baseDir.isDirectory()) {
            try {
                findAllMatchingPhotos(baseDir.toPath(), orderId).forEach(photosModel::addElement);
            } catch (IOException ignored) {}
        }
        selectAndPreviewFirstPhotos();

        try {
            if (baseDir != null && baseDir.isDirectory()) {
                List<Path> designs = findReadyDesignPhotos(baseDir.toPath(), orderId);
                if (!designs.isEmpty()) {
                    int ok = 0;
                    for (Path p : designs) {
                        if (addUserTag(p, "Green")) ok++;   // macOS İngilizce
                    }
                    if (ok > 0) {
                        statusLabel.setText("Ready designs tagged: Green (" + ok + "/" + designs.size() + ").");
                    } else {
                        statusLabel.setText("Tried to tag " + designs.size() + " item(s) Green, none confirmed. Check permissions.");
                    }
                } else {
                    statusLabel.setText("No ready design found to tag for: " + orderId);
                }
            }
        } catch (IOException ioex) {
            statusLabel.setText("Tagging skipped (IO error).");
        }
        if (!labelPagesToPrint.isEmpty() || !slipPagesToPrint.isEmpty()) {
            printCombinedDirect();
            orderIdField.setText("");
            orderIdField.requestFocusInWindow();
        } else {
            orderIdField.requestFocusInWindow();
            orderIdField.selectAll();
        }

        if (photosModel.isEmpty()) {
            if (currentLabelLocation != null) statusLabel.setText("Printed. No matching photo found for: " + orderId);
        } else if (photosModel.size() == 1) {
            statusLabel.setText("Printed. Auto-selected 1 photo for preview.");
        } else {
            statusLabel.setText("Printed. Auto-selected first 2 photos for preview.");
        }
    }

    private java.util.List<BufferedImage> renderPdfPagesList(File pdf, java.util.List<Integer> pages1Based, int dpi) {
        java.util.List<BufferedImage> out = new ArrayList<>();
        if (pdf == null || pages1Based == null || pages1Based.isEmpty()) return out;
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int p : pages1Based) {
                BufferedImage img = renderer.renderImageWithDPI(p - 1, dpi);
                out.add(img);
            }
        } catch (IOException ignored) {}
        return out;
    }

    private static BufferedImage stackMany(java.util.List<BufferedImage> images, int gap, Color bg) {
        if (images == null || images.isEmpty()) return null;
        int wMax = 0, totalH = 0, count = 0;
        for (BufferedImage im : images) {
            if (im == null) continue;
            wMax = Math.max(wMax, im.getWidth());
            totalH += im.getHeight();
            count++;
        }
        if (wMax == 0 || totalH == 0) return null;
        totalH += gap * Math.max(0, count - 1);

        BufferedImage out = new BufferedImage(wMax, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, wMax, totalH);

        int y = 0;
        int idx = 0;
        for (BufferedImage im : images) {
            if (im == null) continue;
            g.drawImage(im, 0, y, null);
            y += im.getHeight();
            if (idx < count - 1) {
                g.setColor(bg);
                g.fillRect(0, y, wMax, gap);
                y += gap;
            }
            idx++;
        }
        g.dispose();
        return out;
    }

    private static java.util.List<BufferedImage> withBorder(java.util.List<BufferedImage> images, Color color, int size) {
        java.util.List<BufferedImage> out = new ArrayList<>();
        if (images == null) return out;
        for (BufferedImage im : images) { out.add(addBorder(im, color, size)); }
        return out;
    }

    private BufferedImage renderPdfPagesMerged(File pdf, List<Integer> pages1Based, int dpi) {
        if (pages1Based == null || pages1Based.isEmpty()) return null;
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            List<BufferedImage> imgs = new ArrayList<>();
            int wMax = 0, totalH = 0;
            for (int p : pages1Based) {
                BufferedImage img = renderer.renderImageWithDPI(p - 1, dpi);
                imgs.add(img);
                wMax = Math.max(wMax, img.getWidth());
                totalH += img.getHeight();
            }
            BufferedImage merged = new BufferedImage(wMax, totalH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = merged.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, wMax, totalH);
            int y = 0;
            for (BufferedImage img : imgs) {
                g.drawImage(img, 0, y, null);
                y += img.getHeight();
            }
            g.dispose();
            return merged;
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage stackImagesVertically(BufferedImage top, BufferedImage bottom, int gap, Color bg) {
        if (top == null && bottom == null) return null;
        if (top == null) return bottom;
        if (bottom == null) return top;
        int w = Math.max(top.getWidth(), bottom.getWidth());
        int h = top.getHeight() + gap + bottom.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, w, h);
        g.drawImage(top, 0, 0, null);
        g.drawImage(bottom, 0, top.getHeight() + gap, null);
        g.dispose();
        return out;
    }

    private void printCombined() {
        java.util.List<BufferedImage> pages = new ArrayList<>();
        if (labelPagesToPrint != null) pages.addAll(labelPagesToPrint);
        if (slipPagesToPrint  != null) pages.addAll(slipPagesToPrint);
        if (pages.isEmpty()) {
            if (combinedPreview == null) { statusLabel.setText("Nothing to print."); return; }
            pages.add(combinedPreview);
        }

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Shipping Label & Slip (4x6)");
        BufferedImage first = pages.get(0);
        boolean landscape = first.getWidth() > first.getHeight();
        PageFormat pf = create4x6PageFormat(job, landscape);
        job.setPrintable(new MultiPagePrintable(pages), pf);

        if (job.printDialog()) {
            try {
                job.print();
                statusLabel.setText("Print job sent.");
            } catch (PrinterException e) {
                JOptionPane.showMessageDialog(this, "Could not print.\nError: " + e.getMessage(),
                        "Print Error", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            statusLabel.setText("Print job canceled.");
        }
    }

    private boolean printCombinedDirect() {
        java.util.List<BufferedImage> pages = new ArrayList<>();
        if (labelPagesToPrint != null) pages.addAll(labelPagesToPrint);
        if (slipPagesToPrint  != null) pages.addAll(slipPagesToPrint);
        if (pages.isEmpty()) {
            if (combinedPreview == null) { statusLabel.setText("Nothing to print."); return false; }
            pages.add(combinedPreview);
        }

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Shipping Label & Slip (4x6) - Direct");
        BufferedImage first = pages.get(0);
        boolean landscape = first.getWidth() > first.getHeight();
        PageFormat pf = create4x6PageFormat(job, landscape);
        job.setPrintable(new MultiPagePrintable(pages), pf);

        try {
            job.print();
            statusLabel.setText("Printed (direct).");
            return true;
        } catch (PrinterException e) {
            JOptionPane.showMessageDialog(this, "Direct print failed.\n" + e.getMessage(),
                    "Print Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void cleanupAndExit() {
        dispose();
        System.exit(0);
    }

    private void populateBulkFromBase() {
        if (baseDir == null || !baseDir.isDirectory()) {
            statusLabel.setText("Select a valid base folder for BULK.");
            return;
        }
        labelRefsModel.clear();
        try (Stream<Path> stream = Files.walk(baseDir.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(pdfPath -> {
                        try (PDDocument doc = PDDocument.load(pdfPath.toFile())) {
                            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                                labelRefsModel.addElement(new LabelRef(pdfPath.toFile(), p));
                            }
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            statusLabel.setText("Error reading bulk PDFs: " + e.getMessage());
        }

        photosModel.clear();
        try (Stream<Path> stream = Files.walk(baseDir.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(photosModel::addElement);
        } catch (IOException e){
            statusLabel.setText("Error reading bulk photos: " + e.getMessage());
        }

        if (!labelRefsModel.isEmpty()) labelRefsList.setSelectedIndex(0);
        if (!photosModel.isEmpty()) photosList.setSelectedIndex(0);

        statusLabel.setText("BULK loaded: " + labelRefsModel.size() + " pages, " + photosModel.size() + " photos.");
    }

    private void renderSelectedBulkPage() {
        LabelRef ref = labelRefsList.getSelectedValue();
        if (ref == null) {
            combinedPreview = null;
            combinedPanel.setImage(null);
            currentLabelLocation = null;
            printButton.setEnabled(false);
            labelPagesToPrint = new ArrayList<>();
            slipPagesToPrint  = new ArrayList<>();
            return;
        }
        currentLabelLocation = new LabelLocation(ref.file, ref.page1Based);
        try (PDDocument doc = PDDocument.load(ref.file)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage img = renderer.renderImageWithDPI(ref.page1Based - 1, 150);
            combinedPreview = addBorder(img, Color.DARK_GRAY, 8);
            combinedPanel.setImage(combinedPreview);
            printButton.setEnabled(true);
            labelPagesToPrint = new ArrayList<>();
            slipPagesToPrint  = new ArrayList<>();
            labelPagesToPrint.add(img);
        } catch (IOException e) {
            combinedPreview = null;
            combinedPanel.setImage(null);
            currentLabelLocation = null;
            printButton.setEnabled(false);
            labelPagesToPrint = new ArrayList<>();
            slipPagesToPrint  = new ArrayList<>();
        }
    }

    private void renderSelectedPhotos() {
        List<Path> selected = photosList.getSelectedValuesList();
        if (selected == null || selected.isEmpty()) {
            photoView.setImages(null, null);
            return;
        }
        try {
            if (selected.size() == 1) {
                BufferedImage a = ImageIO.read(selected.get(0).toFile());
                photoView.setImages(a, null);
            } else {
                BufferedImage a = ImageIO.read(selected.get(0).toFile());
                BufferedImage b = ImageIO.read(selected.get(1).toFile());
                photoView.setImages(a, b);
            }
            if (selected.size() > 2) {
                statusLabel.setText("Only the first 2 selected photos are shown.");
            }
        } catch (IOException ex) {
            photoView.setImages(null, null);
        }
    }

    private static List<Path> findAllMatchingPhotos(Path root, String orderId) throws IOException {
        final String needle = orderId.toLowerCase(Locale.ROOT);
        List<Path> results = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root, 10)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean isImage = n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                if (isImage && n.contains(needle)) results.add(p);
            });
        }
        results.sort(Comparator.comparing(a -> a.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return results;
    }

    private void addPrintShortcut(JRootPane rootPane) {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_P, mask);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, "printCombinedAction");
        rootPane.getActionMap().put("printCombinedAction", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { printCombined(); }
        });
    }

    private static BufferedImage addBorder(BufferedImage src, Color color, int size) {
        if (src == null) return null;
        int w = src.getWidth() + size * 2;
        int h = src.getHeight() + size * 2;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(color);
        g.fillRect(0, 0, w, size);
        g.fillRect(0, h - size, w, size);
        g.fillRect(0, 0, size, h);
        g.fillRect(w - size, 0, size, h);
        g.drawImage(src, size, size, null);
        g.dispose();
        return out;
    }

    private static class ImagePanel extends JPanel {
        private BufferedImage image;
        public void setImage(BufferedImage img) { this.image = img; revalidate(); repaint(); }
        @Override public Dimension getPreferredSize() { return image == null ? new Dimension(900, 1200) : new Dimension(image.getWidth(), image.getHeight()); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image != null) {
                int x = Math.max((getWidth() - image.getWidth()) / 2, 0);
                int y = Math.max((getHeight() - image.getHeight()) / 2, 0);
                ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(image, x, y, this);
            }
        }
    }

    private static class DualImagePanel extends JPanel {
        private BufferedImage imgA;
        private BufferedImage imgB;
        public void setImages(BufferedImage a, BufferedImage b) { this.imgA = a; this.imgB = b; revalidate(); repaint(); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (imgA == null && imgB == null) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int panelWidth = getWidth();
            int panelHeight = getHeight();
            if (imgB == null) {
                drawFitted(g2, imgA, 0, 0, panelWidth, panelHeight);
            } else {
                int wL = panelWidth / 2;
                int wR = panelWidth - wL;
                drawFitted(g2, imgA, 0, 0, wL, panelHeight);
                drawFitted(g2, imgB, wL, 0, wR, panelHeight);
                g2.setColor(new Color(220, 220, 220));
                g2.fillRect(wL - 1, 0, 2, panelHeight);
            }
        }
        private void drawFitted(Graphics2D g2, BufferedImage img, int x, int y, int w, int h) {
            if (img == null) return;
            double scale = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
            int dw = (int) (img.getWidth() * scale);
            int dh = (int) (img.getHeight() * scale);
            int dx = x + (w - dw) / 2;
            int dy = y + (h - dh) / 2;
            g2.drawImage(img, dx, dy, dw, dh, null);
        }
    }

    private static class LabelRef {
        final File file;
        final int page1Based;
        LabelRef(File file, int page1Based) { this.file = file; this.page1Based = page1Based; }
        String toDisplayString() { return file.getName() + "  —  p." + page1Based; }
        @Override public String toString() { return toDisplayString(); }
    }

    private static class MultiPagePrintable implements Printable {
        private final java.util.List<BufferedImage> pages;
        MultiPagePrintable(java.util.List<BufferedImage> pages) {
            this.pages = pages == null ? java.util.Collections.emptyList() : pages;
        }
        @Override
        public int print(Graphics g, PageFormat pf, int pageIndex) throws PrinterException {
            if (pageIndex < 0 || pageIndex >= pages.size()) return NO_SUCH_PAGE;
            BufferedImage img = pages.get(pageIndex);
            if (img == null) return NO_SUCH_PAGE;

            Graphics2D g2d = (Graphics2D) g;
            g2d.translate(pf.getImageableX(), pf.getImageableY());

            double pw = pf.getImageableWidth();
            double ph = pf.getImageableHeight();

            double scale = Math.min(pw / img.getWidth(), ph / img.getHeight());
            int dw = (int) Math.floor(img.getWidth() * scale);
            int dh = (int) Math.floor(img.getHeight() * scale);
            int dx = (int) ((pw - dw) / 2.0);
            int dy = (int) ((ph - dh) / 2.0);

            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.drawImage(img, dx, dy, dw, dh, null);
            return PAGE_EXISTS;
        }
    }

    private static PageFormat create4x6PageFormat(PrinterJob job, boolean landscape) {
        PageFormat pf = job.defaultPage();
        Paper paper = new Paper();
        double inch = 72.0;
        double w = 4 * inch;
        double h = 6 * inch;
        if (landscape) { double t = w; w = h; h = t; }
        double topMargin = h * 0.10;
        double sideMargin = 0.10 * inch;

        paper.setSize(w, h);
        paper.setImageableArea(
                sideMargin,
                topMargin,
                w - 2 * sideMargin,
                h - topMargin - sideMargin
        );
        pf.setPaper(paper);
        pf.setOrientation(landscape ? PageFormat.LANDSCAPE : PageFormat.PORTRAIT);
        return pf;
    }

    private void selectAndPreviewFirstPhotos() {
        if (photosModel == null || photosModel.isEmpty()) {
            photoView.setImages(null, null);
            return;
        }
        int last = Math.min(1, photosModel.size() - 1);
        photosList.clearSelection();
        photosList.setSelectionInterval(0, last);
        renderSelectedPhotos();
    }

    private enum LabelColor {
        NONE(0), GRAY(1), GREEN(2), PURPLE(3), BLUE(4), YELLOW(5), RED(6), ORANGE(7);
        final int idx; LabelColor(int i){ this.idx = i; }
    }
    private static boolean verifyFinderTag(Path path, int expectedIdx) {
        try {
            Process p = new ProcessBuilder(
                    "/usr/bin/mdls", "-raw", "-name", "kMDItemFSLabel", path.toAbsolutePath().toString()
            ).start();
            int exit = p.waitFor();
            if (exit != 0) return false;
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String s = br.readLine();
                if (s == null) return false;
                s = s.trim();
                // bazen (null) gelebilir
                if ("(null)".equalsIgnoreCase(s)) return expectedIdx == 0;
                int val = Integer.parseInt(s);
                return val == expectedIdx;
            }
        } catch (Exception e) {
            return false;
        }
    }
    private static void runAppleScript(String script) throws Exception {
        Process p = new ProcessBuilder("/usr/bin/osascript", "-e", script).start();
        int exit = p.waitFor();
        if (exit != 0) throw new RuntimeException("AppleScript failed. Exit=" + exit);
    }

    private static boolean setFinderTag(Path path, LabelColor color) {
        try {
            String f = path.toAbsolutePath().toString().replace("\"","\\\"");
            String script =
                    "tell application \"Finder\"\n" +
                            "  set t to POSIX file \"" + f + "\" as alias\n" +
                            "  set label index of t to 0\n" +         // önce temizle
                            "  set label index of t to " + color.idx + "\n" + // sonra hedefe ayarla
                            "end tell";
            runAppleScript(script);
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            return verifyFinderTag(path, color.idx);
        } catch (Exception e) {
            return false;
        }
    }
    private static boolean addUserTag(Path path, String tagName) {
        try {
            String f = path.toAbsolutePath().toString().replace("\"","\\\"");
            String tn = tagName.replace("\"","\\\"");
            String script =
                    "set thePath to POSIX file \"" + f + "\"\n" +
                            "tell application \"Finder\"\n" +
                            "  set t to thePath as alias\n" +
                            "  try\n" +
                            "    set cur to tag names of t\n" +
                            "  on error\n" +
                            "    set cur to {}\n" +
                            "  end try\n" +
                            "  if cur does not contain \"" + tn + "\" then set tag names of t to (cur & {\"" + tn + "\"})\n" +
                            "end tell";
            Process p = new ProcessBuilder("/usr/bin/osascript", "-e", script).start();
            if (p.waitFor() != 0) return false;
            try { Thread.sleep(120); } catch (InterruptedException ignored) {}
            Process v = new ProcessBuilder("/usr/bin/mdls","-raw","-name","kMDItemUserTags", f).start();
            if (v.waitFor() != 0) return false;
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(v.getInputStream()))) {
                String s = br.readLine();
                return s != null && s.contains(tagName);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static List<Path> findReadyDesignPhotos(Path root, String orderId) throws IOException {
        final String needle = "(" + orderId.toLowerCase(Locale.ROOT) + ")";
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root, 12)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean isImage = n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                if (isImage && n.startsWith("x") && n.contains(needle)) out.add(p);
            });
        }
        out.sort(Comparator.comparing(a -> a.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LabelFinderUI().setVisible(true));
    }
}
