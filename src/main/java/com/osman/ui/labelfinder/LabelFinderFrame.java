package com.osman.ui.labelfinder;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Top-level frame for the Label Finder workflow. Responsible for wiring the panel,
 * window listeners, and persisting frame-specific preferences.
 */
public class LabelFinderFrame extends JFrame {

    private final LabelFinderPanel panel;

    public LabelFinderFrame() {
        super("Label & Photo Viewer");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(960, 600));

        panel = new LabelFinderPanel(this);
        setContentPane(panel);
        setSize(1600, 900);
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                panel.onWindowOpened();
            }

            @Override
            public void windowActivated(WindowEvent e) {
                panel.onWindowActivated();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                panel.onWindowMovedOrResized();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                panel.onWindowMovedOrResized();
            }
        });

        SwingUtilities.invokeLater(panel::restorePreferences);
    }

    public LabelFinderPanel panel() {
        return panel;
    }

    private void shutdown() {
        panel.onWindowClosing();
        dispose();
        System.exit(0);
    }
}
