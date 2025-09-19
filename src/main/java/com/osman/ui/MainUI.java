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

        JPanel topPanel = new JPanel(new BorderLayout(8, 8));
        JPanel fontRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        chooseFontBtn = new JButton("Choose Font Folder…");
        chooseFontBtn.addActionListener(e -> chooseFontDir());
        fontRow.add(new JLabel("Font folder:"));
        fontRow.add(chooseFontBtn);
        fontPathLabel = new JLabel(shortenPath(fontDirectory, 70));
        fontRow.add(fontPathLabel);
        topPanel.add(fontRow, BorderLayout.NORTH);
        frame.add(topPanel, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        frame.add(scrollPane, BorderLayout.CENTER);

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

                // STEP 1: Normalize selections → unzip first (delete zips) → folders only
                List<File> workItems = preprocessSelectedItems(selected);

                // STEP 2: Process folders as usual
                for (File item : workItems) {
                    if (cancelRequested) break;

                    idx++;
                    publish("");
                    publish("==================================================");
                    publish("[" + idx + "/" + total + "] Processing: " + item.getName());
                    publish("==================================================");

                    if (item.isDirectory()) {
                        handleBaseDirectory(item);
                    } else {
                        publish("Skipped (not a directory): " + item.getName());
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

    // -------------------- ZIP PRE-PROCESSING --------------------

    private File unzipToSiblingFolder(File zipFile) throws java.io.IOException {
        String base = zipFile.getName().replaceAll("(?i)\\.zip$", "");
        File target = new File(zipFile.getParentFile(), base);
        int i = 2;
        while (target.exists()) {
            target = new File(zipFile.getParentFile(), base + " (" + i + ")");
            i++;
        }
        log("  -> Unzipping to: " + target.getAbsolutePath());
        unzip(zipFile, target);
        if (!zipFile.delete()) {
            log("  -> Warning: could not delete zip: " + zipFile.getName());
        } else {
            log("  -> Deleted zip: " + zipFile.getName());
        }
        return target;
    }

    private void preprocessZipsInDirectory(File root) {
        if (root == null || !root.isDirectory()) return;
        boolean found;
        do {
            found = false;
            try (var s = Files.walk(root.toPath(), 8)) {
                List<File> zips = new ArrayList<>();
                s.filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .forEach(f -> {
                            String n = f.getName().toLowerCase(Locale.ROOT);
                            if (n.endsWith(".zip")) zips.add(f);
                        });
                for (File z : zips) {
                    if (cancelRequested) return;
                    found = true;
                    log("  -> Found zip: " + z.getAbsolutePath());
                    try {
                        unzipToSiblingFolder(z);
                    } catch (Exception ex) {
                        log("  -> ERROR unzipping " + z.getName() + ": " + ex.getMessage());
                    }
                }
            } catch (Exception e) {
                log("  -> ERROR scanning for zips under " + root.getAbsolutePath() + ": " + e.getMessage());
                return;
            }
        } while (found);
    }

    private List<File> preprocessSelectedItems(File[] selectedItems) {
        List<File> normalized = new ArrayList<>();
        for (File item : selectedItems) {
            if (cancelRequested) break;

            if (item.isFile() && item.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                log("-> Selected ZIP: " + item.getName() + " (unzip first)");
                try {
                    File extracted = unzipToSiblingFolder(item);
                    // Also expand nested zips inside the extracted folder
                    preprocessZipsInDirectory(extracted);
                    normalized.add(extracted);
                } catch (Exception ex) {
                    log("  -> ERROR unzipping " + item.getName() + ": " + ex.getMessage());
                }
            } else if (item.isDirectory()) {
                // Expand any zips inside this folder (recursively), then use the folder
                preprocessZipsInDirectory(item);
                normalized.add(item);
            } else {
                log("Skipped unsupported file: " + item.getName());
            }
        }
        return normalized;
    }

    // -------------------- FOLDER WORKFLOW --------------------

    private void handleBaseDirectory(File base) {
        File[] customerFolders = base.listFiles(File::isDirectory);

        if (customerFolders != null && customerFolders.length > 0) {
            for (File customerFolder : customerFolders) {
                if (cancelRequested) return;
                if (customerFolder.getName().equalsIgnoreCase("photos")) continue;
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

    // -------------------- LOW-LEVEL HELPERS --------------------

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
