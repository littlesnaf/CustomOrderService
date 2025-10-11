package com.osman.integration.amazon;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.osman.integration.amazon.ItemTypeCategorizer;

/**
 * Generates simple packing slip PDFs from parsed Amazon order data.
 */
public class AmazonPackingSlipGenerator {

    private static final DateTimeFormatter AMAZON_DATE = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final float MARGIN = 40f;
    private static final float LINE_HEIGHT = 14f;

    /**
     * Generates packing slips for every order contained within the supplied {@link OrderBatch}.
     *
     * @param batch     grouped order batch; must not be {@code null} or empty
     * @param runRoot directory where generated PDFs should be written
     * @return list of generated PDF files
     * @throws IOException if a slip cannot be written
     */
    public List<Path> generatePackingSlips(OrderBatch batch, Path runRoot) throws IOException {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(runRoot, "runRoot");
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("Order batch is empty; nothing to generate.");
        }

        Files.createDirectories(runRoot);

        Map<String, Map<String, SlipData>> slipsByItemType = collectSlipData(batch);
        List<Path> generated = new ArrayList<>();

        for (Map.Entry<String, Map<String, SlipData>> entry : slipsByItemType.entrySet()) {
            String itemType = entry.getKey();
            Map<String, SlipData> slips = entry.getValue();
        Path itemTypeRoot = ItemTypeCategorizer.resolveItemTypeFolder(runRoot, itemType);
            Files.createDirectories(itemTypeRoot);

            // Combined slip for the item type
            Path combinedSlip = itemTypeRoot.resolve(itemType + "-packing-slips.pdf");
            writeCombinedSlip(combinedSlip, slips.values());
            generated.add(combinedSlip);

            Path imagesFolder = ItemTypeCategorizer.resolveImagesFolder(itemTypeRoot);
            Files.createDirectories(imagesFolder);

            for (SlipData slip : slips.values()) {
                String orderFolderName = slip.sanitizedBuyerName() + "_" + sanitizeOrderId(slip.orderId());
                Path orderFolder = imagesFolder.resolve(orderFolderName);
                Files.createDirectories(orderFolder);
                Path slipFile = orderFolder.resolve("packing-slip.pdf");
                writeSingleSlip(slipFile, slip);
                generated.add(slipFile);
            }
        }
        return generated;
    }

    private Map<String, Map<String, SlipData>> collectSlipData(OrderBatch batch) {
        Map<String, Map<String, SlipData>> slipsByItemType = new LinkedHashMap<>();

        for (ItemTypeGroup group : batch.groups().values()) {
            if (group.category() != ItemTypeCategorizer.Category.MUG_CUSTOM) {
                continue;
            }
            for (CustomerGroup customer : group.customers().values()) {
                for (CustomerOrder order : customer.orders().values()) {
                    for (CustomerOrderItem item : order.items()) {
                        AmazonOrderRecord record = item.sourceRecord();
                        if (record == null) {
                            continue;
                        }
                        Map<String, SlipData> slipsForItem = slipsByItemType.computeIfAbsent(group.itemType(),
                            key -> new LinkedHashMap<>());
                        SlipData slip = slipsForItem.computeIfAbsent(order.orderId(),
                            id -> SlipData.fromRecord(group.itemType(), order.orderId(), customer.sanitizedBuyerName(), record));
                        slip.addItem(record, item.orderItemId(), group.itemType());
                    }
                }
            }
        }

        return slipsByItemType;
    }

    private void writeCombinedSlip(Path target, Iterable<SlipData> slips) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (SlipData slip : slips) {
                appendSlipPage(document, slip);
            }
            document.save(target.toFile());
        }
    }

    private void writeSingleSlip(Path target, SlipData slip) throws IOException {
        try (PDDocument document = new PDDocument()) {
            appendSlipPage(document, slip);
            document.save(target.toFile());
        }
    }

    private void appendSlipPage(PDDocument document, SlipData slip) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);

        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            float y = page.getMediaBox().getHeight() - MARGIN;
            stream.setFont(PDType1Font.HELVETICA_BOLD, 18);
            y = writeLine(stream, "Packing Slip", MARGIN, y);

            stream.setFont(PDType1Font.HELVETICA, 12);
            y -= LINE_HEIGHT;
            y = writeLine(stream, "Order ID: " + slip.orderId(), MARGIN, y);
            if (!slip.purchaseDate().isEmpty()) {
                y = writeLine(stream, "Purchase Date: " + slip.purchaseDate(), MARGIN, y);
            }
            if (!slip.shipService().isEmpty()) {
                y = writeLine(stream, "Shipping Service: " + slip.shipService(), MARGIN, y);
            }

            y -= LINE_HEIGHT;
            y = writeLine(stream, "Ship To:", MARGIN, y);
            stream.setFont(PDType1Font.HELVETICA_BOLD, 12);
            y = writeLine(stream, slip.recipientName(), MARGIN + 14, y);
            stream.setFont(PDType1Font.HELVETICA, 12);
            for (String line : slip.addressLines()) {
                if (!line.isBlank()) {
                    y = writeLine(stream, line, MARGIN + 14, y);
                }
            }
            if (!slip.contactInfo().isEmpty()) {
                y = writeLine(stream, slip.contactInfo(), MARGIN + 14, y);
            }

            y -= LINE_HEIGHT;
            stream.setFont(PDType1Font.HELVETICA_BOLD, 12);
            y = writeLine(stream, "Items:", MARGIN, y);
            stream.setFont(PDType1Font.HELVETICA, 12);
            for (ItemLine line : slip.items()) {
                String qtyLine = "- Qty " + line.quantity() + " • " + line.productName();
                y = writeWrappedText(stream, qtyLine, MARGIN + 14, y, 500);
                String detail = "  Item ID: " + line.orderItemId();
                if (!line.sku().isEmpty()) {
                    detail += " | SKU: " + line.sku();
                }
                y = writeWrappedText(stream, detail, MARGIN + 14, y, 500);
                y -= LINE_HEIGHT / 2;
            }
        }
    }

    private float writeLine(PDPageContentStream stream, String text, float x, float currentY) throws IOException {
        stream.beginText();
        stream.newLineAtOffset(x, currentY);
        stream.showText(text);
        stream.endText();
        return currentY - LINE_HEIGHT;
    }

    private float writeWrappedText(PDPageContentStream stream,
                                   String text,
                                   float x,
                                   float currentY,
                                   float maxWidth) throws IOException {
        if (text == null || text.isEmpty()) {
            return currentY;
        }
        List<String> lines = wrapText(text, maxWidth);
        for (String line : lines) {
            currentY = writeLine(stream, line, x, currentY);
        }
        return currentY;
    }

    private List<String> wrapText(String text, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            float width = PDType1Font.HELVETICA.getStringWidth(candidate) / 1000 * 12;
            if (width > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                if (current.isEmpty()) {
                    current.append(word);
                } else {
                    current.append(' ').append(word);
                }
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static String safeJoinAddress(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }

    private static String sanitizeOrderId(String orderId) {
        return orderId.replaceAll("[^A-Za-z0-9-]", "_");
    }

    private static String formatPurchaseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return DISPLAY_DATE.format(AMAZON_DATE.parse(raw, LocalDate::from));
        } catch (DateTimeParseException ex) {
            return raw;
        }
    }

    private record ItemLine(String orderItemId, String productName, int quantity, String sku) {
    }

    private static final class SlipData {
        private final String itemType;
        private final String orderId;
        private final String purchaseDate;
        private final String buyerEmail;
        private final String buyerPhone;
        private final String recipientName;
        private final String shipAddress1;
        private final String shipAddress2;
        private final String shipAddress3;
        private final String shipCity;
        private final String shipState;
        private final String shipPostalCode;
        private final String shipCountry;
        private final String shipServiceLevel;
        private final String shipServiceName;
        private final String sanitizedBuyerName;
        private final List<ItemLine> items = new ArrayList<>();

        private SlipData(String itemType,
                         String orderId,
                         String purchaseDate,
                         String buyerEmail,
                         String buyerPhone,
                         String recipientName,
                         String shipAddress1,
                         String shipAddress2,
                         String shipAddress3,
                         String shipCity,
                         String shipState,
                         String shipPostalCode,
                         String shipCountry,
                         String shipServiceLevel,
                         String shipServiceName,
                         String sanitizedBuyerName) {
            this.itemType = itemType;
            this.orderId = orderId;
            this.purchaseDate = purchaseDate;
            this.buyerEmail = buyerEmail;
            this.buyerPhone = buyerPhone;
            this.recipientName = recipientName;
            this.shipAddress1 = shipAddress1;
            this.shipAddress2 = shipAddress2;
            this.shipAddress3 = shipAddress3;
            this.shipCity = shipCity;
            this.shipState = shipState;
            this.shipPostalCode = shipPostalCode;
            this.shipCountry = shipCountry;
            this.shipServiceLevel = shipServiceLevel;
            this.shipServiceName = shipServiceName;
            this.sanitizedBuyerName = sanitizedBuyerName;
        }

        static SlipData fromRecord(String itemType,
                                   String orderId,
                                   String sanitizedBuyerName,
                                   AmazonOrderRecord record) {
            return new SlipData(
                itemType,
                orderId,
                formatPurchaseDate(record.purchaseDate()),
                record.buyerEmail(),
                record.buyerPhoneNumber(),
                fallback(record.recipientName(), record.buyerName()),
                record.shipAddress1(),
                record.shipAddress2(),
                record.shipAddress3(),
                record.shipCity(),
                record.shipState(),
                record.shipPostalCode(),
                record.shipCountry(),
                record.shipServiceLevel(),
                record.shipServiceName(),
                sanitizedBuyerName
            );
        }

        void addItem(AmazonOrderRecord record, String orderItemId, String sku) {
            int quantity = record.quantityPurchased() > 0 ? record.quantityPurchased() : Math.max(1, record.quantityShipped());
            items.add(new ItemLine(orderItemId, fallback(record.productName(), record.rawItemType()), quantity, sku));
        }

        String orderId() {
            return orderId;
        }

        String itemType() {
            return itemType;
        }

        String purchaseDate() {
            return purchaseDate;
        }

        String sanitizedBuyerName() {
            return sanitizedBuyerName;
        }

        String recipientName() {
            return recipientName.isBlank() ? "Customer" : recipientName;
        }

        List<String> addressLines() {
            String line1 = shipAddress1;
            String line2 = shipAddress2;
            String line3 = shipAddress3;
            String cityStateZip = safeJoinAddress(shipCity, shipState, shipPostalCode);
            String countryLine = shipCountry;

            return List.of(line1, line2, line3, cityStateZip, countryLine);
        }

        String contactInfo() {
            StringBuilder builder = new StringBuilder();
            if (!buyerPhone.isBlank()) {
                builder.append("Phone: ").append(buyerPhone);
            }
            if (!buyerEmail.isBlank()) {
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append("Email: ").append(buyerEmail);
            }
            return builder.toString();
        }

        String shipService() {
            if (!shipServiceLevel.isBlank() && !shipServiceName.isBlank()) {
                return shipServiceLevel + " - " + shipServiceName;
            }
            return !shipServiceLevel.isBlank() ? shipServiceLevel : shipServiceName;
        }

        List<ItemLine> items() {
            return items;
        }

        private static String fallback(String preferred, String fallback) {
            return (preferred == null || preferred.isBlank()) ? (fallback == null ? "" : fallback) : preferred;
        }
    }
}
