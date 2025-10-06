package com.osman.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collection;

/**
 * Handles ornament flow debugging. When disabled, all methods become no-ops.
 */
public final class OrnamentDebugLogger implements AutoCloseable {

    private final boolean enabled;
    private final PrintWriter writer;

    private OrnamentDebugLogger(boolean enabled, PrintWriter writer) {
        this.enabled = enabled;
        this.writer = writer;
    }

    public static OrnamentDebugLogger create(Path outDir) {
        boolean enabled = Boolean.getBoolean("ornament.debug");
        if (!enabled) {
            return new OrnamentDebugLogger(false, null);
        }
        try {
            Path debugDir = outDir.resolve("_debug");
            Files.createDirectories(debugDir);
            Path logFile = debugDir.resolve("ornament-debug.log");
            PrintWriter writer = new PrintWriter(Files.newBufferedWriter(
                    logFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ));
            writer.println("=== ORNAMENT DEBUG START " + Instant.now() + " ===");
            writer.flush();
            return new OrnamentDebugLogger(true, writer);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize ornament debug logger", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void logPage(int docId, int pageIndex, String text) {
        if (!enabled) return;
        synchronized (writer) {
            writer.println("===== DOC " + docId + " PAGE " + (pageIndex + 1) + " =====");
            writer.println(text);
            writer.println("===== END PAGE =====");
            writer.flush();
        }
    }

    public void logToken(String raw, String normalized) {
        if (!enabled) return;
        synchronized (writer) {
            writer.println("[TOKEN] raw='" + raw + "' normalized='" + normalized + "'");
            writer.flush();
        }
    }

    public void logSkuSet(String source, Collection<String> skus) {
        if (!enabled) return;
        synchronized (writer) {
            writer.println("[SKUS] " + source + " -> " + (skus.isEmpty() ? "<none>" : skus));
            writer.flush();
        }
    }

    public void logBundleCompleted(String orderId, Collection<String> skus) {
        if (!enabled) return;
        synchronized (writer) {
            writer.println("[BUNDLE COMPLETE] order=" + (orderId == null ? "<unknown>" : orderId)
                    + " skus=" + skus);
            writer.flush();
        }
    }

    @Override
    public void close() {
        if (!enabled) return;
        synchronized (writer) {
            writer.println("=== ORNAMENT DEBUG END " + Instant.now() + " ===");
            writer.flush();
            writer.close();
        }
    }
}
