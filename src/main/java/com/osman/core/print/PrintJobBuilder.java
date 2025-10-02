package com.osman.core.print;

import java.awt.print.PageFormat;
import java.awt.print.PrinterJob;

/**
 * Convenience wrapper for assembling a print job with shared settings.
 */
public final class PrintJobBuilder {
    private final PrinterJob job;
    private PageFormat pageFormat;

    public PrintJobBuilder() {
        this.job = PrinterJob.getPrinterJob();
    }

    public PrintJobBuilder withPageFormat(PrintSettings settings) {
        this.pageFormat = settings.createPageFormat();
        return this;
    }

    public PrinterJob getJob() {
        return job;
    }

    public PageFormat getPageFormat() {
        return pageFormat;
    }
}
