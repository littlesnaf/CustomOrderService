package com.osman.core.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Finds photo assets that correspond to an order.
 */
public final class PhotoLocator {
    private static final String[] IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg"};

    public List<Path> findPhotos(File root, String orderId) throws IOException {
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root.toPath(), 4)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> hasImageExtension(p.getFileName().toString()))
                    .filter(p -> orderId == null || p.getFileName().toString().toLowerCase(Locale.ROOT)
                            .contains(orderId.toLowerCase(Locale.ROOT)))
                    .forEach(matches::add);
        }
        return matches;
    }

    private boolean hasImageExtension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
