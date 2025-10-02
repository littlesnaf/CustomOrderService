package com.osman.core.print;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.util.List;

/**
 * Printable that paints pre-rendered images directly to the page.
 */
public final class MugPrintable implements Printable {
    private final List<BufferedImage> pages;

    public MugPrintable(List<BufferedImage> pages) {
        this.pages = pages;
    }

    @Override
    public int print(Graphics graphics, PageFormat pageFormat, int pageIndex) throws PrinterException {
        if (pageIndex < 0 || pageIndex >= pages.size()) {
            return NO_SUCH_PAGE;
        }
        Graphics2D g2d = (Graphics2D) graphics;
        g2d.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
        BufferedImage image = pages.get(pageIndex);
        g2d.drawImage(image, 0, 0, (int) pageFormat.getImageableWidth(), (int) pageFormat.getImageableHeight(), null);
        return PAGE_EXISTS;
    }
}
