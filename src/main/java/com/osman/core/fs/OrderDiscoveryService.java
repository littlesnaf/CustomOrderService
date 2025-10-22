package com.osman.core.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Locates order folders, JSON definitions, and supporting assets on disk.
 */
public final class OrderDiscoveryService {
    private static final String OUTPUT_FOLDER_NAME = "Ready Designs";
    private static final Comparator<File> FILE_COMPARATOR =
        Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER);

    public List<File> findOrderLeafFolders(File scanRoot, int maxDepth) {
        return discoverOrderFolders(scanRoot, maxDepth).orderFolders();
    }

    public OrderSearchResult discoverOrderFolders(File scanRoot, int maxDepth) {
        LinkedHashSet<File> discovered = new LinkedHashSet<>();
        LinkedHashSet<File> incomplete = new LinkedHashSet<>();
        collectOrderLeafFolders(scanRoot, discovered, incomplete, 0, Math.max(1, maxDepth));
        discovered.remove(scanRoot);
        incomplete.remove(scanRoot);
        List<File> orders = toSortedList(discovered);
        List<File> empty = toSortedList(incomplete);
        return new OrderSearchResult(orders, empty);
    }

    public boolean isOrderFolder(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        String name = dir.getName();
        if (name.equalsIgnoreCase("images") ||
                name.equalsIgnoreCase("img") ||
                isReadyOutputFolderName(name)) {
            return false;
        }
        return containsExtRecursively(dir, ".svg", 3) && containsExtRecursively(dir, ".json", 3);
    }

    private void collectOrderLeafFolders(File dir,
                                         Set<File> validOut,
                                         Set<File> incompleteOut,
                                         int depth,
                                         int maxDepth) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        if (depth > maxDepth) {
            return;
        }

        String dn = dir.getName();
        boolean isContainer = dn.equalsIgnoreCase("images") ||
                dn.equalsIgnoreCase("img") ||
                isReadyOutputFolderName(dn);

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
            if (isReadyOutputFolderName(n)) {
                continue;
            }
            potentialChildren.add(sub);
        }

        boolean discoveredChildOrder = false;
        for (File sub : potentialChildren) {
            if (isOrderFolder(sub)) {
                validOut.add(sub);
                discoveredChildOrder = true;
            } else {
                int beforeValid = validOut.size();
                collectOrderLeafFolders(sub, validOut, incompleteOut, depth + 1, maxDepth);
                if (validOut.size() > beforeValid) {
                    discoveredChildOrder = true;
                }
            }
        }

        if (!discoveredChildOrder && isOrderFolder(dir) && !isContainer) {
            validOut.add(dir);
            discoveredChildOrder = true;
        }

        if (!discoveredChildOrder && looksLikeOrderFolder(dir)) {
            incompleteOut.add(dir);
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

    private static boolean isReadyOutputFolderName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim();
        if (normalized.equalsIgnoreCase(OUTPUT_FOLDER_NAME)) {
            return true;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.startsWith("ready-") && lower.contains("_p");
    }

    private boolean looksLikeOrderFolder(File dir) {
        if (dir == null) {
            return false;
        }
        String name = dir.getName();
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.matches(".*\\d{3}-\\d{7}-\\d{7}.*");
    }

    private List<File> toSortedList(Set<File> files) {
        List<File> list = new ArrayList<>(files);
        list.sort(FILE_COMPARATOR);
        return list;
    }

    public record OrderSearchResult(List<File> orderFolders, List<File> incompleteOrderFolders) {
        public OrderSearchResult {
            orderFolders = List.copyOf(orderFolders);
            incompleteOrderFolders = List.copyOf(incompleteOrderFolders);
        }

        public boolean hasIncompleteFolders() {
            return !incompleteOrderFolders.isEmpty();
        }
    }
}
