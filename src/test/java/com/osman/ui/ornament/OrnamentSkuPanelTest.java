package com.osman.ui.ornament;

import com.osman.cli.OrnamentDebugLogger;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrnamentSkuPanelTest {

    @Test
    void countSkuOccurrencesHandlesLineWrappedSku() throws Exception {
        Method method = OrnamentSkuPanel.class
            .getDeclaredMethod("countSkuOccurrences", String.class, OrnamentDebugLogger.class);
        method.setAccessible(true);

        String slip = """
            Packing Slip
            SKU1847-
            P.OR NEW
            Customizations:
            Qty: 1
            Grand Total: $20.25
            """;

        Path debugDir = Files.createTempDirectory("ornament-debug");
        try (OrnamentDebugLogger debug = OrnamentDebugLogger.create(debugDir)) {
            @SuppressWarnings("unchecked")
            Map<String, Integer> totals =
                (Map<String, Integer>) method.invoke(null, slip, debug);

            assertTrue(totals.containsKey("SKU1847-P.OR"), "Expected normalized SKU1847-P.OR");
            assertEquals(1, totals.get("SKU1847-P.OR"));
            assertTrue(totals.keySet().stream()
                .noneMatch(key -> key != null && key.contains("OR_NEW")));
            assertTrue(totals.keySet().stream()
                .noneMatch(key -> key != null && key.contains("SKU1847-.OR")));
        } finally {
            try {
                Files.deleteIfExists(debugDir);
            } catch (Exception ignored) {
            }
        }
    }
}
