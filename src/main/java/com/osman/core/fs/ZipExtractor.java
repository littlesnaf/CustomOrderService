package com.osman.core.fs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts zip archives with protection against Zip Slip attacks.
 */
public final class ZipExtractor {
    public interface Listener {
        void onFileExtracted(File file);
        void onEntrySkipped(String name, String reason);
    }

    private ZipExtractor() {
    }

    public static void unzip(File zipFile, File destDir, Listener listener) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("Could not create folder: " + destDir);
        }
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("__MACOSX/") || entry.getName().contains("/._")) {
                    continue;
                }
                File newFile = new File(destDir, entry.getName());
                String destPath = destDir.getCanonicalPath() + File.separator;
                String newPath = newFile.getCanonicalPath();
                if (!newPath.startsWith(destPath)) {
                    if (listener != null) {
                        listener.onEntrySkipped(entry.getName(), "Zip entry outside target folder");
                    }
                    continue;
                }

                if (entry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        if (listener != null) {
                            listener.onEntrySkipped(entry.getName(), "Could not create folder");
                        }
                    }
                } else {
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        if (listener != null) {
                            listener.onEntrySkipped(entry.getName(), "Could not create folder");
                        }
                        continue;
                    }
                    try (InputStream is = zf.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                        if (listener != null) {
                            listener.onFileExtracted(newFile);
                        }
                    }
                }
            }
        }
    }
}
