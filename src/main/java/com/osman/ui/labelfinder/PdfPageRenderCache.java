package com.osman.ui.labelfinder;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

final class PdfPageRenderCache {

    private static final Logger LOGGER = Logger.getLogger(PdfPageRenderCache.class.getName());
    private static final ConcurrentHashMap<RenderCacheKey, CompletableFuture<BufferedImage>> CACHE = new ConcurrentHashMap<>();

    private PdfPageRenderCache() {
    }

    static BufferedImage getOrRenderPage(File pdf, int pageIndexZeroBased, int dpi) throws IOException {
        RenderCacheKey key = new RenderCacheKey(pdf, pageIndexZeroBased, dpi);
        CompletableFuture<BufferedImage> future = CACHE.computeIfAbsent(key, k ->
            CompletableFuture.supplyAsync(() -> {
                try (PDDocument doc = PDDocument.load(pdf)) {
                    PDFRenderer renderer = new PDFRenderer(doc);
                    renderer.setSubsamplingAllowed(true);
                    return renderer.renderImageWithDPI(pageIndexZeroBased, dpi);
                }
                catch (IOException ex) {
                    throw new CompletionException(ex);
                }
            }, LabelFinderUI.RENDER_EXECUTOR)
        );
        try {
            return future.get();
        }
        catch (InterruptedException e) {
            future.cancel(true);
            CACHE.remove(key, future);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while rendering PDF page", e);
        }
        catch (CancellationException e) {
            CACHE.remove(key, future);
            throw new IOException("Rendering cancelled", e);
        }
        catch (ExecutionException e) {
            CACHE.remove(key, future);
            Throwable cause = e.getCause();
            if (cause instanceof CompletionException completion && completion.getCause() != null) {
                cause = completion.getCause();
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Failed to render PDF page", cause);
        }
    }

    static List<BufferedImage> renderPages(File pdf, List<Integer> pages1Based, int dpi) {
        List<BufferedImage> out = new ArrayList<>();
        if (pdf == null || pages1Based == null || pages1Based.isEmpty()) {
            return out;
        }
        for (int p : pages1Based) {
            int pageIndexZeroBased = p - 1;
            if (pageIndexZeroBased < 0) {
                continue;
            }
            try {
                BufferedImage img = getOrRenderPage(pdf, pageIndexZeroBased, dpi);
                if (img != null) {
                    out.add(img);
                }
            }
            catch (IndexOutOfBoundsException ex) {
                LOGGER.log(Level.FINE,
                    () -> String.format("Skipping %s page %d due to invalid index: %s",
                        pdf.getName(), p, ex.getMessage()));
            }
            catch (IOException ex) {
                LOGGER.log(Level.FINE,
                    () -> String.format("Failed to render %s page %d: %s", pdf.getName(), p, ex.getMessage()));
            }
        }
        return out;
    }

    static void clear() {
        CACHE.clear();
    }

    private record RenderCacheKey(String path, int pageIndex, int dpi) {
        RenderCacheKey(File pdfFile, int pageIndex, int dpi) {
            this((pdfFile == null) ? "" : pdfFile.getAbsolutePath(), pageIndex, dpi);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RenderCacheKey other)) {
                return false;
            }
            return pageIndex == other.pageIndex && dpi == other.dpi && Objects.equals(path, other.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, pageIndex, dpi);
        }
    }
}
