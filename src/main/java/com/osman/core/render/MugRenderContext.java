package com.osman.core.render;

import com.osman.core.json.JsonOrderLoader;
import com.osman.core.json.OrderPayload;
import com.osman.core.model.OrderInfo;
import com.osman.core.render.TemplateRegistry.MugTemplate;
import com.osman.logging.AppLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MugRenderContext implements AutoCloseable {



    private static final Logger LOGGER = AppLogger.get();

    private final OrderPayload payload;
    private final OrderInfo orderInfo;
    private final MugTemplate template;
    private final File svgFile;
    private final Set<String> declaredImageNames;
    private final File finalOutputFile;
    private final File tempWorkingDir;
    private final boolean drawLeft;
    private final boolean drawRight;

    private MugRenderContext(OrderPayload payload,
                             OrderInfo orderInfo,
                             MugTemplate template,
                             File svgFile,
                             Set<String> declaredImageNames,
                             File finalOutputFile,
                             File tempWorkingDir,
                             boolean drawLeft,
                             boolean drawRight) {
        this.payload = payload;
        this.orderInfo = orderInfo;
        this.template = template;
        this.svgFile = svgFile;
        this.declaredImageNames = declaredImageNames;
        this.finalOutputFile = finalOutputFile;
        this.tempWorkingDir = tempWorkingDir;
        this.drawLeft = drawLeft;
        this.drawRight = drawRight;
    }

    static MugRenderContext prepare(File jsonFile,
                                    File orderRoot,
                                    File outputDirectory,
                                    String customerNameForFile,
                                    String fileNameSuffix) throws Exception {
        OrderPayload payload = JsonOrderLoader.load(jsonFile, orderRoot);
        OrderInfo orderInfo = payload.info();
        MugTemplate template = TemplateRegistry.forOunces(payload.mugOunces());

        Files.createDirectories(outputDirectory.toPath());

        File svgFile = findNearestSvg(jsonFile.getParentFile(), orderRoot);
        if (svgFile == null) {
            throw new IOException("SVG Couldn't Find: " + jsonFile.getAbsolutePath());
        }

        try {
            verifyDeclaredImageAssets(payload, svgFile, orderRoot);
        } catch (IOException e) {
            String message = "Order %s is missing declared image assets: %s"
                .formatted(orderInfo.getOrderId(), e.getMessage());
            LOGGER.log(Level.SEVERE, message, e);
            throw new IOException(message, e);
        }

        Set<String> declaredImageNames = determineDeclaredImageNames(payload);

        String baseName = deriveOutputBaseName(orderRoot, outputDirectory, customerNameForFile, orderInfo);
        String suffix = (fileNameSuffix == null) ? "" : fileNameSuffix;
        String finalBaseName = ("x" + orderInfo.getQuantity() + "-" + baseName + "(" + orderInfo.getOrderId() + ") " + suffix).trim();
        File finalOutputFile = ensureUniqueFile(outputDirectory, finalBaseName, ".png");

        File tempWorkingDir = Files.createTempDirectory(outputDirectory.toPath(), ".render-").toFile();

        boolean drawLeft = !"BACK_ONLY".equals(payload.designSide());
        boolean drawRight = !"FRONT_ONLY".equals(payload.designSide());

        return new MugRenderContext(
            payload,
            orderInfo,
            template,
            svgFile,
            declaredImageNames,
            finalOutputFile,
            tempWorkingDir,
            drawLeft,
            drawRight
        );
    }

    OrderPayload payload() {
        return payload;
    }

    OrderInfo orderInfo() {
        return orderInfo;
    }

    MugTemplate template() {
        return template;
    }

    File svgFile() {
        return svgFile;
    }

    Set<String> declaredImageNames() {
        return declaredImageNames;
    }

    File finalOutputFile() {
        return finalOutputFile;
    }

    File tempWorkingDir() {
        return tempWorkingDir;
    }

    boolean drawLeft() {
        return drawLeft;
    }

    boolean drawRight() {
        return drawRight;
    }

    int totalOrderQuantity() {
        return payload.totalQuantity();
    }

    @Override
    public void close() {
        deleteDirectoryQuietly(tempWorkingDir);
    }

    private static String deriveOutputBaseName(File orderRoot,
                                               File outputDir,
                                               String customerName,
                                               OrderInfo info) {
        String baseName = null;
        try {
            baseName = deriveNameFromPhotos(orderRoot, info.getOrderId());
        } catch (IOException ignored) {
        }
        if (isBlank(baseName)) {
            String folderCandidate = sanitizeName(orderRoot.getName());
            if (!folderCandidate.equalsIgnoreCase("images")
                && !folderCandidate.equalsIgnoreCase("img")
                && !folderCandidate.equalsIgnoreCase("photos")) {
                baseName = folderCandidate;
            }
        }
        if (isBlank(baseName) && customerName != null) {
            baseName = sanitizeName(customerName);
        }
        if (isBlank(baseName)) {
            baseName = sanitizeName(outputDir.getName());
        }
        return baseName;
    }

    private static String sanitizeName(String value) {
        if (value == null) {
            return "";
        }
        String out = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        out = out.replaceAll("__+", "_");
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String deriveNameFromPhotos(File orderDirectory, String orderId) throws IOException {
        if (orderDirectory == null || orderId == null) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(orderDirectory.toPath(), 3)) {
            Optional<String> name = stream.filter(Files::isRegularFile)
                .map(Path::toFile)
                .map(File::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png")
                    && n.toLowerCase(Locale.ROOT).contains(orderId.toLowerCase(Locale.ROOT)))
                .map(MugRenderContext::extractNameAroundOrderId)
                .filter(s -> s != null && !s.isBlank())
                .findFirst();
            return name.orElse(null);
        }
    }

    private static String extractNameAroundOrderId(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String orderIdRegex = "\\d{3}-\\d{7}-\\d{7}";
        Pattern pattern = Pattern.compile("(.*?)(?:[ _-])?(" + orderIdRegex + ")(?:[ _-])?(.*)");
        Matcher matcher = pattern.matcher(base);
        if (!matcher.matches()) {
            return null;
        }

        String left = trimDelims(matcher.group(1).trim());
        String right = trimDelims(matcher.group(3).trim());
        if (!right.isBlank()) {
            return sanitizeName(right);
        } else if (!left.isBlank()) {
            if (left.equalsIgnoreCase("images")) {
                return null;
            }
            return sanitizeName(left);
        }
        return null;
    }

    private static String trimDelims(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^[ _-]+|[ _-]+$", "");
    }

    private static File ensureUniqueFile(File dir, String baseNameNoExt, String ext) {
        File candidate = new File(dir, baseNameNoExt + ext);
        if (!candidate.exists()) {
            return candidate;
        }
        int i = 2;
        while (true) {
            File alt = new File(dir, baseNameNoExt + " (" + i + ")" + ext);
            if (!alt.exists()) {
                return alt;
            }
            i++;
        }
    }

    private static void verifyDeclaredImageAssets(OrderPayload payload,
                                                  File svgFile,
                                                  File orderRoot) throws IOException {
        if (payload == null || payload.images() == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        File svgParent = (svgFile == null) ? orderRoot : svgFile.getParentFile();
        JsonOrderLoader.ImageFileInfo images = payload.images();
        collectMissingImage(images.frontImageFile(), "front image", svgParent, orderRoot, missing);
        collectMissingImage(images.backImageFile(), "back image", svgParent, orderRoot, missing);
        if (!missing.isEmpty()) {
            throw new IOException(String.join(", ", missing));
        }
    }

    private static Set<String> determineDeclaredImageNames(OrderPayload payload) {
        Set<String> names = new HashSet<>();
        if (payload == null || payload.images() == null) {
            return names;
        }
        String designSide = payload.designSide();
        boolean allowFront = designSide == null || !"BACK_ONLY".equalsIgnoreCase(designSide);
        boolean allowBack = designSide == null || !"FRONT_ONLY".equalsIgnoreCase(designSide);
        String front = payload.images().frontImageFile();
        String back = payload.images().backImageFile();
        if (allowFront && front != null && !front.isBlank()) {
            names.add(front);
        }
        if (allowBack && back != null && !back.isBlank()) {
            names.add(back);
        }
        return names;
    }

    private static void collectMissingImage(String assetName,
                                            String label,
                                            File svgParent,
                                            File orderRoot,
                                            List<String> missing) throws IOException {
        if (assetName == null || assetName.isBlank() || isRemoteReference(assetName)) {
            return;
        }
        if (!assetExists(svgParent, orderRoot, assetName)) {
            String parentDesc = (svgParent != null) ? svgParent.getAbsolutePath() : "<unknown>";
            String orderDesc = (orderRoot != null) ? orderRoot.getAbsolutePath() : "<unknown>";
            missing.add("%s '%s' not found under %s or %s".formatted(label, assetName, parentDesc, orderDesc));
        }
    }

    private static boolean assetExists(File svgParent, File orderRoot, String assetName) throws IOException {
        if (svgParent != null) {
            File direct = new File(svgParent, assetName);
            if (direct.exists()) {
                return true;
            }
        }
        if (orderRoot != null) {
            File rootDirect = new File(orderRoot, assetName);
            if (rootDirect.exists()) {
                return true;
            }
            try (Stream<Path> stream = Files.walk(orderRoot.toPath(), 4)) {
                final String needle = assetName.toLowerCase(Locale.ROOT);
                return stream.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).equals(needle));
            }
        }
        return false;
    }

    private static boolean isRemoteReference(String href) {
        String lower = href.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static File findNearestSvg(File startDir, File rootFallback) {
        File result = findFileByExtension(startDir, ".svg");
        if (result != null) {
            return result;
        }
        return findFileByExtension(rootFallback, ".svg");
    }

    private static File findFileByExtension(File directory, String extension) {
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
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteDirectoryQuietly(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
