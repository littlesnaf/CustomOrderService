package com.osman.cli;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared helper for turning page bundles into merged PDFs.
 */
public final class OrnamentBundleMerger {
    private static final PDRectangle SUMMARY_PAGE_SIZE = new PDRectangle(4 * 72, 6 * 72); // 4x6 inches
    private static final Map<String, String> ORNAMENT_BIN_ADDRESSES = loadOrnamentBinAddresses();

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
            mu.addSource(new ByteArrayInputStream(createBlankSummaryPage()));
            mu.addSource(new ByteArrayInputStream(createSummaryPage(summaryPage)));
        }
        mu.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
    }

    private static byte[] createSummaryPage(SummaryPage summaryPage) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(SUMMARY_PAGE_SIZE);
            doc.addPage(page);
            PDRectangle box = page.getMediaBox();
            float centerX = box.getWidth() / 2f;
            float centerY = box.getHeight() / 2f;
            float skuFontSize = 34f;
            float qtyFontSize = 32f;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, skuFontSize);
                String titleLine = summaryPage.title();
                float skuWidth = PDType1Font.HELVETICA_BOLD.getStringWidth(titleLine) / 1000f * skuFontSize;
                cs.newLineAtOffset(centerX - skuWidth / 2f, centerY + 24f);
                cs.showText(titleLine);
                cs.endText();

                if (summaryPage.showQuantity()) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, qtyFontSize);
                    String qtyLine = String.format(Locale.ROOT, "Qty: %d", summaryPage.quantity());
                    float qtyWidth = PDType1Font.HELVETICA.getStringWidth(qtyLine) / 1000f * qtyFontSize;
                    cs.newLineAtOffset(centerX - qtyWidth / 2f, centerY - 32f);
                    cs.showText(qtyLine);
                    cs.endText();
                }

                if (summaryPage.showBinAddress()) {
                    String binAddress = resolveBinAddress(summaryPage.title());
                    if (binAddress != null && !binAddress.isBlank()) {
                        cs.beginText();
                        float addressFontSize = 32f;
                        cs.setFont(PDType1Font.HELVETICA_OBLIQUE, addressFontSize);
                        String addressLine = binAddress;
                        float addressWidth =
                                PDType1Font.HELVETICA_OBLIQUE.getStringWidth(addressLine) / 1000f * addressFontSize;
                        cs.newLineAtOffset(centerX - addressWidth / 2f, centerY - 84f);
                        cs.showText(addressLine);
                        cs.endText();
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createBlankSummaryPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(SUMMARY_PAGE_SIZE);
            doc.addPage(page);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static String resolveBinAddress(String sku) {
        if (sku == null) {
            return null;
        }
        return ORNAMENT_BIN_ADDRESSES.get(sku.trim().toUpperCase(Locale.ROOT));
    }

    private static Map<String, String> loadOrnamentBinAddresses() {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = OrnamentBundleMerger.class.getClassLoader().getResourceAsStream("ornament_bins.csv")) {
            if (in == null) {
                return map;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                boolean headerSkipped = false;
                while ((line = reader.readLine()) != null) {
                    if (!headerSkipped) {
                        headerSkipped = true;
                        continue;
                    }
                    String[] parts = line.split(",", 2);
                    if (parts.length < 2) {
                        continue;
                    }
                    String sku = parts[0].trim();
                    String address = parts[1].trim();
                    if (!sku.isEmpty() && !address.isEmpty()) {
                        map.put(sku.toUpperCase(Locale.ROOT), address);
                    }
                }
            }
        } catch (IOException ignored) {
            // Ignore failures; when missing we simply omit the address line.
        }
        return map;
    }

    public record SummaryPage(String title, int quantity, boolean showQuantity, boolean showBinAddress) {
        public SummaryPage(String title, int quantity) {
            this(title, quantity, true, true);
        }

        public SummaryPage(String title) {
            this(title, 0, false, false);
        }
    }
}
