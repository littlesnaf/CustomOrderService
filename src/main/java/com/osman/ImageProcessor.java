package com.osman;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;



public class ImageProcessor {

    // =================================================================================
    //(Constants)
    // =================================================================================

    static final String BLANK_LOGO_URL = "https://m.media-amazon.com/images/S/";
    static final String TRANSPARENT_PIXEL_DATA_URI = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";



    private static final String[] JAVA_LOGICAL_FALLBACKS = new String[] { "SansSerif", "Dialog" };

    // =================================================================================
    // // (Public API)
    // =================================================================================

    /**
     * Processes every JSON order inside the provided directory and renders production PNGs.
     * @return absolute file paths of the rendered outputs.
     */
    public static List<String> processOrderFolderMulti(File orderDirectory,
                                                       File outputDirectory,
                                                       String customerNameForFile,
                                                       String fileNameSuffix) throws Exception {
        if (!orderDirectory.isDirectory())
            throw new IllegalArgumentException("The provided order path is not a directory. " + orderDirectory.getAbsolutePath());
        if (!outputDirectory.isDirectory())
            throw new IllegalArgumentException("The specified order path is not recognized as a directory. " + outputDirectory.getAbsolutePath());
        List<File> jsonFiles = findJsonFiles(orderDirectory);
        if (jsonFiles.isEmpty())
            throw new IOException("There is No SVG in File " + orderDirectory.getAbsolutePath());

        List<String> outputs = new ArrayList<>();
        for (File jsonFile : jsonFiles) {
            outputs.add(renderFromJson(jsonFile, orderDirectory, outputDirectory, customerNameForFile, fileNameSuffix));
        }
        return outputs;
    }

    // =================================================================================
   // (Core Rendering Pipeline)
    // =================================================================================

    /** Core pipeline that reads JSON metadata, rewrites the SVG, composites assets, and exports PNG. */
    private static String renderFromJson(File jsonFile,
                                         File orderRoot,
                                         File outputDirectory,
                                         String customerNameForFile,
                                         String fileNameSuffix) throws Exception {



        OrderInfo orderInfo = JsonDataReader.parse(jsonFile, orderRoot);
        int totalOrderQuantity = JsonDataReader.calculateTotalOrderQuantity(orderRoot);
        File svgFile = findNearestSvg(jsonFile.getParentFile(), orderRoot);
        if (svgFile == null) throw new IOException("SVG Couldn't Find: " + jsonFile.getAbsolutePath());
        int mugOz = JsonDataReader.readMugOunces(jsonFile);   // 11 or 15
        MugTemplate T = (mugOz == 15) ? MugTemplate.OZ15() : MugTemplate.OZ11();
        String baseName = deriveOutputBaseName(orderRoot, outputDirectory, customerNameForFile, orderInfo);
        String finalBaseName = "x" + orderInfo.getQuantity() + "-" +baseName + "(" + orderInfo.getOrderId() + ") ";
        File finalOutputFile = ensureUniqueFile(outputDirectory, finalBaseName, ".png");

        ProcessedSvgResult processedSvgResult = preProcessAndRewriteSvg(svgFile, orderInfo, outputDirectory);

        BufferedImage finalCanvas = new BufferedImage(T.FINAL_WIDTH, T.FINAL_HEIGHT, BufferedImage.TYPE_INT_RGB);

        Graphics2D g2d = finalCanvas.createGraphics();
        setupHighQualityRendering(g2d);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, finalCanvas.getWidth(), finalCanvas.getHeight());

        String designSide = JsonDataReader.readDesignSide(jsonFile); // "BOTH" | "FRONT_ONLY" | "BACK_ONLY"
        boolean drawLeft  = !"BACK_ONLY".equals(designSide);   // FRONT
        boolean drawRight = !"FRONT_ONLY".equals(designSide);  // BACK
        File tempMaster = null;
        try {
            tempMaster = File.createTempFile("temp_master_", ".png", outputDirectory);
            convertSvgToHighResPng(processedSvgResult.svgContent,
                    svgFile.getParentFile(), tempMaster.getAbsolutePath(), T.RENDER_SIZE, T.RENDER_SIZE);
            ;

            drawCropsToCanvas(g2d, tempMaster, outputDirectory, drawLeft, drawRight, T);



            drawMirroredInfoText(g2d, orderInfo, null, T, totalOrderQuantity);
        } finally {
            deleteIfExists(tempMaster);
            processedSvgResult.tempFiles.forEach(ImageProcessor::deleteIfExists);
        }

        g2d.dispose();
        ImageIO.write(finalCanvas, "png", finalOutputFile);
        return finalOutputFile.getAbsolutePath();
    }

    private record ProcessedSvgResult(String svgContent, List<File> tempFiles) {}

    /**
     * Injects personalization data and sanitizes embedded images before the SVG is rasterized.
     * Temporary files for sanitized assets are tracked for later cleanup.
     */
    private static ProcessedSvgResult preProcessAndRewriteSvg(File svgFile, OrderInfo orderInfo, File tempDir) throws IOException {
        String content = Files.readString(svgFile.toPath());
        content = content.replace("FONT_PLACEHOLDER", orderInfo.getFontName());
        if (content.contains(BLANK_LOGO_URL)) {
            content = content.replace(BLANK_LOGO_URL, TRANSPARENT_PIXEL_DATA_URI);
        }

        Pattern p = Pattern.compile("<image\\b([^>]*?)(?:xlink:href|href)=\"([^\"]+)\"([^>]*)>");
        Matcher m = p.matcher(content);
        List<File> tempFiles = new ArrayList<>();
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String href = m.group(2);
            if (href.startsWith("data:")) {
                m.appendReplacement(sb, m.group(0));
                continue;
            }

            File originalImageFile = new File(svgFile.getParentFile(), href);
            BufferedImage sanitizedImage = readWithImageMagick(originalImageFile);

            if (sanitizedImage != null) {
                File tempPngFile = File.createTempFile("magick_", ".png", tempDir);
                ImageIO.write(sanitizedImage, "png", tempPngFile);
                tempFiles.add(tempPngFile);
                String newHref = tempPngFile.toURI().toString();
                String replacement = "<image" + m.group(1) + "xlink:href=\"" + newHref + "\"" + m.group(3) + ">";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                String replacement = "<image" + m.group(1) + "xlink:href=\"" + TRANSPARENT_PIXEL_DATA_URI + "\"" + m.group(3) + ">";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        }
        m.appendTail(sb);

        String withFallback = addJavaFallbackFonts(sb.toString());

        return new ProcessedSvgResult(withFallback, tempFiles);
    }

    /** Calls ImageMagick to normalize the source image (orientation, color space, alpha) before use. */
    private static BufferedImage readWithImageMagick(File imageFile) {
        if (imageFile == null || !imageFile.exists()) {
            System.err.println(" No source for ImageMagick " + imageFile);
            return null;
        }
        try {
            File tempFile = File.createTempFile("magick_out_", ".png");
            tempFile.deleteOnExit();

            String outputFormat = "PNG32:" + tempFile.getAbsolutePath(); // 8-bit RGBA
            ProcessBuilder pb = new ProcessBuilder(
                    "magick",
                    imageFile.getAbsolutePath(),
                    "-auto-orient",
                    "-colorspace", "sRGB",
                    "-alpha", "on",
                    "-depth", "8",
                    "-define", "png:color-type=6",
                    "-define", "png:bit-depth=8",
                    "-strip",
                    outputFormat
            );
            Process process = pb.start();
            try (var in = process.getInputStream()) { in.readAllBytes(); } catch (Exception ignored) {}
            int exitCode = process.waitFor();

            if (exitCode == 0 && tempFile.exists() && tempFile.length() > 0) {
                return ImageIO.read(tempFile);
            } else {
                System.err.println("ImageMagick failed. File: " + imageFile.getName() + ", Output Code: " + exitCode);
                return null;
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("ImageMagick call error: " + e.getMessage());
            return null;
        }
    }


    /**
     * Crops the master render into left/right panels and paints them into the final canvas.
     * Missing sides are filled with white so single-sided designs still look correct.
     */
    private static void drawCropsToCanvas(Graphics2D g2d,
                                          File masterFile,
                                          File tempDir,
                                          boolean drawLeft,
                                          boolean drawRight,
                                          MugTemplate T) {
        File crop1 = null, crop2 = null;
        try {
            crop1 = File.createTempFile("crop1_", ".png", tempDir);
            crop2 = File.createTempFile("crop2_", ".png", tempDir);

            cropImage(masterFile.getAbsolutePath(), crop1.getAbsolutePath(),
                    T.crop1X, T.crop1Y, T.crop1Width, T.crop1Height);
            cropImage(masterFile.getAbsolutePath(), crop2.getAbsolutePath(),
                    T.crop2X, T.crop2Y, T.crop2Width, T.crop2Height);

            if (drawLeft) {
                g2d.drawImage(ImageIO.read(crop1), T.area1X, T.area1Y, T.area1Width, T.area1Height, null);
            } else {
                g2d.setColor(Color.WHITE);
                g2d.fillRect(T.area1X, T.area1Y, T.area1Width, T.area1Height);
            }

            if (drawRight) {
                g2d.drawImage(ImageIO.read(crop2), T.area2X, T.area2Y, T.area2Width, T.area2Height, null);
            } else {
                g2d.setColor(Color.WHITE);
                g2d.fillRect(T.area2X, T.area2Y, T.area2Width, T.area2Height);
            }

        } catch (IOException e) {
            System.err.println("Crop or Draw error: " + e.getMessage());
        } finally {
            deleteIfExists(crop1);
            deleteIfExists(crop2);
        }
    }


    /** Quietly deletes a temporary file if it was created. */
    private static void deleteIfExists(File f) {
        if (f != null) { try { Files.deleteIfExists(f.toPath()); } catch (IOException e) { /* ignore */ } }
    }

    /** Collects JSON definition files from the order directory, looking several levels deep. */
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
            if (singleJson != null) { jsonFiles.add(singleJson); }
        }
        return jsonFiles;
    }

    /** Chooses a friendly output filename seed based on photos, folder names, or customer info. */
    private static String deriveOutputBaseName(File orderRoot, File outputDir, String customerName, OrderInfo info) {
        String baseName = null;
        try { baseName = deriveNameFromPhotos(orderRoot, info.getOrderId()); } catch (IOException e) { /* ignore */ }
        if (isBlank(baseName)) {
            String folderCandidate = sanitizeName(orderRoot.getName());
            if (!folderCandidate.equalsIgnoreCase("images") &&
                    !folderCandidate.equalsIgnoreCase("img") &&
                    !folderCandidate.equalsIgnoreCase("photos")) {
                baseName = folderCandidate;
            }
        }
        if (isBlank(baseName) && customerName != null) baseName = sanitizeName(customerName);
        if (isBlank(baseName)) baseName = sanitizeName(outputDir.getName());
        return baseName;
    }
    /** Writes mirrored production details (quantity, IDs, barcode) along the bottom of the canvas. */
    private static void drawMirroredInfoText(Graphics2D g2d, OrderInfo orderInfo, String sideLabel, MugTemplate T, int totalOrderQuantity) {
        Font infoFont = new Font("Arial", Font.BOLD, 48);
        g2d.setColor(Color.BLACK);

        // Position dynamically based on template height
        int infoBoxY = T.FINAL_HEIGHT - 147;
        int infoBoxX = 158, infoBoxWidth = 2330, infoBoxHeight = 146;

        FontMetrics fm = g2d.getFontMetrics(infoFont);
        int lineHeight = fm.getHeight();
        int yLine1 = infoBoxY + (infoBoxHeight - (2 * lineHeight)) / 2 + fm.getAscent();
        int yLine2 = infoBoxY + (infoBoxHeight - (2 * lineHeight)) / 2 + fm.getAscent() + lineHeight;

        // Display the correct TOTAL order quantity
        String qtyPart = "Order Q-ty: " + totalOrderQuantity;
        String sidePart = (sideLabel == null || sideLabel.isBlank()) ? "" : " " + sideLabel;

        drawMirroredString(g2d, qtyPart + sidePart, infoBoxX, yLine1, infoFont, false);
        drawMirroredString(g2d, orderInfo.getOrderId(), infoBoxX, yLine2, infoFont, false);

        int rightEdge = infoBoxX + infoBoxWidth;
        String itemIdLast4 = orderInfo.getOrderItemId().substring(Math.max(0, orderInfo.getOrderItemId().length() - 4));

        // Display the ITEM quantity from the JSON
        drawMirroredString(g2d, "ID: " + itemIdLast4 + "  ID Qty: " + orderInfo.getQuantity(), rightEdge, yLine1, infoFont, true);
        drawMirroredString(g2d, orderInfo.getLabel(), rightEdge, yLine2, infoFont, true);

        BufferedImage barcode = generateBarcodeWithText(orderInfo.getOrderId(), 1000, 120);
        int barcodeX = infoBoxX + (infoBoxWidth - barcode.getWidth()) / 2;

        int barcodeY = infoBoxY + (infoBoxHeight - barcode.getHeight()) / 2 - 50;
        g2d.drawImage(barcode, barcodeX, barcodeY, null);
    }


    /** Applies standard high-quality rendering hints for crisp output. */
    private static void setupHighQualityRendering(Graphics2D g2d) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    /** Draws text mirrored horizontally so both mug sides read correctly after printing. */
    static void drawMirroredString(Graphics2D g2d, String text, int x, int y, Font font, boolean alignRight) {
        AffineTransform original = g2d.getTransform();
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int drawX = alignRight ? (x - textWidth) : x;
        g2d.translate(drawX + textWidth, y - fm.getAscent());
        g2d.scale(-1, -1);
        g2d.drawString(text, 0, fm.getAscent());
        g2d.setTransform(original);
    }

    /** Rasterizes customized SVG markup at high resolution while allowing external resources. */
    public static void convertSvgToHighResPng(String svgContent, File baseDirectory, String outputPath,
                                              float targetWidth, float targetHeight) throws IOException, TranscoderException {
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

    /** Convenience wrapper around Java2D subimage cropping. */
    public static void cropImage(String sourcePath, String outputPath, int x, int y, int width, int height) throws IOException {
        BufferedImage sourceImage = ImageIO.read(new File(sourcePath));
        if (sourceImage == null) throw new IOException("Image couldn't read for crop : " + sourcePath);
        BufferedImage cropped = sourceImage.getSubimage(x, y, width, height);
        ImageIO.write(cropped, "png", new File(outputPath));
    }

    /** Finds the first file with the given extension by walking the directory tree. */
    private static File findFileByExtension(File directory, String extension) {
        if (directory == null || !directory.isDirectory()) return null;
        final String extLower = extension.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.walk(directory.toPath(), 3)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extLower))
                    .findFirst()
                    .map(Path::toFile)
                    .orElse(null);
        } catch (IOException e) { return null; }
    }

    /** Attempts to reuse existing photo filenames (minus order IDs) as the output base name. */
    private static String deriveNameFromPhotos(File orderDirectory, String orderId) throws IOException {
        try (Stream<Path> stream = Files.walk(orderDirectory.toPath(), 3)) {
            return stream.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .map(File::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png")
                            && n.toLowerCase(Locale.ROOT).contains(orderId.toLowerCase(Locale.ROOT)))
                    .map(ImageProcessor::extractNameAroundOrderId)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst()
                    .orElse(null);
        }
    }

    /** Strips the order ID portion from a filename and returns the surrounding descriptor text. */
    private static String extractNameAroundOrderId(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String orderIdRegex = "\\d{3}-\\d{7}-\\d{7}";
        Pattern p = Pattern.compile("(.*?)(?:[ _-])?(" + orderIdRegex + ")(?:[ _-])?(.*)");
        Matcher m = p.matcher(base);
        if (!m.matches()) return null;

        String left = trimDelims(m.group(1).trim());
        String right = trimDelims(m.group(3).trim());

        if (!right.isBlank()) {
            String name = right;
            if (left.equalsIgnoreCase("images")) return sanitizeName(name);
            return sanitizeName(name);
        } else if (!left.isBlank()) {
            String name = left;
            if (name.equalsIgnoreCase("images")) return null;
            return sanitizeName(name);
        }
        return null;
    }

    /** Drops underscore/dash prefixes and suffixes that remain after splitting filenames. */
    private static String trimDelims(String s) {
        if (s == null) return "";
        return s.replaceAll("^[ _-]+|[ _-]+$", "");
    }

    /** Normalizes a candidate filename to ASCII-safe characters. */
    private static String sanitizeName(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[^a-zA-Z0-9._-]", "_");
        out = out.replaceAll("__+", "_");
        return out;
    }

    /** Null/blank check used for filename heuristics. */
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    /**
     * Ensures output files never overwrite existing assets by appending incrementing suffixes.
     */
    private static File ensureUniqueFile(File dir, String baseNameNoExt, String ext) {
        File f = new File(dir, baseNameNoExt + ext);
        if (!f.exists()) return f;
        int i = 2;
        while (true) {
            File candidate = new File(dir, baseNameNoExt + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
            i++;
        }
    }

    /** Finds the first SVG either near the JSON file or anywhere within the broader order folder. */
    private static File findNearestSvg(File startDir, File rootFallback) {
        try (Stream<Path> s = Files.walk(startDir.toPath(), 3)) {
            Optional<Path> p = s.filter(Files::isRegularFile)
                    .filter(x -> x.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg"))
                    .findFirst();
            if (p.isPresent()) return p.get().toFile();
        } catch (IOException ignored) {}
        try (Stream<Path> s = Files.walk(rootFallback.toPath(), 6)) {
            Optional<Path> p = s.filter(Files::isRegularFile)
                    .filter(x -> x.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg"))
                    .findFirst();
            if (p.isPresent()) return p.get().toFile();
        } catch (IOException ignored) {}
        return null;
    }

    /** Generates a Code-128 barcode and prints the human-readable text beneath it. */
    static BufferedImage generateBarcodeWithText(String text, int width, int height) {
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

    // =================================================================================
    // SVG font fallback
    // =================================================================================

    /** Adds Java logical fonts to font-family declarations to avoid missing glyphs at runtime. */
    private static String addJavaFallbackFonts(String svg) {
        svg = patchFontFamilyAttributes(svg);
        svg = patchInlineStyleFontFamilies(svg);
        svg = patchStyleBlockFontFamilies(svg);
        return svg;
    }

    /** Injects fallback families into inline `font-family="..."` attributes. */
    private static String patchFontFamilyAttributes(String svg) {
        Pattern attr = Pattern.compile("(?i)font-family\\s*=\\s*\"([^\"]+)\"");
        Matcher m = attr.matcher(svg);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String families = m.group(1);
            String updated = appendMissingFallbacks(families);
            m.appendReplacement(out, "font-family=\"" + Matcher.quoteReplacement(updated) + "\"");
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Adjusts inline style attributes so they also include fallback font families. */
    private static String patchInlineStyleFontFamilies(String svg) {
        Pattern styleAttr = Pattern.compile("(?is)style\\s*=\\s*\"([^\"]*)\"");
        Matcher ms = styleAttr.matcher(svg);
        StringBuffer out = new StringBuffer();
        Pattern famDecl = Pattern.compile("(?i)(font-family\\s*:\\s*)([^;\"']+)");
        while (ms.find()) {
            String styleVal = ms.group(1);
            Matcher fm = famDecl.matcher(styleVal);
            StringBuffer styleOut = new StringBuffer();
            boolean changed = false;
            while (fm.find()) {
                String prefix = fm.group(1);
                String families = fm.group(2).trim();
                String updated = appendMissingFallbacks(families);
                fm.appendReplacement(styleOut, Matcher.quoteReplacement(prefix + updated));
                changed = true;
            }
            fm.appendTail(styleOut);
            String replacement = changed ? styleOut.toString() : styleVal;
            ms.appendReplacement(out, "style=\"" + Matcher.quoteReplacement(replacement) + "\"");
        }
        ms.appendTail(out);
        return out.toString();
    }

    /** Ensures `<style>` blocks reference fallback fonts for any `font-family:` declarations. */
    private static String patchStyleBlockFontFamilies(String svg) {
        Pattern styleBlock = Pattern.compile("(?is)<style([^>]*)>(.*?)</style>");
        Matcher mb = styleBlock.matcher(svg);
        StringBuffer out = new StringBuffer();
        Pattern famDecl = Pattern.compile("(?i)(font-family\\s*:\\s*)([^;}{]+)");
        while (mb.find()) {
            String attrs = mb.group(1);
            String css = mb.group(2);
            Matcher fm = famDecl.matcher(css);
            StringBuffer cssOut = new StringBuffer();
            while (fm.find()) {
                String prefix = fm.group(1);
                String families = fm.group(2).trim();
                String updated = appendMissingFallbacks(families);
                fm.appendReplacement(cssOut, Matcher.quoteReplacement(prefix + updated));
            }
            fm.appendTail(cssOut);
            String rebuilt = "<style" + attrs + ">" + cssOut + "</style>";
            mb.appendReplacement(out, Matcher.quoteReplacement(rebuilt));
        }
        mb.appendTail(out);
        return out.toString();
    }

    /** Appends any missing logical fallback families to the provided font stack string. */
    private static String appendMissingFallbacks(String families) {

        List<String> parts = new ArrayList<>();
        for (String f : families.split(",")) {
            String t = f.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        Set<String> lowerSet = new HashSet<>();
        for (String p : parts) lowerSet.add(stripQuotes(p).toLowerCase(Locale.ROOT));


        for (String fb : JAVA_LOGICAL_FALLBACKS) {
            if (!lowerSet.contains(fb.toLowerCase(Locale.ROOT))) {
                parts.add(fb);
            }
        }


        return String.join(", ", parts);
    }

    /** Removes surrounding single/double quotes without disturbing inner characters. */
    private static String stripQuotes(String s) {
        String t = s.trim();
        if ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith("\"") && t.endsWith("\""))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
