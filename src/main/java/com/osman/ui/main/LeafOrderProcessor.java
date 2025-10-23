package com.osman.ui.main;

import com.osman.core.render.MugRenderErrorLogger;
import com.osman.core.render.MugRenderer;
import com.osman.logging.AppLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

final class LeafOrderProcessor {

    private static final Logger LOGGER = AppLogger.get();

    private final BooleanSupplier cancelRequested;
    private final Consumer<String> log;
    private final List<String> failedItems;
    private final String outputFolderName;

    LeafOrderProcessor(BooleanSupplier cancelRequested,
                       Consumer<String> log,
                       List<String> failedItems,
                       String outputFolderName) {
        this.cancelRequested = cancelRequested;
        this.log = log;
        this.failedItems = failedItems;
        this.outputFolderName = outputFolderName;
    }

    ProcessingSummary processLeaves(List<File> leafOrders,
                                    IntFunction<File> readyFolderProvider,
                                    AtomicInteger orderSequence,
                                    String customerNameForFile,
                                    File contextFolder) {
        if (leafOrders.isEmpty()) {
            return ProcessingSummary.empty();
        }

        if (leafOrders.size() > 1) {
            log.accept("  -> Multiple orders detected (" + leafOrders.size() + " folders).");
        } else {
            log.accept("  -> Single order folder detected.");
        }

        AtomicInteger okCounter = new AtomicInteger();
        AtomicInteger failCounter = new AtomicInteger();

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "RenderPool-Worker");
            t.setDaemon(true);
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(4, tf);
        List<Future<?>> futures = new ArrayList<>();

        for (File subFolder : leafOrders) {
            if (cancelRequested.getAsBoolean()) {
                break;
            }

            if (shouldSkip(subFolder.getName())) {
                log.accept("    -> Container folder skipped: " + subFolder.getName());
                continue;
            }

            final int orderIndex = orderSequence.getAndIncrement();
            final File readyFolder = readyFolderProvider.apply(orderIndex);

            futures.add(pool.submit(() -> processSingleLeaf(subFolder, readyFolder, customerNameForFile, contextFolder, okCounter, failCounter)));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception ignored) {
            }
        }
        pool.shutdown();

        return new ProcessingSummary(okCounter.get() + failCounter.get(), okCounter.get(), failCounter.get());
    }

    boolean processAsMulti(File folder,
                           IntFunction<File> readyFolderProvider,
                           AtomicInteger orderSequence,
                           String customerNameForFile,
                           File contextFolder) {
        try {
            File readyFolder = readyFolderProvider.apply(orderSequence.getAndIncrement());
            List<String> results = MugRenderer.processOrderFolderMulti(folder, readyFolder, customerNameForFile, null);
            for (String path : results) {
                log.accept("  -> OK: " + new File(path).getName());
            }
            return true;
        } catch (Exception ex) {
            String errorMsg = "  -> CRITICAL (" + contextFolder.getName() + "): " + ex.getMessage();
            log.accept(errorMsg);
            String summary = contextFolder.getName() + " - Reason: " + ex.getMessage();
            failedItems.add(summary);
            LOGGER.log(Level.SEVERE, summary, ex);
            MugRenderErrorLogger.logFailure(
                "multi",
                contextFolder.toPath(),
                folder != null ? folder.toPath() : null,
                null,
                customerNameForFile,
                ex
            );
            return false;
        }
    }

    private void processSingleLeaf(File subFolder,
                                   File readyFolder,
                                   String customerNameForFile,
                                   File contextFolder,
                                   AtomicInteger okCounter,
                                   AtomicInteger failCounter) {
        try {
            List<String> results = MugRenderer.processOrderFolderMulti(subFolder, readyFolder, customerNameForFile, null);
            for (String path : results) {
                log.accept("    -> OK: " + subFolder.getName() + " -> " + new File(path).getName());
            }
            okCounter.incrementAndGet();
        } catch (Exception ex) {
            String errorMsg = "    -> ERROR processing " + subFolder.getName() + ": " + ex.getMessage();
            log.accept(errorMsg);
            String summary = contextFolder.getName() + "/" + subFolder.getName() + " - Reason: " + ex.getMessage();
            failedItems.add(summary);
            LOGGER.log(Level.SEVERE, summary, ex);
            MugRenderErrorLogger.logFailure(
                "leaf",
                contextFolder != null ? contextFolder.toPath() : null,
                subFolder != null ? subFolder.toPath() : null,
                readyFolder != null ? readyFolder.toPath() : null,
                customerNameForFile,
                ex
            );
            failCounter.incrementAndGet();
        }
    }

    private boolean shouldSkip(String folderName) {
        return folderName.equalsIgnoreCase(outputFolderName)
            || folderName.equalsIgnoreCase("images")
            || folderName.equalsIgnoreCase("img");
    }

    record ProcessingSummary(int processed, int succeeded, int failed) {
        static ProcessingSummary empty() {
            return new ProcessingSummary(0, 0, 0);
        }
    }
}
