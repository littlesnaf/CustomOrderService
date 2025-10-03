package com.osman.core.render;

import com.osman.logging.AppLogger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges to the local ImageMagick installation so we can normalize source artwork before compositing.
 */
public final class ImageMagickAdapter {
    private static final Logger LOGGER = AppLogger.get();

    private ImageMagickAdapter() {
    }

    public static BufferedImage sanitize(File imageFile) {
        if (imageFile == null || !imageFile.exists()) {
            LOGGER.warning(() -> "No source for ImageMagick " + imageFile);
            return null;
        }
        try {
            File tempFile = File.createTempFile("magick_out_", ".png");
            tempFile.deleteOnExit();

            String outputFormat = "PNG32:" + tempFile.getAbsolutePath();
            ProcessBuilder builder = new ProcessBuilder(
                    "magick",
                    imageFile.getAbsolutePath(),
                    "-auto-orient",
                    "-colorspace", "sRGB",
                    "-alpha", "on",
                    "-depth", "8",
                    "-define", "png:color-type=6",
                    "-define", "png:bit-depth=8",
                    "-strip",
                    outputFormat
            );
            Process process = builder.start();
            try (var in = process.getInputStream()) {
                in.readAllBytes();
            } catch (Exception ignored) {
            }
            int exitCode = process.waitFor();
            if (exitCode == 0 && tempFile.exists() && tempFile.length() > 0) {
                return ImageIO.read(tempFile);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to sanitize image with ImageMagick", e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "ImageMagick sanitization interrupted", e);
        }
        return null;
    }
}
