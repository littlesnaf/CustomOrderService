package com.osman.ui.labelfinder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class LabelFinderWorkflow {

    private static final Pattern IMG_NAME = Pattern.compile("(?i).+\\s*(?:\\(\\d+\\))?\\s*\\.(png|jpe?g)$");
    private static final Pattern XN_READY_NAME = Pattern.compile("(?i)^x(?:\\(\\d+\\)|\\d+)-.+\\s*(?:\\(\\d+\\))?\\s*\\.(?:png|jpe?g)$");

    private final List<File> baseFolders = new ArrayList<>();
    private final Set<String> photoRefreshInFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Object photoIndexLock = new Object();
    private List<PhotoIndexEntry> photoIndex = new ArrayList<>();
    private Set<Path> photoIndexPaths = new LinkedHashSet<>();

    List<File> baseFolders() {
        return Collections.unmodifiableList(baseFolders);
    }

    boolean hasBaseFolders() {
        return !baseFolders.isEmpty();
    }

    File primaryBaseFolder() {
        return hasBaseFolders() ? baseFolders.get(0) : null;
    }

    void setBaseFolders(Collection<File> roots) {
        baseFolders.clear();
        if (roots == null) {
            return;
        }
        for (File root : roots) {
            if (root == null) {
                continue;
            }
            File normalized = root.getAbsoluteFile();
            if (normalized.isDirectory()) {
                baseFolders.add(normalized);
            }
        }
    }

    List<File> normaliseRoots(Collection<File> inputs) {
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
        if (lower.equals("standard") || lower.equals("expedited") || lower.equals("automated") || lower.equals("manual")) {
            return true;
        }
        return name.chars().allMatch(Character::isDigit);
    }

    boolean tryMarkPhotoRefresh(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return false;
        }
        return photoRefreshInFlight.add(orderId);
    }

    void clearPhotoRefresh(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        photoRefreshInFlight.remove(orderId);
    }

    List<Path> scanPhotosFromDisk(String orderId) throws IOException {
        if (orderId == null || orderId.isBlank() || baseFolders.isEmpty()) {
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
        sorted.sort(Comparator.comparing(p -> Objects.requireNonNull(p.getFileName()).toString(), String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }

    List<Path> addPhotosToIndex(List<Path> candidates) {
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

    List<Path> collectPhotosFromIndex(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Collections.emptyList();
        }
        String needle = orderId.toLowerCase(Locale.ROOT);
        synchronized (photoIndexLock) {
            if (photoIndex == null || photoIndex.isEmpty()) {
                return Collections.emptyList();
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

    List<Path> collectAllPhotosFromIndex() {
        synchronized (photoIndexLock) {
            if (photoIndex == null || photoIndex.isEmpty()) {
                return Collections.emptyList();
            }
            List<Path> out = new ArrayList<>(photoIndex.size());
            for (PhotoIndexEntry entry : photoIndex) {
                out.add(entry.path());
            }
            return out;
        }
    }

    List<Path> collectReadyDesignPhotos(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Collections.emptyList();
        }
        String needle = "(" + orderId.toLowerCase(Locale.ROOT) + ")";
        synchronized (photoIndexLock) {
            if (photoIndex == null || photoIndex.isEmpty()) {
                return Collections.emptyList();
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

    int photoIndexSize() {
        synchronized (photoIndexLock) {
            return photoIndex == null ? 0 : photoIndex.size();
        }
    }

    void rebuildPhotoIndex() throws IOException {
        if (!hasBaseFolders()) {
            clearPhotoIndex();
            return;
        }
        LinkedHashSet<Path> seen = new LinkedHashSet<>();
        for (File root : baseFolders) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root.toPath())) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> IMG_NAME.matcher(p.getFileName().toString()).matches())
                    .forEach(p -> seen.add(p.toAbsolutePath().normalize()));
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

    static String serializeBaseDirs(Collection<File> dirs) {
        if (dirs == null || dirs.isEmpty()) return "";
        return dirs.stream()
            .filter(Objects::nonNull)
            .map(f -> f.getAbsolutePath())
            .collect(Collectors.joining("|"));
    }

    static List<File> deserializeBaseDirs(String serialized) {
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

    private boolean isReadyDesignPath(Path path) {
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

    void clearPhotoIndex() {
        synchronized (photoIndexLock) {
            photoIndex = new ArrayList<>();
            photoIndexPaths = new LinkedHashSet<>();
        }
    }

    private record PhotoIndexEntry(Path path, String lowerName) {
    }
}
