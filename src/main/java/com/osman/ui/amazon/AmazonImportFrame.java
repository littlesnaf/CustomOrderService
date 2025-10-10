package com.osman.ui.amazon;

import javax.swing.JFrame;
import java.awt.Dimension;

/**
 * Standalone Swing frame that hosts the {@link AmazonImportPanel}. Kept for backwards compatibility.
 */
public class AmazonImportFrame extends JFrame {

    public AmazonImportFrame() {
        super("Amazon TXT Import");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        AmazonImportPanel panel = new AmazonImportPanel();
        panel.setPreferredSize(new Dimension(960, 700));
        setContentPane(panel);
    }
}
