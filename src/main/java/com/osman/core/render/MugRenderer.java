package com.osman.core.render;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Rendering pipeline that transforms Amazon Custom orders into production-ready PNG files.
 */
public final class MugRenderer {
    private MugRenderer() {
    }

    public static List<String> processOrderFolder(File orderDirectory,
                                                  File outputDirectory,
                                                  String customerNameForFile,
                                                  String fileNameSuffix) throws Exception {
        if (!orderDirectory.isDirectory()) {
            throw new IllegalArgumentException("The provided order path is not a directory. " + orderDirectory.getAbsolutePath());
        }
        if (!outputDirectory.isDirectory()) {
            throw new IllegalArgumentException("The specified order path is not recognized as a directory. " + outputDirectory.getAbsolutePath());
        }

        List<File> jsonFiles = findJsonFiles(orderDirectory);
        if (jsonFiles.isEmpty()) {
            throw new IOException("There is No SVG in File " + orderDirectory.getAbsolutePath());
        }

        List<String> outputs = new ArrayList<>();
        for (File jsonFile : jsonFiles) {
            outputs.add(renderFromJson(jsonFile, orderDirectory, outputDirectory, customerNameForFile, fileNameSuffix));
        }
        return outputs;
    }

    public static List<String> processOrderFolderMulti(File orderDirectory,
                                                       File outputDirectory,
                                                       String customerNameForFile,
                                                       String fileNameSuffix) throws Exception {
        return processOrderFolder(orderDirectory, outputDirectory, customerNameForFile, fileNameSuffix);
    }

    private static List<File> findJsonFiles(File directory) throws IOException {
        List<File> jsonFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory.toPath(), 6)) {
            stream.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(f -> f.getName().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .forEach(jsonFiles::add);
        }
        if (jsonFiles.isEmpty()) {
            File singleJson = findFileByExtension(directory, ".json");
            if (singleJson != null) {
                jsonFiles.add(singleJson);
            }
        }
        return jsonFiles;
    }

    private static String renderFromJson(File jsonFile,
                                         File orderRoot,
                                         File outputDirectory,
                                         String customerNameForFile,
                                         String fileNameSuffix) throws Exception {
        try (MugRenderContext context = MugRenderContext.prepare(
            jsonFile,
            orderRoot,
            outputDirectory,
            customerNameForFile,
            fileNameSuffix
        )) {
            return new MugRenderPipeline(context).render();
        }
    }

    private static File findFileByExtension(File directory, String extension) throws IOException {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        final String extLower = extension.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.walk(directory.toPath(), 3)) {
            return stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extLower))
                .findFirst()
                .map(Path::toFile)
                .orElse(null);
        }
    }
}
