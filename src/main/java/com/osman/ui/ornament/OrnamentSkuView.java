package com.osman.ui.ornament;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

/**
 * Legacy frame wrapper for the ornament SKU workflow.
 */
public class OrnamentSkuView extends JFrame {

    public OrnamentSkuView() {
        super("Ornament SKU Splitter");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setContentPane(new OrnamentSkuPanel());
        setSize(900, 600);
        setLocationRelativeTo(null);
    }
}
