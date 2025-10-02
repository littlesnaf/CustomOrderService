package com.osman.core.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Bridges to the local ImageMagick installation so we can normalize source artwork before compositing.
 */
public final class ImageMagickAdapter {
    private ImageMagickAdapter() {
    }

    public static BufferedImage sanitize(File imageFile) {
        if (imageFile == null || !imageFile.exists()) {
            System.err.println("No source for ImageMagick " + imageFile);
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
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }
}
