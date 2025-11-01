package com.osman.ui.labelfinder;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.io.IOException;
import java.util.List;

final class PdfAndImagePrintable implements Printable {
    private final List<PageRenderSource> sources;
    private final BufferedImage fallback;
    private final int dpi;

    PdfAndImagePrintable(List<PageRenderSource> sources, BufferedImage fallback, int dpi) {
        this.sources = (sources == null) ? List.of() : List.copyOf(sources);
        this.fallback = fallback;
        this.dpi = dpi;
    }

    @Override
    public int print(Graphics g, PageFormat pf, int pageIndex) throws PrinterException {
        int extra = (fallback != null) ? 1 : 0;
        int total = sources.size() + extra;
        if (pageIndex < 0 || pageIndex >= total) {
            return NO_SUCH_PAGE;
        }

        BufferedImage image;
        if (pageIndex < sources.size()) {
            PageRenderSource source = sources.get(pageIndex);
            try {
                image = PdfPageRenderCache.getOrRenderPage(source.file(), source.pageIndexZeroBased(), dpi);
            } catch (IOException ex) {
                throw new PrinterException("Failed to render page: " + ex.getMessage());
            }
        } else {
            image = fallback;
        }

        if (image == null) {
            return NO_SUCH_PAGE;
        }

        Graphics2D g2d = (Graphics2D) g;
        g2d.translate(pf.getImageableX(), pf.getImageableY());
        double pw = pf.getImageableWidth();
        double ph = pf.getImageableHeight();
        double scale = Math.min(pw / image.getWidth(), ph / image.getHeight());
        int dw = (int) Math.floor(image.getWidth() * scale);
        int dh = (int) Math.floor(image.getHeight() * scale);
        int dx = (int) ((pw - dw) / 2.0);
        int dy = (int) ((ph - dh) / 2.0);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.drawImage(image, dx, dy, dw, dh, null);
        return PAGE_EXISTS;
    }
}
