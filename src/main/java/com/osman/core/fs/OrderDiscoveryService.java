package com.osman.core.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Locates order folders, JSON definitions, and supporting assets on disk.
 */
public final class OrderDiscoveryService {
    private static final String OUTPUT_FOLDER_NAME = "Ready Designs";

    public List<File> findOrderLeafFolders(File scanRoot, int maxDepth) {
        List<File> out = new ArrayList<>();
        collectOrderLeafFolders(scanRoot, out, 0, Math.max(1, maxDepth));
        out.removeIf(f -> f.equals(scanRoot));
        out.sort(Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public boolean isOrderFolder(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        String name = dir.getName();
        if (name.equalsIgnoreCase("images") ||
                name.equalsIgnoreCase("img") ||
                name.equalsIgnoreCase(OUTPUT_FOLDER_NAME)) {
            return false;
        }
        return containsExtRecursively(dir, ".svg", 3) && containsExtRecursively(dir, ".json", 3);
    }

    private void collectOrderLeafFolders(File dir, List<File> out, int depth, int maxDepth) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        if (depth > maxDepth) {
            return;
        }

        String dn = dir.getName();
        boolean isContainer = dn.equalsIgnoreCase("images") ||
                dn.equalsIgnoreCase("img") ||
                dn.equalsIgnoreCase(OUTPUT_FOLDER_NAME);

        File[] subs = dir.listFiles(File::isDirectory);
        if (subs == null) {
            subs = new File[0];
        }

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

    private boolean containsExtRecursively(File dir, String ext, int maxDepth) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        final String extLower = ext.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.walk(dir.toPath(), Math.max(1, maxDepth))) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .filter(Objects::nonNull)
                    .map(Path::toString)
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.endsWith(extLower));
        } catch (IOException e) {
            return false;
        }
    }
}
