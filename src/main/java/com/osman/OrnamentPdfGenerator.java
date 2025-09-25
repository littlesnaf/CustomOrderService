package com.osman;

import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Finds matching orders between a shipping label PDF and a packing slip PDF
 * and merges them into a single master document.
 */
public class OrnamentPdfGenerator {

    private final Consumer<String> logger;

    /**
     * @param logger A consumer to send log messages to (e.g., a UI text area).
     */
    public OrnamentPdfGenerator(Consumer<String> logger) {
        this.logger = logger != null ? logger : (s -> {}); // Avoid NullPointerException
    }

    public void generateMasterPdf(File shippingLabelPdf, File packingSlipPdf, File outputPdf) throws IOException {
        logger.accept("Starting PDF processing...");
        logger.accept("Shipping Labels: " + shippingLabelPdf.getName());
        logger.accept("Packing Slips: " + packingSlipPdf.getName());

        // Step 1: Index both PDFs to find which pages belong to which Order ID
        logger.accept("\nIndexing shipping labels by Order ID...");
        Map<String, List<Integer>> shippingLabelMap = PdfLinker.buildOrderIdToPagesMap(shippingLabelPdf);
        logger.accept(" -> Found " + shippingLabelMap.size() + " unique orders in shipping labels.");

        logger.accept("Indexing packing slips by Order ID...");
        Map<String, List<Integer>> packingSlipMap = PackSlipExtractor.indexOrderToPages(packingSlipPdf);
        logger.accept(" -> Found " + packingSlipMap.size() + " unique orders in packing slips.");

        // Step 2: Load documents and prepare for merging
        try (PDDocument shippingDoc = PDDocument.load(shippingLabelPdf);
             PDDocument packingDoc = PDDocument.load(packingSlipPdf);
             PDDocument outputDoc = new PDDocument()) {

            logger.accept("\nMerging documents one by one...");
            int matchCount = 0;
            int noMatchCount = 0;

            // We iterate through the shipping labels as the primary source of truth
            for (String orderId : shippingLabelMap.keySet()) {
                List<Integer> labelPages = shippingLabelMap.get(orderId);
                List<Integer> slipPages = packingSlipMap.get(orderId);

                if (slipPages != null && !slipPages.isEmpty()) {
                    // Match found! Add the pages to the new document.
                    matchCount++;
                    logger.accept("  - Matched Order ID: " + orderId);

                    // Add shipping label page(s)
                    for (int pageNum : labelPages) {
                        outputDoc.addPage(shippingDoc.getPage(pageNum - 1)); // Page numbers are 1-based, index is 0-based
                    }

                    // Add packing slip page(s)
                    for (int pageNum : slipPages) {
                        outputDoc.addPage(packingDoc.getPage(pageNum - 1));
                    }
                } else {
                    // No matching packing slip found for this shipping label
                    noMatchCount++;
                    logger.accept("  - WARNING: No packing slip found for Order ID: " + orderId);
                    // Optionally, you could still add the shipping label for unmatched orders
                    // for (int pageNum : labelPages) {
                    //    outputDoc.addPage(shippingDoc.getPage(pageNum - 1));
                    // }
                }
            }

            // Step 3: Save the final merged document
            logger.accept("\nSaving combined PDF to: " + outputPdf.getAbsolutePath());
            outputDoc.save(outputPdf);
            logger.accept("\nProcessing complete!");
            logger.accept("Successfully matched and merged " + matchCount + " orders.");
            if (noMatchCount > 0) {
                logger.accept(noMatchCount + " shipping labels could not be matched with a packing slip.");
            }
        }
    }
}