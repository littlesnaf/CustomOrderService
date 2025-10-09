package com.osman.cli;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared helper for turning page bundles into merged PDFs.
 */
public final class OrnamentBundleMerger {
    private OrnamentBundleMerger() {
    }

    public interface BundlePages {
        int docId();

        int labelPageIndex();

        List<Integer> slipPageIndices();
    }

    public static void merge(List<List<Path>> singlePagesPerDoc,
                              List<? extends BundlePages> bundles,
                              Path outFile) throws IOException {
        if (bundles == null || bundles.isEmpty()) {
            return;
        }
        PDFMergerUtility mu = new PDFMergerUtility();
        mu.setDestinationFileName(outFile.toString());
        for (BundlePages ref : bundles) {
            List<Path> files = singlePagesPerDoc.get(ref.docId());
            mu.addSource(files.get(ref.labelPageIndex()).toFile());
            for (Integer pageIndex : ref.slipPageIndices()) {
                mu.addSource(files.get(pageIndex).toFile());
            }
        }
        mu.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
    }
}
