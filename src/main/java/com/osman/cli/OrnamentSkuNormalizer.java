package com.osman.cli;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for normalizing ornament SKU tokens and preparing text for regex scanning.
 */
public final class OrnamentSkuNormalizer {

    private static final Pattern PREFIX_BODY = Pattern.compile(
            "^(OR|RN|RM|PF|ORN)\\s*[-:]?\\s*([A-Z0-9][A-Z0-9-/._]*)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ZIP_PATTERN = Pattern.compile("^\\d{5}(?:-\\d{4})?$");
    private static final Pattern SKU_SUFFIX_PATTERN = Pattern.compile(
            "^(SKU[^.]+?)(?:\\.(OR|PF|RN|RM|ORN))?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final String[] SUFFIX_PRIORITY = {"PF", "RN", "RM", "ORN", "OR"};

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
        if (s.startsWith("SLU")) {
            s = "SKU" + s.substring(3);
        }
        s = stripCustomizationArtifacts(s);

        if (s.startsWith("SKU")) {
            String base = ensureNoTrailingPunctuation(s);
            Matcher suffixMatcher = SKU_SUFFIX_PATTERN.matcher(base);
            if (suffixMatcher.matches()) {
                String suffix = normalizeSuffix(suffixMatcher.group(2));
                return suffixMatcher.group(1) + "." + suffix;
            }
            return base.endsWith(".OR") ? base : (base + ".OR");
        }

        Matcher m = PREFIX_BODY.matcher(s);
        if (m.matches()) {
            String prefix = m.group(1).toUpperCase(Locale.ROOT);
            String body = m.group(2);
            body = body.replaceAll("^[^A-Z0-9]+", "");
            body = stripCustomizationArtifacts(body);
            body = body.replaceAll("[-.]+$", "");
            
            if (body.isEmpty()) return s;
            String suffix = switch (prefix) {
                case "PF" -> ".PF";
                case "RN" -> ".RN";
                case "RM" -> ".RM";
                case "ORN" -> ".ORN";
                case "OR" -> ".OR";
                default -> ".OR";
            };
            return "SKU" + body + suffix;
        }

        return s;
    }

    public static java.util.Set<String> canonicalizeTokens(java.util.Set<String> tokens) {
        java.util.LinkedHashMap<String, String> bestByBase = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> others = new java.util.LinkedHashSet<>();
        for (String token : tokens) {
            Matcher m = SKU_SUFFIX_PATTERN.matcher(token);
            if (!m.matches()) {
                others.add(token);
                continue;
            }
            String base = m.group(1).toUpperCase(Locale.ROOT);
            String suffix = normalizeSuffix(m.group(2));
            String candidate = base + "." + suffix;
            String existing = bestByBase.get(base);
            if (existing == null || compareSuffix(candidate, existing) < 0) {
                bestByBase.put(base, candidate);
            }
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        result.addAll(bestByBase.values());
        result.addAll(others);
        return result;
    }

    public static java.util.Map<String, Integer> canonicalizeTotals(java.util.Map<String, Integer> totals) {
        java.util.LinkedHashMap<String, Integer> qtyByBase = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> tokenByBase = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Integer> others = new java.util.LinkedHashMap<>();

        for (java.util.Map.Entry<String, Integer> entry : totals.entrySet()) {
            String token = entry.getKey();
            int qty = entry.getValue();
            Matcher m = SKU_SUFFIX_PATTERN.matcher(token);
            if (!m.matches()) {
                others.merge(token, qty, Integer::sum);
                continue;
            }
            String base = m.group(1).toUpperCase(Locale.ROOT);
            String suffix = normalizeSuffix(m.group(2));
            String candidate = base + "." + suffix;

            String existingToken = tokenByBase.get(base);
            if (existingToken == null) {
                tokenByBase.put(base, candidate);
                qtyByBase.put(base, qty);
            } else {
                if (compareSuffix(candidate, existingToken) < 0) {
                    int existingQty = qtyByBase.get(base);
                    qtyByBase.put(base, existingQty + qty);
                    tokenByBase.put(base, candidate);
                } else {
                    qtyByBase.put(base, qtyByBase.get(base) + qty);
                }
            }
        }

        java.util.LinkedHashMap<String, Integer> result = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> entry : tokenByBase.entrySet()) {
            String base = entry.getKey();
            String token = entry.getValue();
            result.put(token, qtyByBase.getOrDefault(base, 0));
        }
        result.putAll(others);
        return result;
    }

    private static String normalizeSuffix(String raw) {
        String suffix = (raw == null || raw.isBlank()) ? "OR" : raw.toUpperCase(Locale.ROOT);
        return switch (suffix) {
            case "PF", "RN", "RM", "ORN", "OR" -> suffix;
            default -> "OR";
        };
    }

    private static int compareSuffix(String a, String b) {
        return Integer.compare(suffixRank(a), suffixRank(b));
    }

    private static int suffixRank(String token) {
        Matcher m = SKU_SUFFIX_PATTERN.matcher(token);
        if (!m.matches()) {
            return SUFFIX_PRIORITY.length;
        }
        String suffix = normalizeSuffix(m.group(2));
        for (int i = 0; i < SUFFIX_PRIORITY.length; i++) {
            if (SUFFIX_PRIORITY[i].equals(suffix)) {
                return i;
            }
        }
        return SUFFIX_PRIORITY.length;
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
