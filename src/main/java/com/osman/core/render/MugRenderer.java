package com.osman.core.render;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.osman.core.json.JsonOrderLoader;
import com.osman.core.json.OrderPayload;
import com.osman.core.model.OrderInfo;
import com.osman.core.render.SvgPreprocessor.ProcessedSvg;
import com.osman.core.render.TemplateRegistry.MugTemplate;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        OrderPayload payload = JsonOrderLoader.load(jsonFile, orderRoot);
        OrderInfo orderInfo = payload.info();
        MugTemplate template = TemplateRegistry.forOunces(payload.mugOunces());

        File svgFile = findNearestSvg(jsonFile.getParentFile(), orderRoot);
        if (svgFile == null) {
            throw new IOException("SVG Couldn't Find: " + jsonFile.getAbsolutePath());
        }

        String baseName = deriveOutputBaseName(orderRoot, outputDirectory, customerNameForFile, orderInfo);
        String suffix = (fileNameSuffix == null) ? "" : fileNameSuffix;
        String finalBaseName = "x" + orderInfo.getQuantity() + "-" + baseName + "(" + orderInfo.getOrderId() + ") " + suffix;
        File finalOutputFile = ensureUniqueFile(outputDirectory, finalBaseName.trim(), ".png");

        ProcessedSvg processedSvg = SvgPreprocessor.preprocess(svgFile, orderInfo, outputDirectory);

        BufferedImage finalCanvas = new BufferedImage(template.finalWidth, template.finalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = finalCanvas.createGraphics();
        setupHighQualityRendering(g2d);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, finalCanvas.getWidth(), finalCanvas.getHeight());

        boolean drawLeft = !"BACK_ONLY".equals(payload.designSide());
        boolean drawRight = !"FRONT_ONLY".equals(payload.designSide());

        File tempMaster = null;
        try {
            tempMaster = File.createTempFile("temp_master_", ".png", outputDirectory);
            convertSvgToHighResPng(processedSvg.content(),
                    svgFile.getParentFile(),
                    tempMaster.getAbsolutePath(),
                    template.renderSize,
                    template.renderSize);

            drawCropsToCanvas(g2d, tempMaster, outputDirectory, drawLeft, drawRight, template);
            drawMirroredInfoText(g2d, orderInfo, null, template, payload.totalQuantity());
        } finally {
            deleteIfExists(tempMaster);
            processedSvg.tempFiles().forEach(MugRenderer::deleteIfExists);
        }

        g2d.dispose();
        ImageIO.write(finalCanvas, "png", finalOutputFile);
        return finalOutputFile.getAbsolutePath();
    }

    private static void drawCropsToCanvas(Graphics2D g2d,
                                          File masterFile,
                                          File tempDir,
                                          boolean drawLeft,
                                          boolean drawRight,
                                          MugTemplate template) {
        File crop1 = null;
        File crop2 = null;
        try {
            crop1 = File.createTempFile("crop1_", ".png", tempDir);
            crop2 = File.createTempFile("crop2_", ".png", tempDir);

            cropImage(masterFile.getAbsolutePath(), crop1.getAbsolutePath(),
                    template.crop1X, template.crop1Y, template.crop1Width, template.crop1Height);
            cropImage(masterFile.getAbsolutePath(), crop2.getAbsolutePath(),
                    template.crop2X, template.crop2Y, template.crop2Width, template.crop2Height);

            if (drawLeft) {
                g2d.drawImage(ImageIO.read(crop1), template.area1X, template.area1Y, template.area1Width, template.area1Height, null);
            } else {
                g2d.setColor(Color.WHITE);
                g2d.fillRect(template.area1X, template.area1Y, template.area1Width, template.area1Height);
            }

            if (drawRight) {
                g2d.drawImage(ImageIO.read(crop2), template.area2X, template.area2Y, template.area2Width, template.area2Height, null);
            } else {
                g2d.setColor(Color.WHITE);
                g2d.fillRect(template.area2X, template.area2Y, template.area2Width, template.area2Height);
            }
        } catch (IOException e) {
            System.err.println("Crop or Draw error: " + e.getMessage());
        } finally {
            deleteIfExists(crop1);
            deleteIfExists(crop2);
        }
    }

    private static void drawMirroredInfoText(Graphics2D g2d,
                                             OrderInfo orderInfo,
                                             String sideLabel,
                                             MugTemplate template,
                                             int totalOrderQuantity) {
        Font infoFont = new Font("Arial", Font.BOLD, 48);
        Font orderIdFont = infoFont.deriveFont(infoFont.getSize2D() * 1.3f);
        g2d.setColor(Color.BLACK);

        int infoBoxY = template.finalHeight - 190;
        int infoBoxX = 158;
        int infoBoxWidth = 2330;
        int infoBoxHeight = 146;

        FontMetrics fm = g2d.getFontMetrics(infoFont);
        int lineHeight = fm.getHeight();
        int baselineCenter = infoBoxY + (infoBoxHeight - (2 * lineHeight)) / 2 + fm.getAscent() - 30;
        int ascent = fm.getAscent();
        int descent = fm.getDescent();
        int correction = (ascent - descent) / 2;
        int alignmentOffset = -8;

        int yLine1 = baselineCenter - correction + alignmentOffset;
        int yLine2 = yLine1 + lineHeight - 25;
        int yLine2OrderId = yLine2 + 20;
        int yRightLine1 = yLine1;
        int yRightLine2 = yLine2 + 20;

        String qtyPart = "Order Q-ty: " + totalOrderQuantity;
        String sidePart = (sideLabel == null || sideLabel.isBlank()) ? "" : " " + sideLabel;
        drawMirroredString(g2d, qtyPart + sidePart, infoBoxX, yLine1, infoFont, false);
        drawMirroredString(g2d, orderInfo.getOrderId(), infoBoxX, yLine2OrderId, orderIdFont, false);

        int rightEdge = infoBoxX + infoBoxWidth;
        String orderItemId = orderInfo.getOrderItemId() == null ? "" : orderInfo.getOrderItemId();
        String itemIdLast4 = orderItemId.length() <= 4 ? orderItemId : orderItemId.substring(orderItemId.length() - 4);
        drawMirroredString(g2d,
                "ID: " + itemIdLast4 + "  ID Qty: " + orderInfo.getQuantity(),
                rightEdge,
                yRightLine1,
                infoFont,
                true);
        drawMirroredString(g2d, orderInfo.getLabel(), rightEdge, yRightLine2, infoFont, true);

        String barcodePayload = buildBarcodePayload(orderInfo);
        BufferedImage barcode = generateBarcodeWithText(barcodePayload, 1000, 120);
        int barcodeX = infoBoxX + (infoBoxWidth - barcode.getWidth()) / 2;
        int barcodeCenterY = infoBoxY + infoBoxHeight / 2;
        int barcodeY = barcodeCenterY - barcode.getHeight() / 2;
        g2d.drawImage(barcode, barcodeX, barcodeY, null);
    }

    private static void drawMirroredString(Graphics2D g2d, String text, int x, int y, Font font, boolean alignRight) {
        AffineTransform original = g2d.getTransform();
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int drawX = alignRight ? (x - textWidth) : x;
        int baselineY = y;
        g2d.translate(drawX, baselineY);
        g2d.scale(1, -1);
        g2d.drawString(text, 0, 0);
        g2d.setTransform(original);
    }

    private static String buildBarcodePayload(OrderInfo orderInfo) {
        if (orderInfo == null) {
            return "";
        }
        String orderId = orderInfo.getOrderId();
        if (orderId == null) {
            orderId = "";
        }
        String suffix = reduceOrderItemId(orderInfo.getOrderItemId());
        if (suffix == null || suffix.isBlank()) {
            return orderId;
        }
        return orderId + "^" + suffix;
    }

    private static String reduceOrderItemId(String orderItemId) {
        if (orderItemId == null) {
            return null;
        }
        String trimmed = orderItemId.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= 6) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - 6);
    }

    private static BufferedImage generateBarcodeWithText(String text, int width, int height) {
        try {
            Code128Writer writer = new Code128Writer();
            BitMatrix matrix = writer.encode(text, BarcodeFormat.CODE_128, width, height);
            BufferedImage code = MatrixToImageWriter.toBufferedImage(matrix);

            Font textFont = new Font("Arial", Font.PLAIN, 20);
            BufferedImage temp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gTmp = temp.createGraphics();
            gTmp.setFont(textFont);
            FontMetrics fm = gTmp.getFontMetrics();
            int textHeight = fm.getHeight();
            gTmp.dispose();

            int combinedH = height + textHeight;
            BufferedImage out = new BufferedImage(width, combinedH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, combinedH);
            g.setColor(Color.BLACK);
            g.setFont(textFont);

            g.drawImage(code, 0, 0, null);
            int textWidth = fm.stringWidth(text);
            g.drawString(text, (width - textWidth) / 2, height + fm.getAscent() - 3);
            g.dispose();
            return out;
        } catch (Exception e) {
            BufferedImage err = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = err.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.RED);
            g.drawString("BC-ERR", 10, 20);
            g.dispose();
            return err;
        }
    }

    private static void setupHighQualityRendering(Graphics2D g2d) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static void convertSvgToHighResPng(String svgContent,
                                               File baseDirectory,
                                               String outputPath,
                                               float targetWidth,
                                               float targetHeight) throws IOException, TranscoderException {
        try (FileOutputStream out = new FileOutputStream(outputPath)) {
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, targetWidth);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, targetHeight);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, new Color(0, 0, 0, 0));
            transcoder.addTranscodingHint(PNGTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, true);
            TranscoderInput input = new TranscoderInput(new StringReader(svgContent));
            input.setURI(baseDirectory.toURI().toString());
            TranscoderOutput output = new TranscoderOutput(out);
            transcoder.transcode(input, output);
            out.flush();
        }
    }

    private static void cropImage(String sourcePath, String outputPath, int x, int y, int width, int height) throws IOException {
        BufferedImage sourceImage = ImageIO.read(new File(sourcePath));
        if (sourceImage == null) {
            throw new IOException("Image couldn't read for crop : " + sourcePath);
        }
        BufferedImage cropped = sourceImage.getSubimage(x, y, width, height);
        ImageIO.write(cropped, "png", new File(outputPath));
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
            if (!folderCandidate.equalsIgnoreCase("images") &&
                    !folderCandidate.equalsIgnoreCase("img") &&
                    !folderCandidate.equalsIgnoreCase("photos")) {
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

    private static String deriveNameFromPhotos(File orderDirectory, String orderId) throws IOException {
        try (Stream<Path> stream = Files.walk(orderDirectory.toPath(), 3)) {
            Optional<String> name = stream.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .map(File::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png")
                            && n.toLowerCase(Locale.ROOT).contains(orderId.toLowerCase(Locale.ROOT)))
                    .map(MugRenderer::extractNameAroundOrderId)
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
            String name = right;
            if (left.equalsIgnoreCase("images")) {
                return sanitizeName(name);
            }
            return sanitizeName(name);
        } else if (!left.isBlank()) {
            String name = left;
            if (name.equalsIgnoreCase("images")) {
                return null;
            }
            return sanitizeName(name);
        }
        return null;
    }

    private static String trimDelims(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^[ _-]+|[ _-]+$", "");
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

    private static void deleteIfExists(File f) {
        if (f == null) {
            return;
        }
        try {
            Files.deleteIfExists(f.toPath());
        } catch (IOException ignored) {
        }
    }
}
