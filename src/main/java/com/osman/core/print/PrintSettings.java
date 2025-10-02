package com.osman.core.print;

import java.awt.print.PageFormat;
import java.awt.print.Paper;

/**
 * Encapsulates printer configuration for combined label/slip prints.
 */
public final class PrintSettings {
    private final double widthPoints;
    private final double heightPoints;

    public PrintSettings(double widthPoints, double heightPoints) {
        this.widthPoints = widthPoints;
        this.heightPoints = heightPoints;
    }

    public PageFormat createPageFormat() {
        PageFormat pageFormat = new PageFormat();
        Paper paper = new Paper();
        paper.setSize(widthPoints, heightPoints);
        paper.setImageableArea(0, 0, widthPoints, heightPoints);
        pageFormat.setPaper(paper);
        return pageFormat;
    }
}
