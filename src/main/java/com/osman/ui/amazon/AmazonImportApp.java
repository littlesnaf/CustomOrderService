package com.osman.ui.amazon;

import javax.swing.SwingUtilities;

/**
 * Entry point for the Amazon TXT import UI.
 */
public final class AmazonImportApp {
    private AmazonImportApp() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            AmazonImportFrame frame = new AmazonImportFrame();
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
