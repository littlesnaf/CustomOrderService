package com.osman.ui.main;

import com.osman.config.ConfigService;
import com.osman.core.fs.OrderDiscoveryService;
import com.osman.core.fs.ZipExtractor;
import com.osman.core.render.FontRegistry;
import com.osman.core.render.MugRenderer;
import com.osman.integration.amazon.CustomerGroup;
import com.osman.integration.amazon.CustomerOrder;
import com.osman.integration.amazon.CustomerOrderItem;
import com.osman.integration.amazon.ItemTypeCategorizer;
import com.osman.integration.amazon.ItemTypeGroup;
import com.osman.integration.amazon.ShippingLayoutPlanner;
import com.osman.integration.amazon.ShippingLayoutPlanner.MixMetadata;
import com.osman.integration.amazon.ShippingLayoutPlanner.ShippingSpeed;
import com.osman.ui.amazon.AmazonImportPanel;
import com.osman.ui.ornament.OrnamentSkuPanel;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
/**
 * Main desktop UI for batch processing orders (folders & zip files).
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Font directory selection and initial font loading (via {@link FontRegistry}).</li>
 *   <li>Font scanning is handled per-folder to keep setup simple.</li>
 *   <li>Picking base folders and/or .zip files to process.</li>
 *   <li>Extracting zips and discovering "leaf" order folders.</li>
 *   <li>Calling {@link MugRenderer#processOrderFolderMulti(File, File, String, String)}.</li>
 * </ul>
 */
public class MainUIView {

    private static final String OUTPUT_FOLDER_NAME = "Ready Designs";
    private static final int READY_FOLDER_ORDER_LIMIT = 25;
    private final ConfigService configService = ConfigService.getInstance();
    private final OrderDiscoveryService orderDiscoveryService = new OrderDiscoveryService();

    private JFrame frame;
    private JTextArea logArea;
    private JButton processButton;
    private JButton chooseFontDirBtn;
    private JLabel fontPathLabel;
    private JProgressBar progressBar;

    private volatile boolean cancelRequested = false;
    private String fontDirectory;
    private final List<String> failedItems = Collections.synchronizedList(new ArrayList<>());

    /** Launches the window and triggers initial font scan. */
    public MainUIView() {
        fontDirectory = configService.getFontDirectory().toString();

        frame = new JFrame("Bulk Processor (Folders & Zip)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(980, 760);

        JTabbedPane tabbedPane = new JTabbedPane();
        frame.add(tabbedPane, BorderLayout.CENTER);

        JPanel bulkPanel = new JPanel(new BorderLayout(8, 8));
        tabbedPane.addTab("Create Designs", bulkPanel);

        // Top: font rows
        JPanel topPanel = new JPanel(new GridLayout(0, 1, 8, 4));
        bulkPanel.add(topPanel, BorderLayout.NORTH);

        JPanel fontDirRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        chooseFontDirBtn = new JButton("Choose Font Folder…");
        chooseFontDirBtn.addActionListener(e -> chooseFontDir());
        fontDirRow.add(new JLabel("Font folder:"));
        fontDirRow.add(chooseFontDirBtn);
        fontPathLabel = new JLabel(shortenPath(fontDirectory, 70));
        fontDirRow.add(fontPathLabel);
        topPanel.add(fontDirRow);

        // Center: log
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        bulkPanel.add(scrollPane, BorderLayout.CENTER);

        // Bottom: progress + buttons
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 8));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        processButton = new JButton("Select ‘Orders’ Folder or Zip Files…");
        processButton.addActionListener(e -> processSelections());
        processButton.setEnabled(false);
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> cancelRequested = true);
        buttons.add(processButton);
        buttons.add(cancelBtn);

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setMinimum(0);
        progressBar.setMaximum(100);
        progressBar.setValue(0);

        bottomPanel.add(progressBar, BorderLayout.CENTER);
        bottomPanel.add(buttons, BorderLayout.EAST);
        bulkPanel.add(bottomPanel, BorderLayout.SOUTH);

        AmazonImportPanel amazonImportPanel = new AmazonImportPanel();
        tabbedPane.addTab("Download SVGs", amazonImportPanel);

        OrnamentSkuPanel ornamentSkuPanel = new OrnamentSkuPanel();
        tabbedPane.addTab("Ornament Tool", ornamentSkuPanel);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        loadInitialFonts();
    }

    private File resolveScanRoot(File customerFolder) {
        File scanRoot = new File(customerFolder, "images");
        if (!scanRoot.isDirectory()) {
            scanRoot = new File(customerFolder, "img");
            if (!scanRoot.isDirectory()) {
                scanRoot = customerFolder;
            }
        }
        return scanRoot;
    }

    /** Opens a directory chooser to select the font folder. */
    private void chooseFontDir() {
        JFileChooser chooser = new JFileChooser(fontDirectory);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose the folder that contains your fonts");
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File dir = chooser.getSelectedFile();
            if (dir.isDirectory()) {
                fontDirectory = dir.getAbsolutePath();
                configService.setFontDirectory(dir.toPath());
                fontPathLabel.setText(shortenPath(fontDirectory, 70));
                log("Font folder set to: " + fontDirectory);
                loadInitialFonts();
            }
        }
    }

    /** Loads fonts from the selected folder on a background thread. */
    private void loadInitialFonts() {
        processButton.setEnabled(false);
        progressBar.setIndeterminate(true);
        log("Scanning font folder: " + fontDirectory);

        new SwingWorker<Integer, String>() {
            @Override protected Integer doInBackground() {
                try {
                    return FontRegistry.loadFontsFromDirectory(fontDirectory);
                } catch (Exception e) {
                    publish("CRITICAL: Fonts could not be loaded.\nReason: " + e.getMessage()
                            + "\nCheck the path: '" + fontDirectory + "' and restart the program.");
                    return -1;
                }
            }
            @Override protected void process(List<String> chunks) { for (String s : chunks) log(s); }
            @Override protected void done() {
                progressBar.setIndeterminate(false);
                try {
                    int count = get();
                    if (count >= 0) {
                        log(count + " fonts loaded from folder.");
                        log("You can now process your orders.");
                        processButton.setEnabled(true);
                    } else {
                        JOptionPane.showMessageDialog(frame,
                                "Fonts failed to load.\nPlease verify the folder and try again.",
                                "Font Load Error", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ex) {
                    progressBar.setIndeterminate(false);
                    JOptionPane.showMessageDialog(frame,
                            "Unexpected error while loading fonts:\n" + ex.getMessage(),
                            "Font Load Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** Lets the user pick base folders and/or zip files, then processes them. */
    private void processSelections() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose the base ‘Orders’ folder and/or Zip files");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Zip Archives & Folders", "zip"));
        chooser.setAcceptAllFileFilterUsed(true);

        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        File[] selected = chooser.getSelectedFiles();
        if (selected == null || selected.length == 0) return;

        failedItems.clear();
        cancelRequested = false;
        processButton.setEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setString("Processing…");

        new SwingWorker<Void, String>() {
            @Override protected Void doInBackground() {
                int total = selected.length;
                int idx = 0;

                for (File item : selected) {
                    if (cancelRequested) break;

                    idx++;
                    publish("");
                    publish("==================================================");
                    publish("[" + idx + "/" + total + "] Processing: " + item.getName());
                    publish("==================================================");

                    if (item.isDirectory()) {
                        handleBaseDirectory(item);
                    } else if (item.isFile() && item.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        File baseDir = item.getParentFile();
                        File outputDirectory = new File(baseDir, OUTPUT_FOLDER_NAME);
                        if (!outputDirectory.exists()) outputDirectory.mkdirs();
                        handleZipFile(item, outputDirectory);
                    } else {
                        publish("Skipped unsupported file: " + item.getName());
                    }
                }
                publish("\n>>> ALL TASKS COMPLETED <<<");
                return null;
            }
            @Override protected void process(List<String> chunks) { for (String s : chunks) log(s); }
            @Override protected void done() {
                progressBar.setIndeterminate(false);
                progressBar.setValue(100);
                progressBar.setString("Done");
                processButton.setEnabled(true);
                if (cancelRequested) {
                    log("\n>>> CANCELED BY USER <<<");
                }

                if (!failedItems.isEmpty()) {
                    log("\n==========================================");
                    log(">>> ERRORS AT THE END (" + failedItems.size() + ") <<<");
                    log("==========================================");
                    for (String failureMessage : failedItems) {
                        log(" - " + failureMessage);
                    }
                    log("==========================================");
                }
            }
        }.execute();
    }

    /** Processes an “Orders” base directory: iterates subfolders as customers or treats self as a job. */
    private void handleBaseDirectory(File base) {
        File[] customerFolders = base.listFiles(File::isDirectory);

        if (customerFolders != null && customerFolders.length > 0) {
            for (File customerFolder : customerFolders) {
                if (cancelRequested) return;
                File outputDirectory = new File(customerFolder, OUTPUT_FOLDER_NAME);
                handleFolder(customerFolder, outputDirectory, customerFolder.getName());
            }
        } else {
            log("-> No subfolders found. Processing the folder itself as a single job: " + base.getName());
            File outputDirectory = new File(base, OUTPUT_FOLDER_NAME);
            handleFolder(base, outputDirectory, base.getName());
        }
    }

    /**
     * Processes a customer/order folder:
     * <ol>
     *   <li>Extracts inner zip files if any.</li>
     *   <li>Finds leaf order folders.</li>
     *   <li>Calls MugRenderer for each leaf or falls back to current folder.</li>
     * </ol>
     */
    private void handleFolder(File customerFolder, File outputDirectory, String customerNameForFile) {
        log("\n--- Processing folder: " + customerFolder.getName() + " ---");
        try {
            if (customerFolder.getName().equalsIgnoreCase(OUTPUT_FOLDER_NAME)) {
                log("  -> Skipped '" + OUTPUT_FOLDER_NAME + "' folder.");
                return;
            }
            if (isSamePath(customerFolder, outputDirectory)) {
                log("  -> Output folder detected, skipped: " + customerFolder.getName());
                return;
            }

            ZipArchiveExtractor extractor = new ZipArchiveExtractor(
                this::log,
                failedItems,
                () -> cancelRequested,
                OUTPUT_FOLDER_NAME
            );
            boolean continueProcessing = extractor.extract(customerFolder);
            if (!continueProcessing) {
                return;
            }

            if (isContainerFolder(customerFolder)) {
                File[] subfolders = customerFolder.listFiles(File::isDirectory);
                if (subfolders == null || subfolders.length == 0) {
                    log("  -> No subfolders to process under container: " + customerFolder.getName());
                    return;
                }
                for (File subfolder : subfolders) {
                    if (subfolder.getName().equalsIgnoreCase(OUTPUT_FOLDER_NAME)) {
                        continue;
                    }
                    handleFolder(subfolder, new File(subfolder, OUTPUT_FOLDER_NAME), subfolder.getName());
                }
                return;
            }

            File scanRoot = resolveScanRoot(customerFolder);

            List<File> leafOrders = orderDiscoveryService.findOrderLeafFolders(scanRoot, 6);
            ReadyFolderAllocator readyAllocator = new ReadyFolderAllocator(customerFolder, customerNameForFile, READY_FOLDER_ORDER_LIMIT);
            AtomicInteger orderSequence = new AtomicInteger();
            LeafOrderProcessor leafProcessor = new LeafOrderProcessor(() -> cancelRequested, this::log, failedItems, OUTPUT_FOLDER_NAME);

            if (!leafOrders.isEmpty()) {
                LeafOrderProcessor.ProcessingSummary summary = leafProcessor.processLeaves(
                    leafOrders,
                    index -> readyAllocator.folderForOrder(index),
                    orderSequence,
                    customerNameForFile,
                    customerFolder
                );
                log("  -> Summary: " + summary.succeeded() + " succeeded, " + summary.failed() + " failed.");
            } else {
                log("  -> No leaf order folder found. Trying the folder itself as MULTI order…");
                leafProcessor.processAsMulti(
                    customerFolder,
                    index -> readyAllocator.folderForOrder(index),
                    orderSequence,
                    customerNameForFile,
                    customerFolder
                );
            }
        } catch (Exception ex) {
            String errorMsg = "  -> CRITICAL (" + customerFolder.getName() + "): " + ex.getMessage();
            log(errorMsg);
            failedItems.add(customerFolder.getName() + " - Reason: " + ex.getMessage());
        }
    }

    /** Processes a standalone .zip file as if it were an orders container. */
    private void handleZipFile(File zipFile, File outputDirectory) {
        log("\n--- Processing Zip: " + zipFile.getName() + " ---");

        boolean processedOk = false;
        File extractRoot = null;

        ZipArchiveExtractor extractor = new ZipArchiveExtractor(this::log, failedItems, () -> cancelRequested, OUTPUT_FOLDER_NAME);
        LeafOrderProcessor leafProcessor = new LeafOrderProcessor(() -> cancelRequested, this::log, failedItems, OUTPUT_FOLDER_NAME);

        try {
            String baseName = zipFile.getName().replaceAll("(?i)\\.zip$", "");
            extractRoot = new File(zipFile.getParentFile(), baseName);
            if (!extractRoot.exists() && !extractRoot.mkdirs()) {
                throw new RuntimeException("Could not create folder: " + extractRoot.getAbsolutePath());
            }
            log("  -> Extracting to: " + extractRoot.getAbsolutePath());

            ZipExtractor.unzip(zipFile, extractRoot, extractor.listenerFor(zipFile));
            String customerName = baseName;

            File scanRoot = extractRoot;
            List<File> leafOrders = orderDiscoveryService.findOrderLeafFolders(scanRoot, 6);

            ReadyFolderAllocator readyAllocator = new ReadyFolderAllocator(extractRoot, customerName, READY_FOLDER_ORDER_LIMIT);
            AtomicInteger orderSequence = new AtomicInteger();

            if (!leafOrders.isEmpty()) {
                log("  -> " + leafOrders.size() + " order folder(s) found inside the zip.");
                LeafOrderProcessor.ProcessingSummary summary = leafProcessor.processLeaves(
                    leafOrders,
                    index -> readyAllocator.folderForOrder(index),
                    orderSequence,
                    customerName,
                    extractRoot
                );
                log("  -> Summary: " + summary.succeeded() + " succeeded, " + summary.failed() + " failed.");
                processedOk = true;
            } else {
                log("  -> No leaf folder found; trying zip root as MULTI order…");
                processedOk = leafProcessor.processAsMulti(
                    scanRoot,
                    index -> readyAllocator.folderForOrder(index),
                    orderSequence,
                    customerName,
                    extractRoot
                );
            }
        } catch (ZipArchiveExtractor.ZipExtractionCancelledException cancelled) {
            log("  -> Extraction cancelled: " + zipFile.getName());
            return;
        } catch (Exception ex) {
            String errorMsg = "  -> CRITICAL (" + zipFile.getName() + "): " + ex.getMessage();
            log(errorMsg);
            failedItems.add(zipFile.getName() + " - Reason: " + ex.getMessage());
        } finally {
            if (processedOk) {
                if (zipFile.delete()) log("  -> Cleaned up: original zip deleted: " + zipFile.getName());
                else log("  -> WARNING: Zip could not be deleted: " + zipFile.getName());
            } else {
                log("  -> Zip not deleted (processing failed): " + zipFile.getName());
            }
        }
    }

    /** Returns true if both files resolve to the same canonical path. */
    private boolean isSamePath(File a, File b) {
        try {
            return a != null && b != null && a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isContainerFolder(File folder) {
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

    /** Appends a line to the UI log area. */
    private void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
    }

    /** Shortens a long path for display. */
    private String shortenPath(String path, int maxChars) {
        if (path == null) return "";
        if (path.length() <= maxChars) return path;
        String head = path.substring(0, Math.max(0, maxChars / 2 - 2));
        String tail = path.substring(path.length() - Math.max(0, maxChars / 2 - 2));
        return head + "…/" + tail;
    }

    private static final class ReadyFolderAllocator {
        private final File baseDirectory;
        private final String folderToken;
        private final int bucketSize;

        ReadyFolderAllocator(File baseDirectory, String baseName, int bucketSize) {
            this.baseDirectory = baseDirectory;
            this.folderToken = sanitizeBaseName(baseName);
            this.bucketSize = Math.max(1, bucketSize);
        }

        File folderForOrder(int orderIndex) {
            int bucket = Math.max(1, (orderIndex / bucketSize) + 1);
            String folderName = "Ready-" + folderToken + "_P" + bucket;
            File folder = new File(baseDirectory, folderName);
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IllegalStateException("Unable to create ready folder: " + folder.getAbsolutePath());
            }
            return folder;
        }

        private static String sanitizeBaseName(String raw) {
            String value = (raw == null) ? "" : raw.trim();
            if (value.isEmpty()) {
                value = "ORDERS";
            }
            value = value.replaceAll("[^A-Za-z0-9]+", "-");
            value = value.replaceAll("-{2,}", "-");
            value = value.replaceAll("^-|-$", "");
            if (value.isEmpty()) {
                value = "ORDERS";
            }
            return value.toUpperCase(Locale.ROOT);
        }
    }

    /** Entry point. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(MainUIView::new);
    }
}
