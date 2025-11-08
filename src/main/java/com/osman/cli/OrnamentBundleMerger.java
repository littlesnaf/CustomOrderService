package com.osman.cli;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

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
        merge(singlePagesPerDoc, bundles, outFile, null);
    }

    public static void merge(List<List<Path>> singlePagesPerDoc,
                             List<? extends BundlePages> bundles,
                             Path outFile,
                             SummaryPage summaryPage) throws IOException {
        if (bundles == null || bundles.isEmpty()) {
            return;
        }
        PDFMergerUtility mu = new PDFMergerUtility();
        mu.setDestinationFileName(outFile.toString());
        for (BundlePages ref : bundles) {
            List <Path> files = singlePagesPerDoc.get(ref.docId());
            mu.addSource(files.get(ref.labelPageIndex()).toFile());
            for (Integer pageIndex : ref.slipPageIndices()) {
                mu.addSource(files.get(pageIndex).toFile());
            }
        }
        if (summaryPage != null) {
            mu.addSource(new ByteArrayInputStream(createSummaryPage(summaryPage)));
        }
        mu.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
    }

    private static byte[] createSummaryPage(SummaryPage summaryPage) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDRectangle summarySize = new PDRectangle(4 * 72, 6 * 72); // 4x6 inches
            PDPage page = new PDPage(summarySize);
            doc.addPage(page);
            PDRectangle box = page.getMediaBox();
            float centerX = box.getWidth() / 2f;
            float centerY = box.getHeight() / 2f;
            float skuFontSize = 42f;
            float qtyFontSize = 32f;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, skuFontSize);
                String skuLine = summaryPage.sku();
                float skuWidth = PDType1Font.HELVETICA_BOLD.getStringWidth(skuLine) / 1000f * skuFontSize;
                cs.newLineAtOffset(centerX - skuWidth / 2f, centerY + 24f);
                cs.showText(skuLine);
                cs.endText();

                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, qtyFontSize);
                String qtyLine = String.format(Locale.ROOT, "Qty: %d", summaryPage.quantity());
                float qtyWidth = PDType1Font.HELVETICA.getStringWidth(qtyLine) / 1000f * qtyFontSize;
                cs.newLineAtOffset(centerX - qtyWidth / 2f, centerY - 32f);
                cs.showText(qtyLine);
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    public record SummaryPage(String sku, int quantity) {}
}
