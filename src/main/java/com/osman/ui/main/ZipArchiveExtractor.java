package com.osman.ui.main;

import com.osman.core.fs.ZipExtractor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class ZipArchiveExtractor {

    private final Consumer<String> log;
    private final List<String> failedItems;
    private final BooleanSupplier cancelRequested;
    private final String outputFolderName;

    ZipArchiveExtractor(Consumer<String> log,
                        List<String> failedItems,
                        BooleanSupplier cancelRequested,
                        String outputFolderName) {
        this.log = log;
        this.failedItems = failedItems;
        this.cancelRequested = cancelRequested;
        this.outputFolderName = outputFolderName;
    }

    /**
     * Scans the provided folder for nested zip files and extracts them in place.
     *
     * @return {@code true} if processing should continue, {@code false} if it was cancelled.
     */
    boolean extract(File rootFolder) {
        List<File> zipFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(rootFolder.toPath(), 6)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                .forEach(p -> zipFiles.add(p.toFile()));
        } catch (IOException e) {
            log.accept("  -> Error scanning for zip files: " + e.getMessage());
            return true;
        }

        if (zipFiles.isEmpty()) {
            return true;
        }

        log.accept("  -> Found " + zipFiles.size() + " zip file(s) inside folder. Extracting...");
        for (File zip : zipFiles) {
            if (cancelRequested.getAsBoolean()) {
                return false;
            }

            File parent = zip.getParentFile();
            if (parent != null && parent.getName().equalsIgnoreCase(outputFolderName)) {
                continue;
            }

            String baseName = zip.getName().replaceAll("(?i)\\.zip$", "");
            File extractDir = new File(parent, baseName);

            if (!extractDir.exists() && !extractDir.mkdirs()) {
                log.accept("  -> ERROR creating folder for zip: " + extractDir.getAbsolutePath());
                continue;
            }

            log.accept("  -> Extracting zip: " + zip.getName());
            try {
                ZipExtractor.unzip(zip, extractDir, listenerFor(zip));
                if (zip.delete()) {
                    log.accept("    -> Extracted and deleted: " + zip.getName());
                } else {
                    log.accept("    -> WARNING: Extracted but could not delete zip: " + zip.getName());
                }
            } catch (ZipExtractionCancelledException cancelled) {
                log.accept("    -> Extraction cancelled for zip: " + zip.getName());
                return false;
            } catch (Exception ex) {
                String errorMsg = "  -> ERROR extracting " + zip.getName() + ": " + ex.getMessage();
                log.accept(errorMsg);
                failedItems.add(zip.getName() + " - Reason: " + ex.getMessage());
            }
        }
        return true;
    }

    ZipExtractor.Listener listenerFor(File zipFile) {
        return new ZipExtractor.Listener() {
            @Override
            public void onFileExtracted(File file) {
                if (cancelRequested.getAsBoolean()) {
                    throw new ZipExtractionCancelledException();
                }
            }

            @Override
            public void onEntrySkipped(String name, String reason) {
                log.accept("    -> SKIPPED entry '" + name + "' (" + zipFile.getName() + "): " + reason);
            }
        };
    }

    static final class ZipExtractionCancelledException extends RuntimeException {
        ZipExtractionCancelledException() {
            super("Zip extraction cancelled");
        }
    }
}
