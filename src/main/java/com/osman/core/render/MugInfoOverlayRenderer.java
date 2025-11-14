package com.osman.core.render;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.osman.core.model.OrderInfo;
import com.osman.core.render.TemplateRegistry.MugTemplate;
import org.apache.xmlgraphics.image.codec.png.PNGEncodeParam;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Locale;

final class MugInfoOverlayRenderer {

    private MugInfoOverlayRenderer() {
    }

    static void drawInfoAndBarcode(Graphics2D g2d,
                                   OrderInfo orderInfo,
                                   String sideLabel,
                                   MugTemplate template,
                                   int totalOrderQuantity) {
        Color labelColor = pickTextColor(orderInfo.getLabel());
        Font infoFont = new Font("Arial", Font.BOLD, 48);
        Font orderIdFont = infoFont.deriveFont(infoFont.getSize2D() * 1.1f);
        g2d.setColor(new Color(0x95ACD1));

        int baseOffset = template.finalHeight >= 1500 ? 160 : 190;
        int infoBoxY = template.finalHeight - baseOffset + 30;
        int infoBoxX = 158;
        int infoBoxWidth = 2330;
        int infoBoxHeight = 146;

        FontMetrics fm = g2d.getFontMetrics(infoFont);
        int lineHeight = fm.getHeight();
        int baselineCenter = infoBoxY + (infoBoxHeight - (2 * lineHeight)) / 2 + fm.getAscent() - 30;
        int ascent = fm.getAscent();
        int descent = fm.getDescent();
        int correction = (ascent - descent) / 2;
        int alignmentOffset = -8;

        int yLine1 = baselineCenter - correction + alignmentOffset;
        int yLine2 = yLine1 + lineHeight - 25;
        int yLine2OrderId = yLine2 + 20;
        int yRightLine1 = yLine1;
        int yRightLine2 = yLine2 + 20;

        String qtyPart = "Order Q-ty: " + totalOrderQuantity;
        String sidePart = (sideLabel == null || sideLabel.isBlank()) ? "" : " " + sideLabel;
        drawMirroredString(g2d, qtyPart + sidePart, infoBoxX, yLine1, infoFont, false);
        drawMirroredString(g2d, orderInfo.getOrderId(), infoBoxX, yLine2OrderId, orderIdFont, false);

        int rightEdge = infoBoxX + infoBoxWidth;
        String orderItemId = orderInfo.getOrderItemId() == null ? "" : orderInfo.getOrderItemId();
        String itemIdLast4 = orderItemId.length() <= 4 ? orderItemId : orderItemId.substring(orderItemId.length() - 4);
        drawMirroredString(g2d,
            "ID: " + itemIdLast4 + "  ID Qty: " + orderInfo.getQuantity(),
            rightEdge,
            yRightLine1,
            infoFont,
            true);
        drawMirroredString(g2d, orderInfo.getLabel(), rightEdge, yRightLine2, infoFont, true, labelColor);

        String barcodePayload = buildBarcodePayload(orderInfo);
        float barcodeOpacity = 1.0f; // 0 (transparent) -> 1 (opaque)
        Color barcodeColor = withOpacity(new Color(0x4f4f4f), barcodeOpacity);
        Color barcodeBackgroundColor = Color.WHITE;
        BufferedImage barcode = generateBarcodeWithText(
            barcodePayload,
            1000,
            120,
            barcodeColor,
            barcodeBackgroundColor);
        int barcodeX = infoBoxX + (infoBoxWidth - barcode.getWidth()) / 2;
        int barcodeCenterY = infoBoxY + infoBoxHeight / 2-20;
        int barcodeY = barcodeCenterY - barcode.getHeight() / 2;
        g2d.drawImage(barcode, barcodeX, barcodeY, null);
    }

    private static Color pickTextColor(String label) {
        if (label == null) {
            return Color.BLACK;
        }
        String lowered = label.toLowerCase(Locale.ROOT);
        if (lowered.contains("black")) {
            return Color.GRAY;

        }
        if (lowered.contains("white")) {
            return new Color(0x95ACD1) ;
        }
        if (lowered.contains("pink")) {
            return new Color(0xFF2AA5);
        }
        if (lowered.contains("red")) {
            return Color.RED;
        }
        if (lowered.contains("navy")) {
            return new Color(0x1E3A8A);
        }
        if (lowered.contains("blue")) {
            return Color.BLUE;
        }
        return Color.BLACK;
    }

    private static void drawMirroredString(Graphics2D g2d, String text, int x, int y, Font font, boolean alignRight) {
        drawMirroredString(g2d, text, x, y, font, alignRight, g2d.getColor());
    }

    private static void drawMirroredString(Graphics2D g2d, String text, int x, int y, Font font, boolean alignRight, Color color) {
        AffineTransform original = g2d.getTransform();
        Color previous = g2d.getColor();
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int drawX = alignRight ? (x - textWidth) : x;
        int baselineY = y;
        g2d.translate(drawX, baselineY);
        g2d.scale(1, -1);
        g2d.setColor(color);
        g2d.drawString(text, 0, 0);
        g2d.setColor(previous);
        g2d.setTransform(original);
    }

    private static String buildBarcodePayload(OrderInfo orderInfo) {
        if (orderInfo == null) {
            return "";
        }
        String orderId = orderInfo.getOrderId();
        if (orderId == null) {
            orderId = "";
        }
        String suffix = reduceOrderItemId(orderInfo.getOrderItemId());
        if (suffix == null || suffix.isBlank()) {
            return orderId;
        }
        return orderId + "^" + suffix;
    }

    private static String reduceOrderItemId(String orderItemId) {
        if (orderItemId == null) {
            return null;
        }
        String trimmed = orderItemId.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - 4);
    }

    private static BufferedImage generateBarcodeWithText(String text, int width, int height) {
        return generateBarcodeWithText(text, width, height, Color.BLACK, Color.WHITE);
    }

    private static BufferedImage generateBarcodeWithText(String text,
                                                         int width,
                                                         int height,
                                                         Color barColor,
                                                         Color backgroundColor) {
        try {
            Code128Writer writer = new Code128Writer();
            BitMatrix matrix = writer.encode(text, BarcodeFormat.CODE_128, width, height);
            Color resolvedBarColor = barColor == null ? Color.BLACK : barColor;
            Color resolvedBackground = backgroundColor == null ? Color.WHITE : backgroundColor;
            MatrixToImageConfig config = new MatrixToImageConfig(
                resolvedBarColor.getRGB(),
                resolvedBackground.getRGB()
            );
            return MatrixToImageWriter.toBufferedImage(matrix, config);
        } catch (Exception e) {
            BufferedImage err = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = err.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.RED);
            g.drawString("BC-ERR", 10, 20);
            g.dispose();
            return err;
        }
    }

    private static Color withOpacity(Color color, float opacity) {
        Color base = color == null ? Color.BLACK : color;
        float clamped = Math.max(0f, Math.min(1f, opacity));
        int alpha = Math.round(255 * clamped);
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }
}
