package com.osman.ui.ornament;

import javax.swing.SwingUtilities;

/**
 * Controller for the ornament SKU workflow.
 */
public final class OrnamentSkuController {
    private static OrnamentSkuController INSTANCE;
    private final OrnamentSkuView view;

    public OrnamentSkuController() {
        this.view = new OrnamentSkuView();
        this.view.setVisible(true);
    }

    public OrnamentSkuView getView() {
        return view;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> INSTANCE = new OrnamentSkuController());
    }
}
