package com.osman.ui.labelfinder;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

final class ImageComposition {
    static final Color COMBINED_BACKGROUND = Color.WHITE;
    static final Color LABEL_BORDER = Color.RED;
    static final Color SLIP_BORDER = new Color(0, 120, 215);

    private ImageComposition() {
    }

    static List<BufferedImage> withBorder(List<BufferedImage> images, Color borderColor, int thickness) {
        List<BufferedImage> out = new ArrayList<>();
        if (images == null) {
            return out;
        }
        for (BufferedImage image : images) {
            out.add(addBorder(image, borderColor, thickness));
        }
        return out;
    }

    static BufferedImage stackMany(List<BufferedImage> images, int gap, Color bg) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        int maxWidth = 0;
        int totalHeight = 0;
        int count = 0;
        for (BufferedImage image : images) {
            if (image == null) {
                continue;
            }
            maxWidth = Math.max(maxWidth, image.getWidth());
            totalHeight += image.getHeight();
            count++;
        }
        if (maxWidth == 0 || totalHeight == 0) {
            return null;
        }
        totalHeight += gap * Math.max(0, count - 1);
        BufferedImage output = new BufferedImage(maxWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, maxWidth, totalHeight);
        int y = 0;
        for (BufferedImage image : images) {
            if (image == null) {
                continue;
            }
            g.drawImage(image, 0, y, null);
            y += image.getHeight() + gap;
        }
        g.dispose();
        return output;
    }

    static BufferedImage stackImagesVertically(BufferedImage top, BufferedImage bottom, int gap, Color bg) {
        if (top == null && bottom == null) {
            return null;
        }
        if (top == null) {
            return bottom;
        }
        if (bottom == null) {
            return top;
        }
        int width = Math.max(top.getWidth(), bottom.getWidth());
        int height = top.getHeight() + gap + bottom.getHeight();
        BufferedImage combined = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combined.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, width, height);
        g.drawImage(top, 0, 0, null);
        g.drawImage(bottom, 0, top.getHeight() + gap, null);
        g.dispose();
        return combined;
    }

    static BufferedImage addBorder(BufferedImage image, Color color, int thickness) {
        if (image == null) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage bordered = new BufferedImage(width + thickness * 2, height + thickness * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bordered.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, bordered.getWidth(), bordered.getHeight());
        g.drawImage(image, thickness, thickness, null);
        g.dispose();
        return bordered;
    }
}
