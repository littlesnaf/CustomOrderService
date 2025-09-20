package com.osman;

import java.io.File;

/**
 * Central application configuration that exposes default file-system locations
 * for fonts and shipping label PDFs.
 */
public final class
Config {
    private Config() {}

    // Varsayılan font klasörü: kullanıcı isterse burayı proje çalışırken değiştirebilir.
    public static final String DEFAULT_FONT_DIR =
            System.getProperty("osman.fontDir", File.separator + "Users" + File.separator + "murattuncel" + File.separator + "Desktop" + File.separator + "Fonts");

    // Label bulucu için istenirse varsayılan PDF yolu (UI üzerinden de seçilebilir).
    public static final String DEFAULT_LABELS_PDF =
            System.getProperty("osman.labelsPdf", File.separator + "Users" + File.separator + "murattuncel" + File.separator + "Desktop" + File.separator + "labels.pdf");
}
