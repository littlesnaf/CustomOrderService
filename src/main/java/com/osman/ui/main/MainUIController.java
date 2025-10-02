package com.osman.ui.main;

import javax.swing.SwingUtilities;

/**
 * Thin controller that owns the lifecycle of the main desktop UI.
 */
public final class MainUIController {
    private final MainUIView view;

    public MainUIController() {
        this.view = new MainUIView();
    }

    public MainUIView getView() {
        return view;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainUIController());
    }
}
