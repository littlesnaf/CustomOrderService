package com.osman.core.model;

import java.io.File;

/**
 * Associates a PDF file with a specific page that contains the shipping label.
 */
public record LabelMatch(File pdfFile, int pageNumber) {
}
