package com.osman.ui;

import com.osman.Config;
import com.osman.FontManager;
import com.osman.ImageProcessor;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainUI {

    private JFrame frame;
    private JTextArea logArea;
    private JButton processButton;
    private JButton chooseFontBtn;
    private JLabel fontPathLabel;
    private JProgressBar progressBar;

    private volatile boolean cancelRequested = false;
    private String fontDirectory;

    public MainUI() {
        fontDirectory = Config.DEFAULT_FONT_DIR;

        frame = new JFrame("Bulk Processor (Folders & Zip)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(980, 720);
        frame.setLayout(new BorderLayout(8, 8));

        // NORTH
        JPanel topPanel = new JPanel(new BorderLayout(8, 8));
        JPanel fontRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        chooseFontBtn = new JButton("Choose Font Folder…");
        chooseFontBtn.addActionListener(e -> chooseFontDir());
        fontRow.add(new JLabel("Font folder:"));
        fontRow.add(chooseFontBtn);
        fontPathLabel = new JLabel(shortenPath(fontDirectory, 70));
        fontRow.add(fontPathLabel);
        topPanel.add(fontRow, BorderLayout.NORTH);

        // CENTER
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        frame.add(scrollPane, BorderLayout.CENTER);

        // SOUTH
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
        frame.add(bottomPanel, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        loadInitialFonts();
    }

    private void chooseFontDir() {
        JFileChooser chooser = new JFileChooser(fontDirectory);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose the folder that contains your fonts");
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File dir = chooser.getSelectedFile();
            if (dir.isDirectory()) {
                fontDirectory = dir.getAbsolutePath();
                fontPathLabel.setText(shortenPath(fontDirectory, 70));
                log("Font folder set to: " + fontDirectory);
                loadInitialFonts();
            }
        }
    }

    private void loadInitialFonts() {
        processButton.setEnabled(false);
        progressBar.setIndeterminate(true);
        log("Scanning font folder: " + fontDirectory);

        new SwingWorker<Integer, String>() {
            @Override
            protected Integer doInBackground() {
                try {
                    return FontManager.loadFontsFromDirectory(fontDirectory);
                } catch (Exception e) {
                    publish("CRITICAL: Fonts could not be loaded.\nReason: " + e.getMessage()
                            + "\nCheck the path: '" + fontDirectory + "' and restart the program.");
                    return -1;
                }
            }

            @Override
            protected void process(List<String> chunks) {
                for (String s : chunks) log(s);
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                try {
                    int count = get();
                    if (count >= 0) {
                        log(count + " fonts loaded successfully.");
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

        cancelRequested = false;
        processButton.setEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setString("Processing…");

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
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
                        File outputDirectory = new File(baseDir, "photos");
                        if (!outputDirectory.exists()) outputDirectory.mkdirs();
                        handleZipFile(item, outputDirectory);
                    } else {
                        publish("Skipped unsupported file: " + item.getName());
                    }
                }
                publish("\n>>> ALL TASKS COMPLETED <<<");
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String s : chunks) log(s);
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                progressBar.setValue(100);
                progressBar.setString("Done");
                processButton.setEnabled(true);
                if (cancelRequested) {
                    log("\n>>> CANCELED BY USER <<<");
                }
            }
        }.execute();
    }

    private void handleBaseDirectory(File base) {
        File[] customerFolders = base.listFiles(File::isDirectory);

        if (customerFolders != null && customerFolders.length > 0) {
            for (File customerFolder : customerFolders) {
                if (cancelRequested) return;
                File outputDirectory = new File(customerFolder, "photos");
                if (!outputDirectory.exists()) outputDirectory.mkdirs();
                handleFolder(customerFolder, outputDirectory, customerFolder.getName());
            }
        } else {
            log("-> No subfolders. Processing the folder itself as a single job: " + base.getName());
            File outputDirectory = new File(base, "photos");
            if (!outputDirectory.exists()) outputDirectory.mkdirs();
            handleFolder(base, outputDirectory, base.getName());
        }
    }

    private void handleFolder(File customerFolder, File outputDirectory, String customerNameForFile) {
        log("\n--- Processing folder: " + customerFolder.getName() + " ---");
        try {
            if (customerFolder.getName().equalsIgnoreCase("photos")) {
                log("  -> Skipped 'photos' folder.");
                return;
            }
            if (isSamePath(customerFolder, outputDirectory)) {
                log("  -> Output folder detected, skipped: " + customerFolder.getName());
                return;
            }

            File scanRoot = new File(customerFolder, "images");
            if (!scanRoot.isDirectory()) {
                scanRoot = new File(customerFolder, "img");
                if (!scanRoot.isDirectory()) scanRoot = customerFolder;
            }

            List<File> leafOrders = findOrderLeafFolders(scanRoot, 6);
            if (!leafOrders.isEmpty()) {
                if (leafOrders.size() > 1) {
                    log("  -> Multiple orders detected (" + leafOrders.size() + " folders).");
                } else {
                    log("  -> Single order folder detected.");
                }
                int ok = 0, fail = 0;

                for (File subFolder : leafOrders) {
                    if (cancelRequested) return;

                    String n = subFolder.getName();
                    if (n.equalsIgnoreCase("photos") || n.equalsIgnoreCase("images") || n.equalsIgnoreCase("img")) {
                        log("    -> Container folder skipped: " + n);
                        continue;
                    }
                    try {
                        List<String> results =
                                ImageProcessor.processOrderFolderMulti(subFolder, outputDirectory, customerNameForFile, null);

                        for (String path : results) {
                            log("    -> OK: " + subFolder.getName() + " -> " + new File(path).getName());
                            ok++;
                        }
                    } catch (Exception ex) {
                        log("    -> ERROR processing " + subFolder.getName() + ": " + ex.getMessage());
                        fail++;
                    }
                }
                log("  -> Summary: " + ok + " succeeded, " + fail + " failed.");
            } else {
                log("  -> No leaf order folder found. Trying the folder itself as MULTI order…");
                try {
                    List<String> results =
                            ImageProcessor.processOrderFolderMulti(customerFolder, outputDirectory, customerNameForFile, null);
                    for (String path : results) {
                        log("  -> OK: " + new File(path).getName());
                    }
                } catch (Exception ex) {
                    log("  -> CRITICAL (" + customerFolder.getName() + "): " + ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log("  -> CRITICAL (" + customerFolder.getName() + "): " + ex.getMessage());
        }
    }

    private void handleZipFile(File zipFile, File outputDirectory) {
        log("\n--- Processing Zip: " + zipFile.getName() + " ---");
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("order-unzip-");
            log("  -> Extracting to temporary folder…");
            unzip(zipFile, tempDir.toFile());

            File unzippedCustomerFolder = findFirstDirectory(tempDir.toFile());
            File processingDirectory = (unzippedCustomerFolder != null) ? unzippedCustomerFolder : tempDir.toFile();

            String customerName = zipFile.getName().replaceAll("(?i)\\.zip$", "");
            handleFolder(processingDirectory, outputDirectory, customerName);

        } catch (Exception ex) {
            log("  -> CRITICAL (" + zipFile.getName() + "): " + ex.getMessage());
        } finally {
            if (tempDir != null) {
                log("  -> Cleaning temporary files: " + zipFile.getName());
                deleteDirectory(tempDir.toFile());
            }
        }
    }

    private void unzip(File zipFile, File destDir) throws java.io.IOException {
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                if (cancelRequested) return;

                File newFile = new File(destDir, zipEntry.getName());
                if (!newFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    throw new java.io.IOException("Zip entry is outside target folder: " + zipEntry.getName());
                }
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new java.io.IOException("Could not create folder: " + newFile);
                    }
                } else {
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new java.io.IOException("Could not create folder: " + parent);
                    }
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
        }
    }

    private void deleteDirectory(File directory) {
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                if (file.isDirectory()) deleteDirectory(file);
                else file.delete();
            }
        }
        directory.delete();
    }

    private File findFirstDirectory(File directory) {
        File[] files = directory.listFiles(f -> f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("__MACOSX"));
        return (files != null && files.length > 0) ? files[0] : null;
    }

    private boolean isSamePath(File a, File b) {
        try {
            return a != null && b != null && a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
    }

    private String shortenPath(String path, int maxChars) {
        if (path == null) return "";
        if (path.length() <= maxChars) return path;
        String head = path.substring(0, Math.max(0, maxChars / 2 - 2));
        String tail = path.substring(path.length() - Math.max(0, maxChars / 2 - 2));
        return head + "…/" + tail;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MainUI::new);
    }

    // ------------ Order folder discovery helpers ------------

    private static List<File> findOrderLeafFolders(File scanRoot, int maxDepth) {
        List<File> out = new ArrayList<>();
        collectOrderLeafFolders(scanRoot, out, 0, Math.max(1, maxDepth));
        out.removeIf(f -> f.equals(scanRoot));
        out.sort(Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static void collectOrderLeafFolders(File dir, List<File> out, int depth, int maxDepth) {
        if (dir == null || !dir.isDirectory()) return;
        if (depth > maxDepth) return;

        String dn = dir.getName();
        boolean isContainer = dn.equalsIgnoreCase("images") || dn.equalsIgnoreCase("img") || dn.equalsIgnoreCase("photos");

        File[] subs = dir.listFiles(File::isDirectory);
        if (subs == null) subs = new File[0];

        List<File> potentialChildren = new ArrayList<>();
        for (File sub : subs) {
            String n = sub.getName();
            if (n.startsWith(".") || n.equalsIgnoreCase("__MACOSX")) {
                continue;
            }
            potentialChildren.add(sub);
        }

        boolean addedChildAsLeaf = false;
        for (File sub : potentialChildren) {
            if (isOrderFolder(sub)) {
                out.add(sub);
                addedChildAsLeaf = true;
            } else {
                collectOrderLeafFolders(sub, out, depth + 1, maxDepth);
            }
        }

        if (!addedChildAsLeaf && isOrderFolder(dir) && !isContainer) {
            if (!out.contains(dir)) {
                out.add(dir);
            }
        }
    }

    private static boolean isOrderFolder(File dir) {
        String name = dir.getName();
        if (name.equalsIgnoreCase("images") || name.equalsIgnoreCase("img") || name.equalsIgnoreCase("photos")) {
            return false;
        }
        return containsExtRecursively(dir, ".svg", 3) && containsExtRecursively(dir, ".json", 3);
    }

    private static boolean containsExtRecursively(File dir, String ext, int maxDepth) {
        if (dir == null || !dir.isDirectory()) return false;
        final String extLower = ext.toLowerCase(Locale.ROOT);
        try (var stream = Files.walk(dir.toPath(), Math.max(1, maxDepth))) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .filter(Objects::nonNull)
                    .map(Path::toString)
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.endsWith(extLower));
        } catch (Exception e) {
            return false;
        }
    }
}
