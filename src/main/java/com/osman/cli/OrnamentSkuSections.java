package com.osman.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Provides section lookup utilities for ornament SKUs, backed by
 * {@code ornament-sku-sections.csv}. The CSV is expected to contain
 * two columns: {@code SKU} and {@code Section}.
 */
public final class OrnamentSkuSections {

    public static final String SECTION_UNKNOWN = "Section Unknown";
    public static final String SECTION_MULTI = "Section Multi";

    private static final List<String> PRIMARY_SECTIONS = List.of(
            "Section 1",
            "Section 2",
            "Section 3",
            "Section 4",
            "Section 5",
            "Section 6"
    );

    private static final Map<String, String> SECTION_BY_SKU = loadSectionMap();

    private OrnamentSkuSections() {
    }

    /**
     * Looks up the configured section for a single SKU.
     */
    public static Optional<String> findSection(String sku) {
        if (sku == null) {
            return Optional.empty();
        }
        String trimmed = sku.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String direct = SECTION_BY_SKU.get(trimmed);
        if (direct != null) {
            return Optional.of(direct);
        }
        String normalized = OrnamentSkuNormalizer.normalizeToken(trimmed);
        if (normalized != null && !normalized.isBlank()) {
            direct = SECTION_BY_SKU.get(normalized);
            if (direct != null) {
                return Optional.of(direct);
            }
        }
        return Optional.empty();
    }

    /**
     * Determines an aggregate section for the supplied SKUs.
     * <ul>
     *     <li>If all SKUs resolve to a single section, that section is returned.</li>
     *     <li>If multiple distinct sections are present, {@link #SECTION_MULTI} is returned.</li>
     *     <li>If none resolve, {@link #SECTION_UNKNOWN} is returned.</li>
     * </ul>
     */
    public static String resolveSection(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return SECTION_UNKNOWN;
        }
        LinkedHashSet<String> sections = new LinkedHashSet<>();
        for (String sku : skus) {
            findSection(sku).ifPresent(sections::add);
        }
        if (sections.isEmpty()) {
            return SECTION_UNKNOWN;
        }
        if (sections.size() == 1) {
            return sections.iterator().next();
        }
        return SECTION_MULTI;
    }

    /**
     * Ordered list of the primary sections (Section 1-6).
     */
    public static List<String> primarySections() {
        return PRIMARY_SECTIONS;
    }

    private static Map<String, String> loadSectionMap() {
        Map<String, String> map = new LinkedHashMap<>();
        try (InputStream in = OrnamentSkuSections.class.getResourceAsStream("/ornament-sku-sections.csv")) {
            if (in == null) {
                return Collections.unmodifiableMap(map);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (first) {
                        first = false;
                        if (isHeader(line)) {
                            continue;
                        }
                    }
                    String[] parts = line.split(",", 2);
                    if (parts.length < 2) {
                        continue;
                    }
                    String rawSku = parts[0].trim();
                    String section = parts[1].trim();
                    if (rawSku.isEmpty() || section.isEmpty()) {
                        continue;
                    }
                    String normalized = OrnamentSkuNormalizer.normalizeToken(rawSku);
                    String key = (normalized == null || normalized.isBlank()) ? rawSku : normalized;
                    map.put(key, section);
                }
            }
        } catch (IOException ignored) {
        }
        return Collections.unmodifiableMap(map);
    }

    private static boolean isHeader(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("sku,") && lower.contains("section");
    }
}
