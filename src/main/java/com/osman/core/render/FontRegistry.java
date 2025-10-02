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
    private FontRegistry() {
    }

    public static int loadFontsFromDirectory(String fontFolderPath) throws IOException, FontFormatException {
        File fontDir = new File(fontFolderPath);
        if (!fontDir.isDirectory()) {
            throw new IOException("Font folder not found or is not a directory." + fontFolderPath);
        }

        File[] fontFiles = fontDir.listFiles((dir, name) -> {
            String lowercase = name.toLowerCase();
            return lowercase.endsWith(".ttf") || lowercase.endsWith(".otf");
        });

        if (fontFiles == null || fontFiles.length == 0) {
            throw new IOException("No font files (.ttf, .otf) were found in the specified folder " + fontFolderPath);
        }

        int loadedCount = 0;
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();

        for (File fontFile : fontFiles) {
            try {
                Font font = Font.createFont(Font.TRUETYPE_FONT, fontFile);
                ge.registerFont(font);
                loadedCount++;
            } catch (IOException | FontFormatException ignored) {
            }
        }

        if (loadedCount == 0) {
            throw new IOException("The font files in the folder could not be read or are all corrupted.");
        }
        return loadedCount;
    }
}
