package com.osman.ui.labelfinder;

import com.osman.PackSlipExtractor;
import com.osman.PdfLinker;
import com.osman.config.PreferencesStore;
import com.osman.logging.AppLogger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.json.JSONException;
import org.json.JSONObject;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * Swing UI for locating shipping labels, packing slips, and matching order photos across one or more base folders.
 * <p>
 * Key capabilities:
 * <ul>
 *   <li>Interactive lookup by order ID with bulk/auto mode toggles.</li>
 *   <li>Workspace management (remembered base directories, window bounds, split positions).</li>
 *   <li>Combined label + slip previews, photo gallery, and single-click printing via {@link #printCombined()}.</li>
 *   <li>Drag-and-drop support for quickly loading PDFs or photo directories straight into the UI.</li>
 *   <li>Background scanning and indexing with status updates so large datasets remain responsive.</li>
 * </ul>
 * The class is intentionally self-contained so it can be launched both stand-alone and from other desktop flows.
 */
public class LabelFinderUI extends JFrame {
    private static final Logger LOGGER = AppLogger.get();
    private static class LabelLocation {
        final File pdfFile;
        final int pageNumber;
        LabelLocation(File pdfFile, int pageNumber) {
            this.pdfFile = pdfFile;
            this.pageNumber = pageNumber;
        }
    }
    private static class PageGroup {
        final File file;
        final List<Integer> pages;
        PageGroup(File file, List<Integer> pages) {
            this.file = file;
            this.pages = pages;
        }
    }
    private final JTextField orderIdField;
    private final JButton findButton;
    private final JButton printButton;
    private final JButton chooseBaseBtn;
    private final JCheckBox bulkModeCheck;
    private final JLabel statusLabel;
    private final JLabel topStatusLabel;
    private final ImagePanel combinedPanel;
    private final DefaultListModel<LabelRef> labelRefsModel;
    private final JList<LabelRef> labelRefsList;
    private final DefaultListModel<Path> photosModel;
    private final JList<Path> photosList;
    private final DualImagePanel photoView;
    private final Map<String, OrderExpectation> expectationIndex;
    private final Map<String, OrderScanState> scanProgress;
    private final List<File> baseFolders;
    private final Set<String> photoRefreshInFlight;
    private final Object photoIndexLock = new Object();
    private volatile String activeOrderId;
    private List<PhotoIndexEntry> photoIndex;
    private Set<Path> photoIndexPaths;
    private File baseDir;
    private Map<String, PageGroup> labelGroups;
    private Map<String, PageGroup> slipGroups;
    private LabelLocation currentLabelLocation;
    private BufferedImage combinedPreview;
    private List<BufferedImage> labelPagesToPrint;
    private List<BufferedImage> slipPagesToPrint;
    private static final Pattern IMG_NAME = Pattern.compile("(?i).+\\s*(?:\\(\\d+\\))?\\s*\\.(png|jpe?g)$");
    private static final Pattern XN_READY_NAME = Pattern.compile("(?i)^x(?:\\(\\d+\\)|\\d+)-.+\\s*(?:\\(\\d+\\))?\\s*\\.(?:png|jpe?g)$");

    private static final String PREF_BULK_MODE        = "bulkMode";         // "true"/"false"
    private static final String PREF_WIN_BOUNDS       = "winBounds";        // x,y,w,h
    private static final String PREF_DIVIDER_LOCATION = "dividerLocation";  // JSplitPane divider
    private final PreferencesStore prefs = PreferencesStore.global();
    private JSplitPane mainSplit;
    private static final int MAX_SCAN_HISTORY = 100;
    private final Deque<String> scanHistory = new ArrayDeque<>();
    private final Map<String, CompletedOrderInfo> completedOrders = new LinkedHashMap<>();

    /**
     * Constructs the UI, wires listeners, and prepares the initial application state.
     */
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
        JPanel controlsRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        controlsRow.setBorder(new EmptyBorder(6, 6, 6, 6));
        controlsRow.add(new JLabel("Order ID:"));
        controlsRow.add(orderIdField);
        controlsRow.add(findButton);
        controlsRow.add(printButton);
        controlsRow.add(chooseBaseBtn);
        controlsRow.add(bulkModeCheck);

        String initialStatus = "Scan barcode → prints automatically. Choose base folder first.";
        topStatusLabel = new JLabel("Scanned 0/0");
        topStatusLabel.setFont(topStatusLabel.getFont().deriveFont(Font.BOLD, 18f));
        topStatusLabel.setForeground(Color.RED);
        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        statusRow.setBorder(new EmptyBorder(6, 6, 6, 6));
        statusRow.add(topStatusLabel);

        JPanel top = new JPanel(new BorderLayout());
        top.add(statusRow, BorderLayout.WEST);
        top.add(controlsRow, BorderLayout.CENTER);
        labelRefsModel = new DefaultListModel<>();
        labelRefsList = new JList<>(labelRefsModel);
        JScrollPane labelsScroll = new JScrollPane(labelRefsList);
        labelsScroll.setBorder(BorderFactory.createTitledBorder("PDF Pages (Bulk)"));
        photosModel = new DefaultListModel<>();
        photosList = new JList<>(photosModel);
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
        expectationIndex = new ConcurrentHashMap<>();
        scanProgress = new ConcurrentHashMap<>();
        baseFolders = new ArrayList<>();
        photoRefreshInFlight = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        activeOrderId = null;
        photoIndex = new ArrayList<>();
        photoIndexPaths = new LinkedHashSet<>();
        JScrollPane photoViewScroll = new JScrollPane(photoView);
        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, photoViewScroll);

        mainSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> {
            prefs.putString(PREF_DIVIDER_LOCATION, String.valueOf(mainSplit.getDividerLocation()));
        });
        mainSplit.setResizeWeight(0.45);
        statusLabel = new JLabel(initialStatus);
        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        setStatusMessage(initialStatus);
        getRootPane().setTransferHandler(new BaseFolderTransferHandler());
        chooseBaseBtn.addActionListener(e -> chooseBaseFolder());
        findButton.addActionListener(e -> onFind());
        printButton.addActionListener(e -> printCombined());
        orderIdField.addActionListener(e -> onFind());
        bulkModeCheck.addItemListener(e -> onModeChanged(e.getStateChange() == ItemEvent.SELECTED));
        labelRefsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) renderSelectedBulkPage();
        });
        photosList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) renderSelectedPhotos();
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                savePreferences();
                cleanupAndExit();
            }
            @Override
            public void windowOpened(WindowEvent e) {
                orderIdField.requestFocusInWindow();
            }
            @Override
            public void windowActivated(WindowEvent e) {
                orderIdField.requestFocusInWindow();
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                savePreferences();
            }
            @Override
            public void componentResized(ComponentEvent e) {
                savePreferences();
            }
        });
        orderIdField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                SwingUtilities.invokeLater(orderIdField::selectAll);
            }

        });
        addPrintShortcut(getRootPane());

        applyModeUI();
        restorePreferences();

    }
    private void onModeChanged(boolean bulk) {
        applyModeUI();
        if (hasBaseFolders()) {
            refreshBaseFolders();
        }
        else {
            clearAllViews();
        }
    }
    private void applyModeUI() {
        boolean bulk = bulkModeCheck.isSelected();
        orderIdField.setEnabled(!bulk);
        findButton.setEnabled(true);
        labelRefsList.setEnabled(bulk);

    }
    private void clearAllViews() {
        activeOrderId = null;
        combinedPreview = null;
        combinedPanel.setImage(null);
        photoView.setImages(null, null);
        labelRefsModel.clear();
        photosModel.clear();
        currentLabelLocation = null;
        printButton.setEnabled(false);
        labelPagesToPrint = new ArrayList<>();
        slipPagesToPrint = new ArrayList<>();
    }
    private File getPrimaryBaseFolder() {
        return baseFolders.isEmpty() ? null : baseFolders.get(0);
    }
    private boolean hasBaseFolders() {
        return !baseFolders.isEmpty();
    }
    private List<File> normaliseRoots(Collection<File> inputs) {
        LinkedHashSet<File> dedup = new LinkedHashSet<>();
        if (inputs != null) {
            for (File f : inputs) {
                if (f == null) {
                    continue;
                }
                File candidate = f.isDirectory() ? f : f.getParentFile();
                if (candidate == null) {
                    continue;
                }
                File abs = candidate.getAbsoluteFile();
                if (abs.isDirectory()) {
                    expandCandidate(abs, dedup);
                }
            }
        }
        return new ArrayList<>(dedup);
    }

    private void expandCandidate(File folder, Set<File> dedup) {
        if (folder == null || !folder.isDirectory()) {
            return;
        }
        if (!dedup.add(folder)) {
            return;
        }

        if (isShippingContainer(folder)) {
            File[] children = folder.listFiles(File::isDirectory);
            if (children != null) {
                for (File child : children) {
                    expandCandidate(child, dedup);
                }
            }
        }
    }

    private boolean isShippingContainer(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return false;
        }
        String name = folder.getName();
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("standard") || lower.equals("expedited")) {
            return true;
        }
        boolean digitsOnly = name.chars().allMatch(Character::isDigit);
        return digitsOnly;
    }
    private void loadBaseFolders(List<File> folders) {
        List<File> normalized = normaliseRoots(folders);
        if (normalized.isEmpty()) {
            setStatusMessage("Please select folder(s) containing your orders.");
            return;
        }
        baseFolders.clear();
        baseFolders.addAll(normalized);
        baseDir = getPrimaryBaseFolder();
        refreshBaseFolders();

    }
    private void refreshBaseFolders() {
        if (!hasBaseFolders()) {
            setStatusMessage("Select a valid base folder.");
            return;
        }
        baseDir = getPrimaryBaseFolder();
        clearAllViews();
        synchronized (photoIndexLock) {
            photoIndex = new ArrayList<>();
            photoIndexPaths = new LinkedHashSet<>();
        }
        photoRefreshInFlight.clear();
        clearProgressCaches();
        setUIEnabled(false);
        String scanningMessage = (baseFolders.size() == 1) ? "Scanning folder..." : "Scanning folders...";
        setStatusMessage(scanningMessage);
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                rebuildPhotoIndex();
                if (bulkModeCheck.isSelected()) {
                    populateBulkFromBase();
                }
                else {
                    buildLabelAndSlipIndices();
                }
                rebuildExpectations();
                return null;
            }
            @Override
            protected void done() {
                try {
                    get();
                    if (bulkModeCheck.isSelected()) {
                        setStatusMessage("BULK loaded: " + labelRefsModel.size() + " pages, " + photosModel.size() + " photos.");
                    }
                    else {
                        int photoCount = (photoIndex == null) ? 0 : photoIndex.size();
                        setStatusMessage("Indexed " + labelGroups.size() + " labels, " + slipGroups.size() + " packing slips, " + photoCount + " photos.");
                    }
                }
                catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    setStatusMessage("Error: " + cause.getMessage());
                    JOptionPane.showMessageDialog(LabelFinderUI.this, "Error while scanning:\n" + cause.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
                finally {
                    setUIEnabled(true);
                    orderIdField.requestFocusInWindow();
                    orderIdField.selectAll();
                }
            }
        }
        ;
        worker.execute();
    }
    private void chooseBaseFolder() {
        JFileChooser chooser = new JFileChooser(getPrimaryBaseFolder());
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Choose Base Folder (e.g., 'Orders')");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File[] selections = chooser.getSelectedFiles();
        if (selections == null || selections.length == 0) {
            File single = chooser.getSelectedFile();
            selections = (single == null) ? new File[0] : new File[] {
                single
            }
            ;
        }
        List<File> chosen = normaliseRoots(Arrays.asList(selections));
        if (chosen.isEmpty()) {
            setStatusMessage("Please select folder(s) containing your orders.");
            return;
        }
        loadBaseFolders(chosen);
    }
    private void setUIEnabled(boolean enabled) {
        findButton.setEnabled(enabled);
        chooseBaseBtn.setEnabled(enabled);
        orderIdField.setEnabled(enabled && !bulkModeCheck.isSelected());
        bulkModeCheck.setEnabled(enabled);
        labelRefsList.setEnabled(enabled);
        photosList.setEnabled(enabled);
    }
    private void rebuildPhotoIndex() throws IOException {
        if (!hasBaseFolders()) {
            synchronized (photoIndexLock) {
                photoIndex = new ArrayList<>();
                photoIndexPaths = new LinkedHashSet<>();
            }
            return;
        }
        LinkedHashSet<Path> seen = new LinkedHashSet<>();
        for (File root : baseFolders) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root.toPath())) {
                stream.filter(Files::isRegularFile).filter(p -> IMG_NAME.matcher(p.getFileName().toString()).matches()).forEach(p -> seen.add(p.toAbsolutePath().normalize()));
            }
        }
        List<PhotoIndexEntry> indexed = new ArrayList<>(seen.size());
        LinkedHashSet<Path> pathSet = new LinkedHashSet<>(seen.size());
        for (Path path : seen) {
            Path normalized = path.toAbsolutePath().normalize();
            if (pathSet.add(normalized)) {
                String lower = normalized.getFileName().toString().toLowerCase(Locale.ROOT);
                indexed.add(new PhotoIndexEntry(normalized, lower));
            }
        }
        indexed.sort(Comparator.comparing(PhotoIndexEntry::lowerName));
        synchronized (photoIndexLock) {
            photoIndex = indexed;
            photoIndexPaths = pathSet;
        }
    }
    private void buildLabelAndSlipIndices() {
        labelGroups = new HashMap<>();
        slipGroups = new HashMap<>();
        if (!hasBaseFolders()) {
            setStatusMessage("Base folder invalid.");
            return;
        }
        LinkedHashSet<File> pdfs = new LinkedHashSet<>();
        for (File root : baseFolders) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root.toPath())) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                        .forEach(p -> pdfs.add(p.toFile().getAbsoluteFile()));
            } catch (IOException e) {
                setStatusMessage("Error reading PDFs: " + e.getMessage());
                return;
            }
        }
        Pattern packingSlipName = Pattern.compile("(?i)^Amazon(?:\\s*\\(\\d+\\))?\\.pdf$");
        Pattern packingSlipFolder = Pattern.compile("(?i)^(?:\\d{2}\\s*[PRWB]|mix).*");
        for (File pdf : pdfs) {
            String name = pdf.getName();
            File parent = pdf.getParentFile();
            String parentName = parent != null ? parent.getName() : "";
            String lowerName = name.toLowerCase(Locale.ROOT);
            boolean looksLikePackingSlip = packingSlipName.matcher(name).matches()
                || lowerName.startsWith("amazon");
            if (looksLikePackingSlip) {
                try {
                    Map<String, List<Integer>> m = PackSlipExtractor.indexOrderToPages(pdf);
                    for (Map.Entry<String, List<Integer>> e : m.entrySet()) {
                        slipGroups.put(e.getKey(), new PageGroup(pdf, new ArrayList<>(e.getValue())));
                    }
                }
                catch (IOException ignored) {
                }
            }
            else {
                try {
                    Map<String, List<Integer>> m = PdfLinker.buildOrderIdToPagesMap(pdf);
                    for (Map.Entry<String, List<Integer>> e : m.entrySet()) {
                        labelGroups.put(e.getKey(), new PageGroup(pdf, new ArrayList<>(e.getValue())));
                    }
                }
                catch (IOException ignored) {
                }
            }
        }
        int photoCount = (photoIndex == null) ? 0 : photoIndex.size();
        setStatusMessage("Indexed " + labelGroups.size() + " labels, " + slipGroups.size() + " packing slips, " + photoCount + " photos.");
    }
    private void onFind() {
        if (bulkModeCheck.isSelected()) {
            populateBulkFromBase();
            return;
        }
        findSingleOrderFlow();
    }
    private void findSingleOrderFlow() {
        ScanInput scanInput = parseScanInput(orderIdField.getText());
        String orderId = scanInput.orderId();
        if (orderId.isEmpty()) {
            setStatusMessage("Please enter an Order ID.");
            orderIdField.requestFocusInWindow();
            return;
        }
        activeOrderId = orderId;
        combinedPreview = null;
        combinedPanel.setImage(null);
        printButton.setEnabled(false);
        BufferedImage labelImg = null;
        BufferedImage slipImg = null;
        labelPagesToPrint = new ArrayList<>();
        slipPagesToPrint = new ArrayList<>();
        PageGroup lg = (labelGroups != null) ? labelGroups.get(orderId) : null;
        if (lg != null) {
            List<BufferedImage> labelPages = renderPdfPagesList(lg.file, lg.pages, 150);
            labelPagesToPrint.addAll(labelPages);
            labelImg = stackMany(withBorder(labelPages, Color.RED, 8), 12, Color.WHITE);
            currentLabelLocation = new LabelLocation(lg.file, lg.pages.get(0));
        }
        else {
            setStatusMessage("Shipping label not found for: " + orderId);
        }
        PageGroup sg = (slipGroups != null) ? slipGroups.get(orderId) : null;
        if (sg != null) {
            List<BufferedImage> slipPages = renderPdfPagesList(sg.file, sg.pages, 150);
            slipPagesToPrint.addAll(slipPages);
            slipImg = stackMany(withBorder(slipPages, new Color(0,120,215), 8), 12, Color.WHITE);
        }
        else if (lg != null) {
            setStatusMessage("Packing slip not found for: " + orderId);
        }
        combinedPreview = stackImagesVertically(labelImg, slipImg, 12, Color.WHITE);
        if (combinedPreview != null) {
            combinedPanel.setImage(combinedPreview);
            printButton.setEnabled(true);
        }
        photoView.setImages(null, null);
        List<Path> photoMatches = collectPhotosFromIndex(orderId);
        displayPhotosForOrder(orderId, photoMatches, false);
        List<Path> designMatches = collectReadyDesignPhotos(orderId);
        tagReadyDesigns(orderId, designMatches, true, true);
        refreshPhotosForOrderAsync(orderId);
        boolean hasPrintableContent = !labelPagesToPrint.isEmpty() || !slipPagesToPrint.isEmpty();
        ScanUpdate scanUpdate = null;
        OrderScanState stateForOrder = null;
        boolean expectationMissing = false;
        if (hasPrintableContent) {
            scanUpdate = trackScanProgress(orderId, scanInput.itemKey(), scanInput.rawItemId());
            if (scanUpdate == null) {
                expectationMissing = true;
            }
            else {
                updateProgressBanner(scanUpdate.scanned, scanUpdate.expected);
                if (scanUpdate.counted) {
                    rememberScanEvent(orderId, scanUpdate.itemIdentifier);
                }
                if (!scanUpdate.counted) {
                    String message;
                    if (scanUpdate.duplicate) {
                        message = composeDuplicateMessage(scanUpdate);
                    }
                    else if (scanUpdate.unknownItem) {
                        message = composeUnknownItemMessage(scanUpdate);
                    }
                    else if (scanUpdate.alreadyComplete) {
                        message = composeAlreadyCompleteMessage(scanUpdate);
                    }
                    else {
                        message = composeGenericHoldMessage(scanUpdate);
                    }
                    setStatusMessage(message);
                    orderIdField.setText("");
                    orderIdField.requestFocusInWindow();
                    orderIdField.selectAll();
                    return;
                }
                else if (!scanUpdate.completed) {
                    setStatusMessage(composeInProgressMessage(scanUpdate));
                    orderIdField.setText("");
                    orderIdField.requestFocusInWindow();
                    orderIdField.selectAll();
                    return;
                }
            }
            if (scanUpdate != null) {
                stateForOrder = scanProgress.get(orderId);
            }
        }
        boolean printed = false;
        String completionMessage = null;
        if (hasPrintableContent) {
            printed = printCombinedDirect();
            if (printed) {
                if (scanUpdate != null && scanUpdate.completed) {
                    markOrderComplete(orderId, stateForOrder);
                    completionMessage = composeCompletionMessage(scanUpdate);
                }
                else if (expectationMissing) {
                    completionMessage = "Printed (no quantity data for order: " + orderId + ").";
                }
            }
            orderIdField.setText("");
            orderIdField.requestFocusInWindow();
        }
        else {
            orderIdField.requestFocusInWindow();
            orderIdField.selectAll();
        }
        if (printed) {
            String photoMessage = describePhotoOutcome(orderId);
            String status = completionMessage;
            if (photoMessage != null) {
                status = (status == null) ? photoMessage : status + ' ' + photoMessage;
            }
            if (status == null) {
                status = "Printed.";
            }
            setStatusMessage(status);
        }
    }
    private static boolean applyTagWithBrew(Path filePath, String tagName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("tag", "-a", tagName, filePath.toAbsolutePath().toString());
            Process p = pb.start();
            int exitCode = p.waitFor();
            return exitCode == 0;
        }
        catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to apply Finder tag via 'tag' command", e);
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Tag application interrupted", e);
            return false;
        }
    }
    /**
     * Launches the application on the Swing event thread.
     *
     * @param args command line arguments (ignored)
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LabelFinderUI().setVisible(true));
    }
    private List<BufferedImage> renderPdfPagesList(File pdf, List<Integer> pages1Based, int dpi) {
        List<BufferedImage> out = new ArrayList<>();
        if (pdf == null || pages1Based == null || pages1Based.isEmpty()) return out;
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int p : pages1Based) {
                BufferedImage img = renderer.renderImageWithDPI(p - 1, dpi);
                out.add(img);
            }
        }
        catch (IOException ignored) {
        }
        return out;
    }
    private static BufferedImage stackMany(List<BufferedImage> images, int gap, Color bg) {
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
        for (BufferedImage im : images) {
            if (im == null) continue;
            g.drawImage(im, 0, y, null);
            y += im.getHeight();
        }
        g.dispose();
        return out;
    }
    private static List<BufferedImage> withBorder(List<BufferedImage> images, Color color, int size) {
        List<BufferedImage> out = new ArrayList<>();
        if (images == null) return out;
        for (BufferedImage im : images) {
            out.add(addBorder(im, color, size));
        }
        return out;
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
        List<BufferedImage> pages = new ArrayList<>();
        if (labelPagesToPrint != null) pages.addAll(labelPagesToPrint);
        if (slipPagesToPrint != null) pages.addAll(slipPagesToPrint);
        if (pages.isEmpty()) {
            if (combinedPreview == null) {
                setStatusMessage("Nothing to print.");
                return;
            }
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
                setStatusMessage("Print job sent.");
            }
            catch (PrinterException e) {
                JOptionPane.showMessageDialog(this, "Could not print.\nError: " + e.getMessage(), "Print Error", JOptionPane.ERROR_MESSAGE);
            }
        }
        else {
            setStatusMessage("Print job canceled.");
        }
    }
    private boolean printCombinedDirect() {
        List<BufferedImage> pages = new ArrayList<>();
        if (labelPagesToPrint != null) pages.addAll(labelPagesToPrint);
        if (slipPagesToPrint != null) pages.addAll(slipPagesToPrint);
        if (pages.isEmpty()) {
            if (combinedPreview == null) {
                setStatusMessage("Nothing to print.");
                return false;
            }
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
            setStatusMessage("Printed (direct).");
            return true;
        }
        catch (PrinterException e) {
            JOptionPane.showMessageDialog(this, "Direct print failed.\n" + e.getMessage(), "Print Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }
    private void cleanupAndExit() {
        dispose();
        System.exit(0);
    }
    private void populateBulkFromBase() {
        if (!hasBaseFolders()) {
            setStatusMessage("Select a valid base folder for BULK.");
            return;
        }
        labelRefsModel.clear();
        for (File root : baseFolders) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root.toPath())) {
                stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")).sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)).forEach(pdfPath -> {
                    try (PDDocument doc = PDDocument.load(pdfPath.toFile())) {
                        for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                            labelRefsModel.addElement(new LabelRef(pdfPath.toFile(), p));
                        }
                    }
                    catch (IOException ignored) {
                    }
                });
            }
            catch (IOException e) {
                setStatusMessage("Error reading bulk PDFs: " + e.getMessage());
                return;
            }
        }
        photosModel.clear();
        List<Path> indexedPhotos = collectAllPhotosFromIndex();
        if (indexedPhotos.isEmpty()) {
            setStatusMessage("No photos indexed. Choose a base folder.");
        }
        else {
            indexedPhotos.forEach(photosModel::addElement);
        }
        if (!labelRefsModel.isEmpty()) labelRefsList.setSelectedIndex(0);
        if (!photosModel.isEmpty()) photosList.setSelectedIndex(0);
        setStatusMessage("BULK loaded: " + labelRefsModel.size() + " pages, " + photosModel.size() + " photos.");
    }
    private void renderSelectedBulkPage() {
        LabelRef ref = labelRefsList.getSelectedValue();
        if (ref == null) {
            clearAllViews();
            return;
        }
        currentLabelLocation = new LabelLocation(ref.file, ref.page1Based);
        try (PDDocument doc = PDDocument.load(ref.file)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage img = renderer.renderImageWithDPI(ref.page1Based - 1, 150);
            combinedPreview = addBorder(img, Color.DARK_GRAY, 8);
            combinedPanel.setImage(combinedPreview);
            printButton.setEnabled(true);
            labelPagesToPrint = new ArrayList<>(List.of(img));
            slipPagesToPrint = new ArrayList<>();
        }
        catch (IOException e) {
            clearAllViews();
        }
    }
    private void renderSelectedPhotos() {
        List<Path> selected = photosList.getSelectedValuesList();
        if (selected == null || selected.isEmpty()) {
            photoView.setImages(null, null);
            return;
        }
        try {
            BufferedImage a = ImageIO.read(selected.get(0).toFile());
            BufferedImage b = selected.size() > 1 ? ImageIO.read(selected.get(1).toFile()) : null;
            photoView.setImages(a, b);
            if (selected.size() > 2) {
                setStatusMessage("Only the first 2 selected photos are shown.");
            }
        }
        catch (IOException ex) {
            photoView.setImages(null, null);
        }
    }
    private List<Path> collectPhotosFromIndex(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return new ArrayList<>();
        }
        String needle = orderId.toLowerCase(Locale.ROOT);
        synchronized (photoIndexLock) {
            if (photoIndex == null || photoIndex.isEmpty()) {
                return new ArrayList<>();
            }
            List<Path> matches = new ArrayList<>();
            for (PhotoIndexEntry entry : photoIndex) {
                if (entry.lowerName().contains(needle)) {
                    matches.add(entry.path());
                }
            }
            return matches;
        }
    }
    private List<Path> collectAllPhotosFromIndex() {
        synchronized (photoIndexLock) {
            if (photoIndex == null || photoIndex.isEmpty()) {
                return new ArrayList<>();
            }
            List<Path> out = new ArrayList<>(photoIndex.size());
            for (PhotoIndexEntry entry : photoIndex) {
                out.add(entry.path());
            }
            return out;
        }
    }
    private void displayPhotosForOrder(String orderId, List<Path> matches, boolean preserveSelection) {
        List<Path> previouslySelected = preserveSelection ? new ArrayList<>(photosList.getSelectedValuesList()) : Collections.emptyList();
        photosModel.clear();
        for (Path match : matches) {
            photosModel.addElement(match);
        }
        if (matches.isEmpty()) {
            photoView.setImages(null, null);
            return;
        }
        if (preserveSelection && !previouslySelected.isEmpty()) {
            LinkedHashSet<Path> desired = new LinkedHashSet<>(previouslySelected);
            photosList.clearSelection();
            for (int i = 0; i < matches.size(); i++) {
                if (desired.contains(matches.get(i))) {
                    photosList.addSelectionInterval(i, i);
                }
            }
            if (photosList.isSelectionEmpty()) {
                selectAndPreviewFirstPhotos();
            }
            else {
                renderSelectedPhotos();
            }
        }
        else {
            selectAndPreviewFirstPhotos();
        }
    }
    private void refreshPhotosForOrderAsync(String orderId) {
        if (orderId == null || orderId.isBlank() || !hasBaseFolders()) {
            return;
        }
        if (!photoRefreshInFlight.add(orderId)) {
            return;
        }
        SwingWorker<List<Path>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<Path> doInBackground() {
                try {
                    return scanPhotosFromDisk(orderId);
                }
                catch (IOException ignored) {
                    return Collections.emptyList();
                }
            }
            @Override
            protected void done() {
                try {
                    List<Path> diskMatches = get();
                    List<Path> newEntries = addPhotosToIndex(diskMatches);
                    List<Path> designMatches = collectReadyDesignPhotos(orderId);
                    boolean newReadyDesignFound = !newEntries.isEmpty() && designMatches.stream().anyMatch(newEntries::contains);
                    if (orderId.equals(activeOrderId)) {
                        List<Path> refreshed = collectPhotosFromIndex(orderId);
                        displayPhotosForOrder(orderId, refreshed, true);
                        if (newReadyDesignFound) {
                            tagReadyDesigns(orderId, designMatches, true, false);
                        }
                    }
                    else if (newReadyDesignFound) {
                        tagReadyDesigns(orderId, designMatches, false, false);
                    }
                }
                catch (Exception ignored) {
                }
                finally {
                    photoRefreshInFlight.remove(orderId);
                }
            }
        };
        worker.execute();
    }
    private List<Path> addPhotosToIndex(List<Path> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> newEntries = new ArrayList<>();
        synchronized (photoIndexLock) {
            if (photoIndex == null) {
                photoIndex = new ArrayList<>();
            }
            if (photoIndexPaths == null) {
                photoIndexPaths = new LinkedHashSet<>();
            }
            for (Path candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                Path normalized = candidate.toAbsolutePath().normalize();
                if (photoIndexPaths.add(normalized)) {
                    photoIndex.add(new PhotoIndexEntry(normalized, normalized.getFileName().toString().toLowerCase(Locale.ROOT)));
                    newEntries.add(normalized);
                }
            }
            if (!newEntries.isEmpty()) {
                photoIndex.sort(Comparator.comparing(PhotoIndexEntry::lowerName));
            }
        }
        return newEntries;
    }
    private List<Path> scanPhotosFromDisk(String orderId) throws IOException {
        if (orderId == null || orderId.isBlank() || !hasBaseFolders()) {
            return Collections.emptyList();
        }
        String needle = orderId.toLowerCase(Locale.ROOT);
        LinkedHashSet<Path> results = new LinkedHashSet<>();
        for (File root : baseFolders) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root.toPath(), 10)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String fileName = p.getFileName().toString();
                    if (IMG_NAME.matcher(fileName).matches() && fileName.toLowerCase(Locale.ROOT).contains(needle)) {
                        results.add(p.toAbsolutePath().normalize());
                    }
                });
            }
        }
        List<Path> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }
    private void tagReadyDesigns(String orderId, List<Path> designMatches, boolean updateStatus, boolean allowEmptyStatusMessage) {
        if (designMatches == null || designMatches.isEmpty()) {
            if (updateStatus && allowEmptyStatusMessage) {
                setStatusMessage("No ready design found to tag for: " + orderId);
            }
            return;
        }
        int ok = 0;
        for (Path p : designMatches) {
            if (applyTagWithBrew(p, "Green")) {
                ok++;
            }
        }
        if (!updateStatus) {
            return;
        }
        if (ok > 0) {
            setStatusMessage("Ready designs tagged: Green (" + ok + "/" + designMatches.size() + ").");
        }
        else {
            setStatusMessage("Tried to tag " + designMatches.size() + " item(s) with 'tag' command, but it failed. Is 'tag' installed and in your PATH?");
        }
    }
    /**
     * Enables drag-and-drop of folders so operators can quickly switch data sources.
     */
    private class BaseFolderTransferHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return false;
            }
            support.setShowDropLocation(true);
            support.setDropAction(COPY);
            return true;
        }
        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                @SuppressWarnings("unchecked") List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                List<File> normalized = normaliseRoots(files);
                if (normalized.isEmpty()) {
                    setStatusMessage("Drop folders to load orders.");
                    return false;
                }
                loadBaseFolders(normalized);
                return true;
            }
            catch (UnsupportedFlavorException | IOException e) {
                setStatusMessage("Drop failed: " + e.getMessage());
                return false;
            }
        }
    }
    private void clearProgressCaches() {
        expectationIndex.clear();
        scanProgress.clear();
        completedOrders.clear();
        scanHistory.clear();
        updateProgressBanner(0, 0);
    }
    private void rebuildExpectations() {
        expectationIndex.clear();
        if (!hasBaseFolders()) {
            return;
        }
        for (File root : baseFolders) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root.toPath(), 8)) {
                stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")).forEach(p -> {
                    OrderContribution contribution = readOrderContribution(p.toFile());
                    if (contribution == null || contribution.orderId == null || contribution.orderId.isBlank()) {
                        return;
                    }
                    OrderExpectation expectation = expectationIndex.computeIfAbsent(contribution.orderId, OrderExpectation::new);
                    expectation.registerItem(contribution.orderItemId, contribution.itemQuantity);
                });
            }
            catch (IOException ignored) {
            }
        }
    }
    private OrderExpectation resolveExpectation(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        OrderExpectation cached = expectationIndex.get(orderId);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        OrderExpectation loaded = loadExpectationForOrder(orderId);
        if (loaded != null && !loaded.isEmpty()) {
            expectationIndex.put(orderId, loaded);
            return loaded;
        }
        return null;
    }
    private OrderExpectation loadExpectationForOrder(String orderId) {
        if (!hasBaseFolders()) {
            return null;
        }
        OrderExpectation expectation = new OrderExpectation(orderId);
        for (File root : baseFolders) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root.toPath(), 8)) {
                stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")).forEach(p -> {
                    OrderContribution contribution = readOrderContribution(p.toFile());
                    if (contribution == null) {
                        return;
                    }
                    if (!orderId.equals(contribution.orderId)) {
                        return;
                    }
                    expectation.registerItem(contribution.orderItemId, contribution.itemQuantity);
                });
            }
            catch (IOException ignored) {
            }
        }
        return expectation.isEmpty() ? null : expectation;
    }
    private static OrderContribution readOrderContribution(File jsonFile) {
        if (jsonFile == null || !jsonFile.isFile()) {
            return null;
        }
        try {
            String content = Files.readString(jsonFile.toPath());
            JSONObject root = new JSONObject(content);
            String orderId = root.optString("orderId", "");
            String itemId = root.optString("orderItemId", "");
            if (orderId.isBlank() || itemId.isBlank()) {
                return null;
            }
            int quantity = Math.max(root.optInt("quantity", 1), 1);
            return new OrderContribution(orderId, itemId, quantity);
        }
        catch (IOException | JSONException ignored) {
            return null;
        }
    }
    private ScanUpdate trackScanProgress(String orderId, String itemKey, String rawItemId) {
        CompletedOrderInfo archived = completedOrders.get(orderId);
        if (archived != null) {
            String identifier = firstNonBlank(rawItemId, itemKey, archived.lastIdentifier());
            String display = identifier != null ? shortenItemReference(identifier) : null;
            return new ScanUpdate(orderId,
                archived.totalScanned(),
                archived.expectedTotal(),
                true,
                false,
                false,
                false,
                true,
                display,
                archived.totalScanned(),
                archived.expectedTotal(),
                identifier);
        }
        OrderExpectation expectation = resolveExpectation(orderId);
        if (expectation == null) {
            return null;
        }
        OrderScanState state = scanProgress.computeIfAbsent(orderId, key -> new OrderScanState(orderId, expectation));
        return state.recordScan(itemKey, rawItemId);
    }
    private void markOrderComplete(String orderId, OrderScanState state) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        OrderScanState removed = scanProgress.remove(orderId);
        if (removed != null) {
            state = removed;
        }
        if (state != null) {
            CompletedOrderInfo info = new CompletedOrderInfo(state.expectedTotal());
            for (String identifier : state.scannedIdentifiersSnapshot()) {
                info.addItem(identifier);
            }
            completedOrders.put(orderId, info);
        }
        expectationIndex.remove(orderId);
    }
    private String describePhotoOutcome(String orderId) {
        if (photosModel.isEmpty()) {
            if (currentLabelLocation != null) {
                return "No matching photo found for: " + orderId;
            }
            return null;
        }
        if (photosModel.size() == 1) {
            return "Auto-selected 1 photo for preview.";
        }
        return "Auto-selected first 2 photos for preview.";
    }
    private void rememberScanEvent(String orderId, String itemIdentifier) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        if (itemIdentifier == null || itemIdentifier.isBlank()) {
            return;
        }
        String normalized = itemIdentifier.trim();
        String combined = combineOrderAndItem(orderId.trim(), normalized);
        scanHistory.addLast(combined);
        while (scanHistory.size() > MAX_SCAN_HISTORY) {
            scanHistory.removeFirst();
        }
    }
    private void updateProgressBanner(int scanned, int expected) {
        if (scanned < 0) {
            scanned = 0;
        }
        if (expected < 0) {
            expected = 0;
        }
        topStatusLabel.setText("Scanned " + scanned + "/" + expected);
    }
    private void setStatusMessage(String message) {
        String text = (message == null) ? "" : message;
        statusLabel.setText(text);
    }
    private static String combineOrderAndItem(String orderId, String itemIdentifier) {
        return orderId + "^" + itemIdentifier;
    }
    private ScanInput parseScanInput(String rawInput) {
        if (rawInput == null) {
            return new ScanInput("", null, null);
        }
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) {
            return new ScanInput("", null, null);
        }
        String[] parts = trimmed.split("\\^", 2);
        String id = parts[0].trim();
        String rawItem = null;
        String key = null;
        if (parts.length > 1) {
            rawItem = parts[1].trim();
            if (!rawItem.isEmpty()) {
                key = normalizeItemKey(rawItem);
            }
        }
        return new ScanInput(id, rawItem, key);
    }
    private static String normalizeItemKey(String rawItemId) {
        if (rawItemId == null) {
            return null;
        }
        String reduced = reduceOrderItemId(rawItemId);
        if (reduced != null && !reduced.isBlank()) {
            return reduced;
        }
        String trimmed = rawItemId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
    private static String reduceOrderItemId(String itemId) {
        if (itemId == null) {
            return null;
        }
        String trimmed = itemId.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= 6) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - 6);
    }
    private static String shortenItemReference(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 6) {
            return trimmed;
        }
        return "…" + trimmed.substring(trimmed.length() - 6);
    }
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }
    private String composeInProgressMessage(ScanUpdate update) {
        StringBuilder sb = new StringBuilder();
        sb.append("Order ").append(update.orderId).append(": scanned ").append(update.scanned).append('/').append(update.expected);
        String itemSegment = formatItemSegment(update);
        if (itemSegment != null) {
            sb.append(" (last: ").append(itemSegment).append(')');
        }
        int remaining = Math.max(update.expected - update.scanned, 0);
        if (remaining > 0) {
            sb.append(". Waiting for ").append(remaining).append(remaining == 1 ? " more item." : " more items.");
        }
        return sb.toString();
    }
    private String composeCompletionMessage(ScanUpdate update) {
        return "Order " + update.orderId + ": scanned " + update.scanned + '/' + update.expected + ". Printing.";
    }
    private String composeDuplicateMessage(ScanUpdate update) {
        StringBuilder sb = new StringBuilder();
        sb.append("Order ").append(update.orderId).append(": ");
        String itemSegment = formatItemSegment(update);
        if (itemSegment != null) {
            sb.append(itemSegment).append(" already scanned.");
        }
        else {
            sb.append("Item already scanned.");
        }
        sb.append(" Progress ").append(update.scanned).append('/').append(update.expected).append('.');
        return sb.toString();
    }
    private String composeUnknownItemMessage(ScanUpdate update) {
        String display = update.itemDisplay != null ? update.itemDisplay : "item";
        return "Order " + update.orderId + ": barcode item " + display + " not expected. Scanned " + update.scanned + '/' + update.expected + '.';
    }
    private String composeAlreadyCompleteMessage(ScanUpdate update) {
        return "Order " + update.orderId + " already completed (" + update.scanned + '/' + update.expected + ").";
    }
    private String composeGenericHoldMessage(ScanUpdate update) {
        return "Order " + update.orderId + ": waiting. Progress " + update.scanned + '/' + update.expected + '.';
    }
    private static String formatItemSegment(ScanUpdate update) {
        if (update.itemDisplay == null || update.itemDisplay.isBlank()) {
            return null;
        }
        if (update.itemExpected > 1 && update.itemProgress > 0) {
            return update.itemDisplay + " " + update.itemProgress + '/' + update.itemExpected;
        }
        return update.itemDisplay;
    }
    private record ScanInput(String orderId, String rawItemId, String itemKey) {
        ScanInput {
            orderId = (orderId == null) ? "" : orderId;
            rawItemId = (rawItemId == null || rawItemId.isBlank()) ? null : rawItemId;
            itemKey = (itemKey == null || itemKey.isBlank()) ? null : itemKey;
        }
    }
    private static final class OrderExpectation {
        private final String orderId;
        private final Map<String, ItemSpec> items = new LinkedHashMap<>();
        OrderExpectation(String orderId) {
            this.orderId = orderId;
        }
        void registerItem(String itemId, int quantity) {
            int sanitized = quantity <= 0 ? 1 : quantity;
            String canonical = (itemId == null || itemId.isBlank()) ? null : itemId.trim();
            if (canonical == null) {
                items.computeIfAbsent(null, k -> new ItemSpec(null, null)).expected += sanitized;
                return;
            }
            String key = reduceOrderItemId(canonical);
            ItemSpec spec = items.computeIfAbsent(key, k -> new ItemSpec(key, canonical));
            spec.expected += sanitized;
        }
        Collection<ItemSpec> specs() {
            return items.values();
        }
        int expectedForItem(String itemKey) {
            ItemSpec spec = items.get(itemKey);
            return spec == null ? 0 : spec.expected;
        }
        int resolvedTotal() {
            return items.values().stream().mapToInt(spec -> spec.expected).sum();
        }
        boolean isEmpty() {
            return items.isEmpty();
        }
    }
    private static final class ItemSpec {
        final String key;
        final String fullId;
        int expected;
        ItemSpec(String key, String fullId) {
            this.key = key;
            this.fullId = (fullId == null || fullId.isBlank()) ? null : fullId.trim();
        }
    }
    private static final class OrderScanState {
        private final String orderId;
        private final Map<String, ItemCounter> keyedCounters = new LinkedHashMap<>();
        private final List<ItemCounter> orderedCounters = new ArrayList<>();
        private final int expectedTotal;
        private int scannedTotal;
        private final List<String> scannedIdentifiers = new ArrayList<>();
        OrderScanState(String orderId, OrderExpectation expectation) {
            this.orderId = orderId;
            int sum = 0;
            for (ItemSpec spec : expectation.specs()) {
                int expected = Math.max(spec.expected, 0);
                if (expected <= 0) {
                    continue;
                }
                ItemCounter counter = new ItemCounter(spec.key, spec.fullId, expected);
                if (spec.key != null) {
                    keyedCounters.put(spec.key, counter);
                }
                orderedCounters.add(counter);
                sum += expected;
            }
            int resolved = Math.max(sum, expectation.resolvedTotal());
            if (resolved > sum) {
                int remaining = resolved - sum;
                orderedCounters.add(new ItemCounter(null, null, remaining));
                sum = resolved;
            }
            expectedTotal = Math.max(sum, 1);
        }
        ScanUpdate recordScan(String itemKey, String rawItemId) {
            if (scannedTotal >= expectedTotal) {
                return new ScanUpdate(orderId, scannedTotal, expectedTotal, true, false, false, false, true, null, 0, 0, lastIdentifier());
            }
            if (itemKey != null) {
                ItemCounter counter = keyedCounters.get(itemKey);
                if (counter != null) {
                    if (counter.scanned >= counter.expected) {
                        return new ScanUpdate(orderId, scannedTotal, expectedTotal, scannedTotal >= expectedTotal, false, true, false, scannedTotal >= expectedTotal, counter.display(), counter.scanned, counter.expected, counter.identifier());
                    }
                    counter.scanned++;
                    scannedTotal++;
                    String identifier = counter.identifier();
                    recordIdentifier(identifier);
                    return new ScanUpdate(orderId, scannedTotal, expectedTotal, scannedTotal >= expectedTotal, true, false, false, scannedTotal >= expectedTotal, counter.display(), counter.scanned, counter.expected, identifier);
                }
                String identifier = firstNonBlank(rawItemId, itemKey);
                String display = shortenItemReference(identifier);
                return new ScanUpdate(orderId, scannedTotal, expectedTotal, scannedTotal >= expectedTotal, false, false, true, scannedTotal >= expectedTotal, display, 0, 0, identifier);
            }
            ItemCounter next = orderedCounters.stream().filter(c -> c.scanned < c.expected).findFirst().orElse(null);
            if (next == null) {
                return new ScanUpdate(orderId, scannedTotal, expectedTotal, true, false, false, false, true, null, 0, 0, lastIdentifier());
            }
            next.scanned++;
            scannedTotal++;
            String identifier = firstNonBlank(rawItemId, next.identifier());
            recordIdentifier(identifier);
            return new ScanUpdate(orderId, scannedTotal, expectedTotal, scannedTotal >= expectedTotal, true, false, false, scannedTotal >= expectedTotal, next.display(), next.scanned, next.expected, identifier);
        }
        private void recordIdentifier(String identifier) {
            if (identifier == null) {
                return;
            }
            String normalized = identifier.trim();
            if (!normalized.isEmpty()) {
                scannedIdentifiers.add(normalized);
            }
        }
        List<String> scannedIdentifiersSnapshot() {
            return new ArrayList<>(scannedIdentifiers);
        }
        int expectedTotal() {
            return expectedTotal;
        }
        int totalScanned() {
            return scannedTotal;
        }
        String lastIdentifier() {
            return scannedIdentifiers.isEmpty() ? null : scannedIdentifiers.get(scannedIdentifiers.size() - 1);
        }
    }
    private static final class ItemCounter {
        final String key;
        final String fullId;
        final int expected;
        int scanned;
        ItemCounter(String key, String fullId, int expected) {
            this.key = key;
            this.fullId = (fullId == null || fullId.isBlank()) ? null : fullId.trim();
            this.expected = expected;
        }
        String display() {
            String candidate = (fullId != null) ? fullId : key;
            if (candidate == null || candidate.isBlank()) {
                return null;
            }
            return shortenItemReference(candidate);
        }
        String identifier() {
            if (fullId != null && !fullId.isBlank()) {
                return fullId;
            }
            return key;
        }
    }
    private static final class CompletedOrderInfo {
        private final int expectedTotal;
        private int totalScanned;
        private final LinkedHashMap<String, Integer> itemCounts = new LinkedHashMap<>();
        private String lastIdentifier;
        CompletedOrderInfo(int expectedTotal) {
            this.expectedTotal = Math.max(expectedTotal, 0);
        }
        void addItem(String identifier) {
            if (identifier == null) {
                return;
            }
            String normalized = identifier.trim();
            if (normalized.isEmpty()) {
                return;
            }
            itemCounts.merge(normalized, 1, Integer::sum);
            totalScanned++;
            lastIdentifier = normalized;
        }
        int expectedTotal() {
            return expectedTotal;
        }
        int totalScanned() {
            return totalScanned;
        }
        String lastIdentifier() {
            return lastIdentifier;
        }
    }
    private static final class ScanUpdate {
        final String orderId;
        final int scanned;
        final int expected;
        final boolean completed;
        final boolean counted;
        final boolean duplicate;
        final boolean unknownItem;
        final boolean alreadyComplete;
        final String itemDisplay;
        final int itemProgress;
        final int itemExpected;
        final String itemIdentifier;
        ScanUpdate(String orderId, int scanned, int expected, boolean completed, boolean counted, boolean duplicate, boolean unknownItem, boolean alreadyComplete, String itemDisplay, int itemProgress, int itemExpected, String itemIdentifier) {
            this.orderId = orderId;
            this.scanned = scanned;
            this.expected = expected;
            this.completed = completed;
            this.counted = counted;
            this.duplicate = duplicate;
            this.unknownItem = unknownItem;
            this.alreadyComplete = alreadyComplete;
            this.itemDisplay = itemDisplay;
            this.itemProgress = itemProgress;
            this.itemExpected = itemExpected;
            this.itemIdentifier = itemIdentifier;
        }
    }
    private record OrderContribution(String orderId, String orderItemId, int itemQuantity) {
    }
    private void addPrintShortcut(JRootPane rootPane) {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_P, mask);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, "printCombinedAction");
        rootPane.getActionMap().put("printCombinedAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                printCombined();
            }
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
    /**
     * Simple panel that centers and renders a single buffered image.
     */
    private static class ImagePanel extends JPanel {
        private BufferedImage image;
        /**
         * Updates the image displayed on the panel and repaints.
         *
         * @param img image to display, or {@code null} to clear the panel
         */
        public void setImage(BufferedImage img) {
            this.image = img;
            revalidate();
            repaint();
        }
        /**
         * Reports the preferred size so scroll panes can size themselves appropriately.
         */
        @Override
        public Dimension getPreferredSize() {
            return image == null ? new Dimension(900, 1200) : new Dimension(image.getWidth(), image.getHeight());
        }
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image != null) {
                int x = Math.max((getWidth() - image.getWidth()) / 2, 0);
                int y = Math.max((getHeight() - image.getHeight()) / 2, 0);
                ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(image, x, y, this);
            }
        }
    }
    /**
     * Panel capable of rendering up to two photos side by side while preserving aspect ratio.
     */
    private static class DualImagePanel extends JPanel {
        private BufferedImage imgA;
        private BufferedImage imgB;
        /**
         * Sets the images to display and triggers a repaint.
         *
         * @param a left image, or {@code null}
         * @param b right image, or {@code null}
         */
        public void setImages(BufferedImage a, BufferedImage b) {
            this.imgA = a;
            this.imgB = b;
            revalidate();
            repaint();
        }
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (imgA == null && imgB == null) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int panelWidth = getWidth();
            int panelHeight = getHeight();
            if (imgB == null) {
                drawFitted(g2, imgA, 0, 0, panelWidth, panelHeight);
            }
            else {
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
    private record PhotoIndexEntry(Path path, String lowerName) {
    }
    /**
     * Lightweight description of a PDF and page pair for the bulk label list.
     */
    private static class LabelRef {
        final File file;
        final int page1Based;
        LabelRef(File file, int page1Based) {
            this.file = file;
            this.page1Based = page1Based;
        }
        String toDisplayString() {
            return file.getName() + " — p." + page1Based;
        }
        @Override
        public String toString() {
            return toDisplayString();
        }
    }
    /**
     * Printable implementation that streams buffered images as sequential pages.
     */
    private static class MultiPagePrintable implements Printable {
        private final List<BufferedImage> pages;
        MultiPagePrintable(List<BufferedImage> pages) {
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
    /**
     * Builds a 4x6 inch page format tailored for thermal shipping label printers.
     *
     *
     * @return configured page format
     */
    private void restorePreferences() {
        // Bulk mode
        boolean bulk = Boolean.parseBoolean(prefs.getString(PREF_BULK_MODE).orElse("false"));
        bulkModeCheck.setSelected(bulk);
        applyModeUI();

        // Pencere konumu/boyutu
        prefs.getString(PREF_WIN_BOUNDS).ifPresent(s -> {
            String[] parts = s.split(",");
            if (parts.length == 4) {
                try {
                    int x = Integer.parseInt(parts[0]);
                    int y = Integer.parseInt(parts[1]);
                    int w = Integer.parseInt(parts[2]);
                    int h = Integer.parseInt(parts[3]);
                    setBounds(x, y, w, h);
                } catch (NumberFormatException ignored) { /* geçersizse yoksay */ }
            }
        });

        // Splitter
        prefs.getString(PREF_DIVIDER_LOCATION).ifPresent(s -> {
            try {
                int loc = Integer.parseInt(s);

                SwingUtilities.invokeLater(() -> mainSplit.setDividerLocation(loc));
            } catch (NumberFormatException ignored) { }
        });





    }

    private void savePreferences() {
        // pencere boyut/konum
        Rectangle r = getBounds();
        String win = r.x + "," + r.y + "," + r.width + "," + r.height;
        prefs.putString(PREF_WIN_BOUNDS, win);

        // divider
        prefs.putString(PREF_DIVIDER_LOCATION, String.valueOf(((JSplitPane) getContentPane()
                .getComponents()[1]).getDividerLocation())); // ama biz zaten mainSplit değişkenini tutuyoruz:


        // bulk mode
        prefs.putString(PREF_BULK_MODE, String.valueOf(bulkModeCheck.isSelected()));

        // base dir(ler)
        if (!baseFolders.isEmpty()) {

        }

    }

    private static String serializeBaseDirs(Collection<File> dirs) {
        if (dirs == null || dirs.isEmpty()) return "";
        return dirs.stream()
                .filter(f -> f != null)
                .map(f -> f.getAbsolutePath())
                .collect(Collectors.joining("|"));
    }

    private static List<File> deserializeBaseDirs(String serialized) {
        List<File> list = new ArrayList<>();
        if (serialized == null || serialized.isBlank()) return list;
        for (String s : serialized.split("\\|")) {
            if (s == null || s.isBlank()) continue;
            File f = new File(s.trim());
            if (f.exists() && f.isDirectory()) {
                list.add(f.getAbsoluteFile());
            }
        }
        return list;
    }
    private static PageFormat create4x6PageFormat(PrinterJob job, boolean landscape) {
        PageFormat pf = job.defaultPage();
        Paper paper = new Paper();
        double inch = 72.0;
        double w = 4 * inch;
        double h = 6 * inch;
        if (landscape) {
            double t = w;
            w = h;
            h = t;
        }
        double topMargin = h * 0.10;
        double sideMargin = 0.10 * inch;
        paper.setSize(w, h);
        paper.setImageableArea(sideMargin, topMargin, w - 2 * sideMargin, h - topMargin - sideMargin);
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
    private List<Path> collectReadyDesignPhotos(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return new ArrayList<>();
        }
        String needle = "(" + orderId.toLowerCase(Locale.ROOT) + ")";
        synchronized (photoIndexLock) {
            if (photoIndex == null || photoIndex.isEmpty()) {
                return new ArrayList<>();
            }
            List<Path> out = new ArrayList<>();
            for (PhotoIndexEntry entry : photoIndex) {
                if (!entry.lowerName().contains(needle)) {
                    continue;
                }
                Path path = entry.path();
                String fileName = path.getFileName().toString();
                boolean isXn = XN_READY_NAME.matcher(fileName).matches();
                boolean inReadyDir = isReadyDesignPath(path);
                if (isXn || inReadyDir) {
                    out.add(path);
                }
            }
            return out;
        }
    }

    private static boolean isReadyDesignPath(Path path) {
        if (path == null) {
            return false;
        }
        Path current = path;
        while (current != null) {
            Path fileName = current.getFileName();
            if (fileName != null) {
                String lower = fileName.toString().toLowerCase(Locale.ROOT);
                if (lower.contains("ready design")) {
                    return true;
                }
                if (lower.startsWith("ready-") && lower.contains("_p")) {
                    return true;
                }
            }
            current = current.getParent();
        }
        return false;
    }
}
