package com.osman.cli;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for normalizing ornament SKU tokens and preparing text for regex scanning.
 */
public final class OrnamentSkuNormalizer {

    private static final Pattern PREFIX_BODY = Pattern.compile(
            "^(?:OR|RN|RM|PF|ORN)\\s*[-:]?\\s*([A-Z0-9][A-Z0-9-/._]*)$",
            Pattern.CASE_INSENSITIVE
    );

    private OrnamentSkuNormalizer() {}

    public static String normalizeForScan(String text) {
        if (text == null) return "";
        String t = text
                .replace("\u00AD", "")   // soft hyphen
                .replace("\u200B", "")   // zero width space
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\uFEFF", "");
        t = t.replace('\u00A0', ' ');
        t = t.replaceAll("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212\u2043]", "-");
        t = t.replaceAll("[\\t\\f\\r]+", " ");
        return t;
    }

    public static String normalizeToken(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        s = stripCustomizationArtifacts(s);

        if (s.startsWith("SKU")) {
            String base = ensureNoTrailingPunctuation(s);
            return base.endsWith(".OR") ? base : (base + ".OR");
        }

        Matcher m = PREFIX_BODY.matcher(s);
        if (m.matches()) {
            String body = m.group(1);
            body = body.replaceAll("^[^A-Z0-9]+", "");
            body = stripCustomizationArtifacts(body);
            body = body.replaceAll("[-.]+$", "");
            if (body.isEmpty()) return s;
            return "SKU" + body + ".OR";
        }

        return s;
    }

    private static String stripCustomizationArtifacts(String value) {
        if (value == null) return null;
        String v = value;
        v = v.replaceAll("(?i)CUSTOMIZATIONS", "");
        v = v.replaceAll("--+", "-");
        v = v.replaceAll("-+\\.", ".");
        v = v.replaceAll("\\.-+", ".");
        v = v.replaceAll("-+$", "");
        v = v.replaceAll("^[-.]+", "");
        return v;
    }

    private static String ensureNoTrailingPunctuation(String token) {
        String t = token;
        while (t.endsWith(".") && !".".equals(t)) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
