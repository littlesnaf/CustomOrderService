package com.osman.ui.labelfinder;

import javax.swing.SwingUtilities;

/**
 * Entry point for launching the Label Finder UI.
 */
public final class LabelFinderApp {

    private LabelFinderApp() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            LabelFinderFrame frame = new LabelFinderFrame();
            frame.setVisible(true);
        });
    }
}
