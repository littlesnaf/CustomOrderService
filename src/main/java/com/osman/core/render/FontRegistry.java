package com.osman.core.render;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;

/**
 * Registers custom font files for use during rendering.
 */
public final class FontRegistry {
    private FontRegistry() { }

    /**
     * Loads and registers all supported font files in the given directory.
     * Supports .ttf, .otf, .ttc.
     *
     * @param fontFolderPath path to a directory containing font files
     * @return number of fonts successfully registered (file-level count)
     * @throws IOException if the directory is missing/invalid or nothing could be loaded
     * @throws FontFormatException if a font file is present but unreadable/corrupted
     */
    public static int loadFontsFromDirectory(String fontFolderPath) throws IOException, FontFormatException {
        File fontDir = new File(fontFolderPath);
        if (!fontDir.isDirectory()) {
            throw new IOException("Font folder not found or is not a directory: " + fontFolderPath);
        }

        File[] fontFiles = fontDir.listFiles((dir, name) -> {
            String lowercase = name.toLowerCase();
            return lowercase.endsWith(".ttf") || lowercase.endsWith(".otf") || lowercase.endsWith(".ttc");
        });

        if (fontFiles == null || fontFiles.length == 0) {
            throw new IOException("No font files (.ttf, .otf, .ttc) were found in: " + fontFolderPath);
        }

        int loadedCount = 0;
        for (File fontFile : fontFiles) {
            try {
                loadedCount += loadFontFile(fontFile.getAbsolutePath());
            } catch (IOException | FontFormatException ignored) {
                // Skip unreadable fonts but continue with others.
            }
        }

        if (loadedCount == 0) {
            throw new IOException("Font files were found but none could be registered in: " + fontFolderPath);
        }
        return loadedCount;
    }

    /**
     * Loads and registers a single font file (.ttf/.otf/.ttc).
     *
     * @param fontFilePath absolute or relative path to a font file
     * @return 1 if at least one font face was registered from the file, 0 otherwise
     * @throws IOException if the file does not exist or cannot be read
     * @throws FontFormatException if the file is not a valid font
     */
    public static int loadFontFile(String fontFilePath) throws IOException, FontFormatException {
        File fontFile = new File(fontFilePath);
        if (!fontFile.isFile()) {
            throw new IOException("Font file not found: " + fontFilePath);
        }

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();

        // Try as TrueType/OpenType first (covers .ttf/.otf and most .ttc on modern JDKs)
        try {
            Font font = Font.createFont(Font.TRUETYPE_FONT, fontFile);
            ge.registerFont(font);
            return 1;
        } catch (IOException | FontFormatException e) {
            // Fallback: some PostScript Type 1 fonts
            try {
                Font fontPs = Font.createFont(Font.TYPE1_FONT, fontFile);
                ge.registerFont(fontPs);
                return 1;
            } catch (IOException | FontFormatException e2) {
                // Propagate the original error to signal failure for this file
                throw e;
            }
        }
    }
}
