package com.osman;

import java.io.File;

/**
 * Central application configuration that exposes default file-system locations
 * for fonts and shipping label PDFs.
 */
public final class Config {
    private Config() {}

    public static final String DEFAULT_FONT_DIR =
            System.getProperty(
                    "fontDir",
                    System.getProperty("user.home") + File.separator + "Desktop" + File.separator + "Fonts"
            );
}
