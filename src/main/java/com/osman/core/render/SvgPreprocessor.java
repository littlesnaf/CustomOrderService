package com.osman.core.render;

import com.osman.core.model.OrderInfo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Injects personalization data and sanitizes embedded images before rasterization.
 */
public final class SvgPreprocessor {
    private static final String BLANK_LOGO_URL = "https://m.media-amazon.com/images/S/";
    private static final String TRANSPARENT_PIXEL_DATA_URI = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
    private static final String[] JAVA_LOGICAL_FALLBACKS = new String[]{"SansSerif", "Dialog"};

    private SvgPreprocessor() {
    }

    public static ProcessedSvg preprocess(File svgFile, OrderInfo orderInfo, File tempDir) throws IOException {
        String content = Files.readString(svgFile.toPath());
        content = content.replace("FONT_PLACEHOLDER", orderInfo.getFontName());
        if (content.contains(BLANK_LOGO_URL)) {
            content = content.replace(BLANK_LOGO_URL, TRANSPARENT_PIXEL_DATA_URI);
        }

        Pattern imagePattern = Pattern.compile("<image\\b([^>]*?)(?:xlink:href|href)=\"([^\"]+)\"([^>]*)>");
        Matcher matcher = imagePattern.matcher(content);
        List<File> tempFiles = new ArrayList<>();
        List<String> missingImages = new ArrayList<>();
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String href = matcher.group(2);
            if (href.startsWith("data:")) {
                matcher.appendReplacement(sb, matcher.group(0));
                continue;
            }

            boolean remoteReference = isRemoteReference(href);
            File originalImageFile = remoteReference ? null : new File(svgFile.getParentFile(), href);
            if (!remoteReference && (originalImageFile == null || !originalImageFile.exists())) {
                String expectedPath = (originalImageFile == null) ? href : originalImageFile.getAbsolutePath();
                missingImages.add(href + " (expected at " + expectedPath + ")");
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            BufferedImage sanitizedImage = remoteReference ? null : ImageMagickAdapter.sanitize(originalImageFile);
            if (sanitizedImage != null) {
                File tempPngFile = File.createTempFile("magick_", ".png", tempDir);
                ImageIO.write(sanitizedImage, "png", tempPngFile);
                tempFiles.add(tempPngFile);
                String newHref = tempPngFile.toURI().toString();
                String replacement = "<image" + matcher.group(1) + "xlink:href=\"" + newHref + "\"" + matcher.group(3) + ">";
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                String replacement = "<image" + matcher.group(1) + "xlink:href=\"" + TRANSPARENT_PIXEL_DATA_URI + "\"" + matcher.group(3) + ">";
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(sb);

        if (!missingImages.isEmpty()) {
            throw new MissingEmbeddedImageException(svgFile, missingImages);
        }

        String withFallbacks = addJavaFallbackFonts(sb.toString());
        return new ProcessedSvg(withFallbacks, tempFiles);
    }

    private static boolean isRemoteReference(String href) {
        String lower = href.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String addJavaFallbackFonts(String svg) {
        svg = patchFontFamilyAttributes(svg);
        svg = patchInlineStyleFontFamilies(svg);
        svg = patchStyleBlockFontFamilies(svg);
        return svg;
    }

    private static String patchFontFamilyAttributes(String svg) {
        Pattern attr = Pattern.compile("(?i)font-family\\s*=\\s*\"([^\"]+)\"");
        Matcher matcher = attr.matcher(svg);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String families = matcher.group(1);
            String updated = appendMissingFallbacks(families);
            matcher.appendReplacement(out, "font-family=\"" + Matcher.quoteReplacement(updated) + "\"");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String patchInlineStyleFontFamilies(String svg) {
        Pattern styleAttr = Pattern.compile("(?is)style\\s*=\\s*\"([^\"]*)\"");
        Matcher styleMatcher = styleAttr.matcher(svg);
        StringBuffer out = new StringBuffer();
        Pattern familyDeclaration = Pattern.compile("(?i)(font-family\\s*:\\s*)([^;\"']+)");
        while (styleMatcher.find()) {
            String styleValue = styleMatcher.group(1);
            Matcher familyMatcher = familyDeclaration.matcher(styleValue);
            StringBuffer styleOut = new StringBuffer();
            boolean changed = false;
            while (familyMatcher.find()) {
                String prefix = familyMatcher.group(1);
                String families = familyMatcher.group(2).trim();
                String updated = appendMissingFallbacks(families);
                familyMatcher.appendReplacement(styleOut, Matcher.quoteReplacement(prefix + updated));
                changed = true;
            }
            familyMatcher.appendTail(styleOut);
            String replacement = changed ? styleOut.toString() : styleValue;
            styleMatcher.appendReplacement(out, "style=\"" + Matcher.quoteReplacement(replacement) + "\"");
        }
        styleMatcher.appendTail(out);
        return out.toString();
    }

    private static String patchStyleBlockFontFamilies(String svg) {
        Pattern styleBlock = Pattern.compile("(?is)<style([^>]*)>(.*?)</style>");
        Matcher blockMatcher = styleBlock.matcher(svg);
        StringBuffer out = new StringBuffer();
        Pattern familyDeclaration = Pattern.compile("(?i)(font-family\\s*:\\s*)([^;}{]+)");
        while (blockMatcher.find()) {
            String attrs = blockMatcher.group(1);
            String css = blockMatcher.group(2);
            Matcher familyMatcher = familyDeclaration.matcher(css);
            StringBuffer cssOut = new StringBuffer();
            while (familyMatcher.find()) {
                String prefix = familyMatcher.group(1);
                String families = familyMatcher.group(2).trim();
                String updated = appendMissingFallbacks(families);
                familyMatcher.appendReplacement(cssOut, Matcher.quoteReplacement(prefix + updated));
            }
            familyMatcher.appendTail(cssOut);
            String rebuilt = "<style" + attrs + ">" + cssOut + "</style>";
            blockMatcher.appendReplacement(out, Matcher.quoteReplacement(rebuilt));
        }
        blockMatcher.appendTail(out);
        return out.toString();
    }

    private static String appendMissingFallbacks(String families) {
        List<String> parts = new ArrayList<>();
        for (String part : families.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        Set<String> lowerSet = new HashSet<>();
        for (String value : parts) {
            lowerSet.add(stripQuotes(value).toLowerCase(Locale.ROOT));
        }
        for (String fallback : JAVA_LOGICAL_FALLBACKS) {
            if (!lowerSet.contains(fallback.toLowerCase(Locale.ROOT))) {
                parts.add(fallback);
            }
        }
        return String.join(", ", parts);
    }

    private static String stripQuotes(String value) {
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    public record ProcessedSvg(String content, List<File> tempFiles) {
    }

    public static final class MissingEmbeddedImageException extends IOException {
        private final List<String> missingAssets;

        public MissingEmbeddedImageException(File svgFile, List<String> missingAssets) {
            super("Missing image assets referenced by SVG '" + svgFile.getName() + "': " + String.join(", ", missingAssets));
            this.missingAssets = List.copyOf(missingAssets);
        }

        public List<String> getMissingAssets() {
            return missingAssets;
        }
    }
}
