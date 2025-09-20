package com.osman;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
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
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ImageProcessor {

    static final String BLANK_LOGO_URL = "https://m.media-amazon.com/images/S/";
    static final String TRANSPARENT_PIXEL_DATA_URI =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

    static final int FINAL_WIDTH = 2580;
    static final int FINAL_HEIGHT = 1410;
    static final int RENDER_SIZE = 3200;

    static final int area1X = 144,  area1Y = 78,  area1Width = 952,  area1Height = 974;
    static final int area2X = 1524, area2Y = 75,  area2Width = 944,  area2Height = 975;

    static final int crop1X = 336,  crop1Y = 1048, crop1Width = 1016, crop1Height = 1040;
    static final int crop2X = 1808, crop2Y = 1048, crop2Width = 1016, crop2Height = 1040;

    public static List<String> processOrderFolderMulti(File orderDirectory,
                                                       File outputDirectory,
                                                       String customerNameForFile,
                                                       String fileNameSuffix) throws Exception {
        if (!orderDirectory.isDirectory())
            throw new IllegalArgumentException("Provided order path is not a directory: " + orderDirectory.getAbsolutePath());
        if (!outputDirectory.isDirectory())
            throw new IllegalArgumentException("Provided output path is not a directory: " + outputDirectory.getAbsolutePath());

        List<File> jsonFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(orderDirectory.toPath(), 6)) {
            stream.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(f -> f.getName().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .forEach(jsonFiles::add);
        }

        if (jsonFiles.isEmpty()) {
            File singleJson = findFileByExtension(orderDirectory, ".json");
            if (singleJson == null) {
                throw new IOException("No JSON found under: " + orderDirectory.getAbsolutePath());
            }
            return List.of(renderFromJson(singleJson, orderDirectory, outputDirectory, customerNameForFile, fileNameSuffix));
        }

        List<String> outputs = new ArrayList<>();
        for (File jsonFile : jsonFiles) {
            String outPath = renderFromJson(jsonFile, orderDirectory, outputDirectory, customerNameForFile, fileNameSuffix);
            outputs.add(outPath);
        }
        return outputs;
    }

    private static BufferedImage readImageAny(String href, File baseDir) throws IOException {
        String lower = href.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:image/")) {
            int i = href.indexOf("base64,");
            if (i > 0) {
                byte[] bytes = Base64.getDecoder().decode(href.substring(i + 7));
                try (var in = new java.io.ByteArrayInputStream(bytes)) {
                    return ImageIO.read(in);
                }
            }
            return null;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try (var in = new java.net.URL(href).openStream()) {
                return ImageIO.read(in);
            }
        }
        File f = new File(baseDir, href);
        return f.isFile() ? ImageIO.read(f) : null;
    }

    private static String normalizeAllImagesToLocalPNG(String svg, File baseDir) {
        Pattern p = Pattern.compile("<image\\b([^>]*?)(?:xlink:href|href)=\"([^\"]+)\"([^>]*)>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(svg);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String before = m.group(1);
            String href   = m.group(2);
            String after  = m.group(3);

            try {
                BufferedImage in = readImageAny(href, baseDir);
                if (in == null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                    continue;
                }

                int type = in.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
                BufferedImage rgb = new BufferedImage(in.getWidth(), in.getHeight(), type);
                Graphics2D g = rgb.createGraphics();
                g.setComposite(AlphaComposite.Src);
                g.drawImage(in, 0, 0, null);
                g.dispose();

                String safe = href.replaceAll("[^a-zA-Z0-9._-]", "_");
                if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
                String newName = safe.replaceAll("\\.(?i)(jpe?g|png|tif?f|webp|heic)$", "") + "__srgb.png";

                File out = new File(baseDir, newName);
                ImageIO.write(rgb, "png", out);
                out.deleteOnExit();

                String replaced = "<image" + before + "xlink:href=\"" + newName + "\"" + after + ">";
                m.appendReplacement(sb, Matcher.quoteReplacement(replaced));
            } catch (Exception ex) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String removeNonSrgbPngImagesInSvg(String svg, File baseDir) {
        Pattern p = Pattern.compile("<image\\b([^>]*?)(?:xlink:href|href)=\"([^\"]+)\"([^>]*)>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(svg);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String before = m.group(1);
            String href   = m.group(2);
            String after  = m.group(3);
            String lower  = href.toLowerCase(Locale.ROOT);
            boolean keep = lower.endsWith("__srgb.png");
            String replaced = keep ? m.group(0)
                    : "<image" + before + "xlink:href=\"" + TRANSPARENT_PIXEL_DATA_URI + "\"" + after + ">";
            m.appendReplacement(sb, Matcher.quoteReplacement(replaced));
        }
        m.appendTail(sb);
        return sb.toString();
    }
    // ImageProcessor class içine (diğer yardımcıların yanına)
    private static void deleteIfExists(File f) {
        try { if (f != null && f.exists()) Files.delete(f.toPath()); }
        catch (Exception ignore) { if (f != null) f.delete(); }
    }

    private static void cleanupSrgbAndOrientedFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith("__srgb.png")
                    || name.endsWith("__oriented.png")
                    || name.endsWith("__oriented.jpg")) {
                deleteIfExists(f);
            }
        }
    }

    private static String renderFromJson(File jsonFile,
                                         File orderRoot,
                                         File outputDirectory,
                                         String customerNameForFile,
                                         String fileNameSuffix) throws Exception {
        OrderInfo orderInfo = JsonDataReader.parse(jsonFile, orderRoot);

        String baseName = deriveNameFromPhotos(jsonFile.getParentFile(), orderInfo.getOrderId());
        if (isBlank(baseName)) {
            String folderCandidate = sanitizeName(orderRoot.getName());
            if (!folderCandidate.equalsIgnoreCase("images")
                    && !folderCandidate.equalsIgnoreCase("img")
                    && !folderCandidate.equalsIgnoreCase("photos")) {
                baseName = folderCandidate;
            }
        }
        if (isBlank(baseName) && customerNameForFile != null) baseName = sanitizeName(customerNameForFile);
        if (isBlank(baseName)) baseName = sanitizeName(outputDirectory.getName());

        String baseNoExt = baseName + "(" + orderInfo.getOrderId() + ")";
        File finalOutputFile = ensureUniqueFile(outputDirectory, baseNoExt, ".png");

        File svgFile = findNearestSvg(jsonFile.getParentFile(), orderRoot);
        if (svgFile == null) throw new IOException("SVG not found for: " + jsonFile.getAbsolutePath());

        String originalSvgContent = Files.readString(svgFile.toPath());
        String modifiedSvgContent = originalSvgContent.replace("FONT_PLACEHOLDER", orderInfo.getFontName());
        if (modifiedSvgContent.contains(BLANK_LOGO_URL)) {
            modifiedSvgContent = modifiedSvgContent.replace(BLANK_LOGO_URL, TRANSPARENT_PIXEL_DATA_URI);
        }

        modifiedSvgContent = fixExifOrientationInSvg(modifiedSvgContent, svgFile.getParentFile());
        modifiedSvgContent = normalizeAllImagesToLocalPNG(modifiedSvgContent, svgFile.getParentFile());
        modifiedSvgContent = removeNonSrgbPngImagesInSvg(modifiedSvgContent, svgFile.getParentFile());

        BufferedImage finalCanvas = new BufferedImage(FINAL_WIDTH, FINAL_HEIGHT, BufferedImage.TYPE_INT_RGB);

        String orderType = detectOrderType(jsonFile.getParentFile());
        if ("TEXT_ONLY".equals(orderType)) {
            String rnd = UUID.randomUUID().toString();
            File tempMaster = new File(outputDirectory, "temp_master_" + rnd + ".png");
            File tempCrop1  = new File(outputDirectory, "temp_crop1_"  + rnd + ".png");
            File tempCrop2  = new File(outputDirectory, "temp_crop2_"  + rnd + ".png");
            try {
                convertSvgToHighResPng(modifiedSvgContent, svgFile.getParentFile(),
                        tempMaster.getAbsolutePath(), RENDER_SIZE, RENDER_SIZE);

                cropImage(tempMaster.getAbsolutePath(), tempCrop1.getAbsolutePath(),
                        crop1X, crop1Y, crop1Width, crop1Height);
                cropImage(tempMaster.getAbsolutePath(), tempCrop2.getAbsolutePath(),
                        crop2X, crop2Y, crop2Width, crop2Height);

                drawPhotoMugOnCanvas(finalCanvas,
                        tempCrop1.getAbsolutePath(), area1X, area1Y, area1Width, area1Height,
                        tempCrop2.getAbsolutePath(), area2X, area2Y, area2Width, area2Height, orderInfo);
            } finally {
                tempMaster.delete();
                tempCrop1.delete();
                tempCrop2.delete();
            }
        } else {
            String rnd = UUID.randomUUID().toString();
            File tempMaster = new File(outputDirectory, "temp_master_" + rnd + ".png");
            File tempCrop1  = new File(outputDirectory, "temp_crop1_"  + rnd + ".png");
            File tempCrop2  = new File(outputDirectory, "temp_crop2_"  + rnd + ".png");
            try {
                convertSvgToHighResPng(modifiedSvgContent, svgFile.getParentFile(),
                        tempMaster.getAbsolutePath(), RENDER_SIZE, RENDER_SIZE);
                cropImage(tempMaster.getAbsolutePath(), tempCrop1.getAbsolutePath(),
                        crop1X, crop1Y, crop1Width, crop1Height);
                cropImage(tempMaster.getAbsolutePath(), tempCrop2.getAbsolutePath(),
                        crop2X, crop2Y, crop2Width, crop2Height);
                drawPhotoMugOnCanvas(finalCanvas,
                        tempCrop1.getAbsolutePath(), area1X, area1Y, area1Width, area1Height,
                        tempCrop2.getAbsolutePath(), area2X, area2Y, area2Width, area2Height, orderInfo);
            } finally {
                tempMaster.delete();
                tempCrop1.delete();
                tempCrop2.delete();
            }
        }

        ImageIO.write(finalCanvas, "png", finalOutputFile);
        // >>> SADE TEMİZLİK: Sadece SVG klasöründeki __srgb / __oriented dosyalarını sil
        cleanupSrgbAndOrientedFiles(svgFile.getParentFile());

        return finalOutputFile.getAbsolutePath();
    }

    private static String detectOrderType(File dir) {
        File[] imageFiles = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith("__srgb.png"));
        return (imageFiles != null && imageFiles.length > 0) ? "PHOTO_MUG" : "TEXT_ONLY";
    }

    public static void drawPhotoMugOnCanvas(BufferedImage canvas, String overlay1Path, int x1, int y1, int w1, int h1,
                                            String overlay2Path, int x2, int y2, int w2, int h2, OrderInfo orderInfo) throws IOException {
        Graphics2D g2d = canvas.createGraphics();
        setupHighQualityRendering(g2d);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        BufferedImage o1 = ImageIO.read(new File(overlay1Path));
        BufferedImage o2 = ImageIO.read(new File(overlay2Path));
        g2d.drawImage(o1, x1, y1, w1, h1, null);
        g2d.drawImage(o2, x2, y2, w2, h2, null);

        drawMirroredInfoText(g2d, orderInfo);
        g2d.dispose();
    }

    private static int readExifOrientation(File f) {
        try {
            Metadata md = ImageMetadataReader.readMetadata(f);
            ExifIFD0Directory d = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (d != null && d.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return d.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ignore) {}
        return 1;
    }

    private static boolean isSwapOrientation(int ori) {
        return ori == 5 || ori == 6 || ori == 7 || ori == 8;
    }

    private static boolean shouldApplyExif(int ori, int w, int h) {
        if (ori == 3) return true;
        if (isSwapOrientation(ori)) return w >= h;
        return false;
    }
    // 1) EXIF yön düzeltme: AffineTransformOp yok, RGB/ARGB seçimi + beyaz zemin
    private static BufferedImage applyExifOrientation(BufferedImage src, int orientation) {
        boolean hasAlpha = src.getColorModel().hasAlpha();
        int w = src.getWidth(), h = src.getHeight();
        int newW = w, newH = h;

        AffineTransform tx = new AffineTransform();
        switch (orientation) {
            case 1:  return src;
            case 2:  tx.scale(-1, 1); tx.translate(-w, 0); break;
            case 3:  tx.translate(w, h); tx.rotate(Math.PI); break;
            case 4:  tx.scale(1, -1); tx.translate(0, -h); break;
            case 5:  tx.rotate(-Math.PI/2); tx.scale(-1, 1); tx.translate(-w, 0); newW = h; newH = w; break;
            case 6:  tx.translate(h, 0); tx.rotate(Math.PI/2);                         newW = h; newH = w; break;
            case 7:  tx.rotate(Math.PI/2); tx.scale(-1, 1); tx.translate(-w, 0);      newW = h; newH = w; break;
            case 8:  tx.translate(0, w); tx.rotate(-Math.PI/2);                       newW = h; newH = w; break;
            default: return src;
        }

        int type = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage dst = new BufferedImage(newW, newH, type);

        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (!hasAlpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, newW, newH);
            }

            g.setTransform(tx);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    // 2) EXIF’e göre <image> kaynaklarını yerinde düzelten sürüm (href ve xlink:href destekli).
//    Alfa yoksa JPEG, varsa PNG üretir.
    private static String fixExifOrientationInSvg(String svg, File baseDir) {
        Pattern p = Pattern.compile("<image\\b([^>]*?)(?:xlink:href|href)=\"([^\"]+)\"([^>]*)>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(svg);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String before = m.group(1);
            String href   = m.group(2);
            String after  = m.group(3);

            if (href.startsWith("data:")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }

            File imgFile = new File(baseDir, href);
            int ori = imgFile.isFile() ? readExifOrientation(imgFile) : 1;

            if (ori == 1 || !imgFile.isFile()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }

            try {
                BufferedImage raw = ImageIO.read(imgFile);
                if (raw == null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                    continue;
                }

                if (!shouldApplyExif(ori, raw.getWidth(), raw.getHeight())) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                    continue;
                }

                BufferedImage fixed = applyExifOrientation(raw, ori);
                boolean hasAlpha = fixed.getColorModel().hasAlpha();

                String stem = href.replaceAll("\\.(?i)(jpe?g|png|tif?f|webp|heic)$", "");
                String newName = stem + "__oriented." + (hasAlpha ? "png" : "jpg");
                File temp = new File(baseDir, newName);

                if (hasAlpha) {
                    ImageIO.write(fixed, "png", temp);
                } else {
                    writeJpeg(fixed, temp, 0.9f);
                }
                temp.deleteOnExit();

                String replaced = "<image" + before + "xlink:href=\"" + newName + "\"" + after + ">";
                m.appendReplacement(sb, Matcher.quoteReplacement(replaced));
            } catch (IOException e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // 3) JPEG yazıcı (kalite parametreli). Girdi ARGB ise önce RGB’ye düşürür.
    private static void writeJpeg(BufferedImage src, File out, float quality) throws IOException {
        BufferedImage rgb = src.getType() == BufferedImage.TYPE_INT_RGB ? src
                : new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        if (rgb != src) {
            Graphics2D g = rgb.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
        }

        javax.imageio.ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam prm = w.getDefaultWriteParam();
        prm.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        prm.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));

        try (var ios = ImageIO.createImageOutputStream(out)) {
            w.setOutput(ios);
            w.write(null, new javax.imageio.IIOImage(rgb, null, null), prm);
        } finally {
            w.dispose();
        }
    }


    public static void drawSingleDesignOnCanvas(BufferedImage canvas, String path, int x1, int y1, int w1, int h1,
                                                int x2, int y2, int w2, int h2, OrderInfo orderInfo) throws IOException {
        Graphics2D g2d = canvas.createGraphics();
        setupHighQualityRendering(g2d);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        BufferedImage img = ImageIO.read(new File(path));
        g2d.drawImage(img, x1, y1, w1, h1, null);
        g2d.drawImage(img, x2, y2, w2, h2, null);

        drawMirroredInfoText(g2d, orderInfo);
        g2d.dispose();
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

    private static void drawMirroredInfoText(Graphics2D g2d, OrderInfo orderInfo) {
        Font infoFont = new Font("Arial", Font.BOLD, 48);
        g2d.setColor(Color.BLACK);

        int infoBoxX = 158, infoBoxY = 1263, infoBoxWidth = 2330, infoBoxHeight = 146;

        g2d.setFont(infoFont);
        FontMetrics fm = g2d.getFontMetrics();
        int lineHeight = fm.getHeight();

        int yLine1 = infoBoxY + (infoBoxHeight - (2 * lineHeight)) / 2 + fm.getAscent();
        int yLine2 = yLine1 + lineHeight;

        String leftLine1 = "Order Q-ty: " + orderInfo.getQuantity();
        String leftLine2 = orderInfo.getOrderId();
        drawMirroredString(g2d, leftLine1, infoBoxX, yLine1, infoFont, false);
        drawMirroredString(g2d, leftLine2, infoBoxX, yLine2, infoFont, false);

        int rightEdge = infoBoxX + infoBoxWidth;
        String itemIdLast4 = orderInfo.getOrderItemId()
                .substring(Math.max(0, orderInfo.getOrderItemId().length() - 4));
        String rightLine1 = "ID: " + itemIdLast4 + "   ID Q-ty: " + orderInfo.getQuantity();
        String rightLine2 = orderInfo.getLabel();
        drawMirroredString(g2d, rightLine1, rightEdge, yLine1, infoFont, true);
        drawMirroredString(g2d, rightLine2, rightEdge, yLine2, infoFont, true);

        int barcodeWidth = 1000;
        int barcodeHeightOnly = 120;
        BufferedImage barcode = generateBarcodeWithText(orderInfo.getOrderId(), barcodeWidth, barcodeHeightOnly);
        int barcodeTotalHeight = barcode.getHeight();

        int barcodeX = infoBoxX + (infoBoxWidth - barcodeWidth) / 2;
        int barcodeY = infoBoxY + (infoBoxHeight - barcodeTotalHeight) / 2 - 50;
        g2d.drawImage(barcode, barcodeX, barcodeY, null);
    }

    private static void setupHighQualityRendering(Graphics2D g2d) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static void drawMirroredString(Graphics2D g2d, String text, int x, int y, Font font, boolean alignRight) {
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

    public static void convertSvgToHighResPng(String svgContent, File baseDirectory, String outputPath,
                                              float targetWidth, float targetHeight)
            throws IOException, TranscoderException {
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

    public static void cropImage(String sourcePath, String outputPath, int x, int y, int width, int height) throws IOException {
        BufferedImage sourceImage = ImageIO.read(new File(sourcePath));
        BufferedImage cropped = sourceImage.getSubimage(x, y, width, height);
        ImageIO.write(cropped, "png", new File(outputPath));
    }

    private static File findFileByExtension(File directory, String extension) {
        if (directory == null || !directory.isDirectory()) return null;
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

    private static String deriveNameFromPhotos(File orderDirectory, String orderId) {
        try (Stream<Path> stream = Files.walk(orderDirectory.toPath(), 3)) {
            return stream.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .map(File::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith("__srgb.png")
                            && n.toLowerCase(Locale.ROOT).contains(orderId.toLowerCase(Locale.ROOT)))
                    .map(ImageProcessor::extractNameAroundOrderId)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

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

    private static String trimDelims(String s) {
        if (s == null) return "";
        return s.replaceAll("^[ _-]+|[ _-]+$", "");
    }

    private static String sanitizeName(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[^a-zA-Z0-9._-]", "_");
        out = out.replaceAll("__+", "_");
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

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
}
