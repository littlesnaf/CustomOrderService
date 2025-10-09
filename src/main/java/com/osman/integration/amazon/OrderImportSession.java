package com.osman.integration.amazon;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * In-memory store for the most recent import batch. Allows the UI to refresh without re-reading files.
 */
public final class OrderImportSession {
    private static final OrderImportSession INSTANCE = new OrderImportSession();

    private volatile OrderBatch currentBatch = OrderBatch.empty();
    private volatile Path sourceFile;
    private volatile Instant loadedAt;

    private OrderImportSession() {
    }

    public static OrderImportSession getInstance() {
        return INSTANCE;
    }

    public synchronized void store(Path file, OrderBatch batch) {
        this.sourceFile = file;
        this.currentBatch = batch;
        this.loadedAt = Instant.now();
    }

    public Optional<OrderBatch> getCurrentBatch() {
        return Optional.ofNullable(currentBatch);
    }

    public Optional<Path> getSourceFile() {
        return Optional.ofNullable(sourceFile);
    }

    public Optional<Instant> getLoadedAt() {
        return Optional.ofNullable(loadedAt);
    }
}
