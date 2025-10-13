package com.osman.core.render;

import com.osman.core.render.SvgPreprocessor.ProcessedSvg;
import com.osman.core.render.TemplateRegistry.MugTemplate;
import com.osman.logging.AppLogger;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

final class MugRenderPipeline {

    private static final Logger LOGGER = AppLogger.get();

    private final MugRenderContext context;

    MugRenderPipeline(MugRenderContext context) {
        this.context = context;
    }

    String render() throws Exception {
        ProcessedSvg processedSvg = null;
        BufferedImage finalCanvas = new BufferedImage(
            context.template().finalWidth,
            context.template().finalHeight,
            BufferedImage.TYPE_INT_RGB
        );

        Graphics2D g2d = finalCanvas.createGraphics();
        try {
            setupHighQualityRendering(g2d);
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, finalCanvas.getWidth(), finalCanvas.getHeight());

            processedSvg = SvgPreprocessor.preprocess(
                context.svgFile(),
                context.orderInfo(),
                context.declaredImageNames(),
                context.tempWorkingDir()
            );

            BufferedImage masterImage = renderSvgToImage(
                processedSvg.content(),
                context.svgFile().getParentFile(),
                context.template().renderSize,
                context.template().renderSize
            );

            drawCropsToCanvas(g2d, masterImage, context.drawLeft(), context.drawRight(), context.template());
            MugInfoOverlayRenderer.drawInfoAndBarcode(
                g2d,
                context.orderInfo(),
                null,
                context.template(),
                context.totalOrderQuantity()
            );
        } finally {
            g2d.dispose();
            if (processedSvg != null) {
                processedSvg.tempFiles().forEach(MugRenderPipeline::deleteIfExists);
            }
        }

        ImageIO.write(finalCanvas, "png", context.finalOutputFile());
        return context.finalOutputFile().getAbsolutePath();
    }

    private static void setupHighQualityRendering(Graphics2D g2d) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage renderSvgToImage(String svgContent,
                                                  File baseDirectory,
                                                  float targetWidth,
                                                  float targetHeight) throws TranscoderException {
        BufferedImageTranscoder transcoder = new BufferedImageTranscoder();
        transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, targetWidth);
        transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, targetHeight);
        transcoder.addTranscodingHint(ImageTranscoder.KEY_BACKGROUND_COLOR, new Color(0, 0, 0, 0));
        transcoder.addTranscodingHint(ImageTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, true);

        TranscoderInput input = new TranscoderInput(new StringReader(svgContent));
        if (baseDirectory != null) {
            input.setURI(baseDirectory.toURI().toString());
        }
        transcoder.transcode(input, (TranscoderOutput) null);
        return transcoder.getBufferedImage();
    }

    private static void drawCropsToCanvas(Graphics2D g2d,
                                          BufferedImage masterImage,
                                          boolean drawLeft,
                                          boolean drawRight,
                                          MugTemplate template) {
        try {
            if (drawLeft) {
                BufferedImage crop1 = cropSubImage(masterImage,
                    template.crop1X, template.crop1Y,
                    template.crop1Width, template.crop1Height);
                g2d.drawImage(crop1, template.area1X, template.area1Y, template.area1Width, template.area1Height, null);
            } else {
                g2d.setColor(Color.WHITE);
                g2d.fillRect(template.area1X, template.area1Y, template.area1Width, template.area1Height);
            }

            if (drawRight) {
                BufferedImage crop2 = cropSubImage(masterImage,
                    template.crop2X, template.crop2Y,
                    template.crop2Width, template.crop2Height);
                g2d.drawImage(crop2, template.area2X, template.area2Y, template.area2Width, template.area2Height, null);
            } else {
                g2d.setColor(Color.WHITE);
                g2d.fillRect(template.area2X, template.area2Y, template.area2Width, template.area2Height);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to crop or draw mug artwork", e);
        }
    }

    private static BufferedImage cropSubImage(BufferedImage source,
                                              int x,
                                              int y,
                                              int width,
                                              int height) throws IOException {
        try {
            BufferedImage sub = source.getSubimage(x, y, width, height);
            BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = copy.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(sub, 0, 0, null);
            g.dispose();
            return copy;
        } catch (RasterFormatException e) {
            throw new IOException("Crop dimensions out of bounds", e);
        }
    }

    private static void deleteIfExists(File f) {
        if (f == null) {
            return;
        }
        try {
            Files.deleteIfExists(f.toPath());
        } catch (IOException ignored) {
        }
    }

    private static final class BufferedImageTranscoder extends ImageTranscoder {
        private BufferedImage image;

        @Override
        public BufferedImage createImage(int w, int h) {
            return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }

        @Override
        public void writeImage(BufferedImage img, TranscoderOutput out) {
            this.image = img;
        }

        public BufferedImage getBufferedImage() {
            if (image == null) {
                throw new IllegalStateException("No image produced during SVG transcoding");
            }
            return image;
        }
    }
}
