package com.osman.core.render;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MugRendererTest {

    @Test
    void rendersSimpleOrderIntoPng() throws Exception {
        Path orderDir = Files.createTempDirectory("mug-order");
        Path outputDir = Files.createTempDirectory("mug-output");

        try {
            Path svgFile = orderDir.resolve("design.svg");
            Files.writeString(svgFile, simpleSvg());

            Path jsonFile = orderDir.resolve("order.json");
            Files.writeString(jsonFile, simpleOrderJson());

            List<String> outputs = MugRenderer.processOrderFolder(
                orderDir.toFile(),
                outputDir.toFile(),
                "John Doe",
                "test"
            );

            assertEquals(1, outputs.size(), "Expected a single rendered PNG");
            Path rendered = Path.of(outputs.get(0));
            assertNotNull(rendered);
            assertFalse(Files.size(rendered) == 0, "Rendered PNG should not be empty");

            BufferedImage image = ImageIO.read(rendered.toFile());
            TemplateRegistry.MugTemplate template = TemplateRegistry.forOunces(11);
            assertEquals(template.finalWidth, image.getWidth(), "Unexpected output width");
            assertEquals(template.finalHeight, image.getHeight(), "Unexpected output height");
        } finally {
            deleteQuietly(orderDir);
            deleteQuietly(outputDir);
        }
    }

    private static String simpleOrderJson() {
        return "{\n" +
            "  \"orderId\": \"111-0000000-0000000\",\n" +
            "  \"orderItemId\": \"ITEM-123\",\n" +
            "  \"quantity\": 2,\n" +
            "  \"title\": \"Custom Mug 11oz\",\n" +
            "  \"customizationData\": {\n" +
            "    \"children\": [\n" +
            "      {\n" +
            "        \"label\": \"Preview\",\n" +
            "        \"children\": []\n" +
            "      },\n" +
            "      {\n" +
            "        \"type\": \"FontCustomization\",\n" +
            "        \"fontSelection\": { \"family\": \"Arial\" }\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}\n";
    }

    private static String simpleSvg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"3200\" height=\"1440\">" +
            "<rect x=\"0\" y=\"0\" width=\"3200\" height=\"1440\" fill=\"#ffffff\"/>" +
            "<text x=\"1600\" y=\"720\" font-family=\"FONT_PLACEHOLDER\" font-size=\"120\" text-anchor=\"middle\" fill=\"#000000\">Test Render</text>" +
            "</svg>";
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
        } catch (IOException ignored) {
        }
    }
}
