package com.osman.core.fs;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderDiscoveryServiceTest {

    @Test
    void skipsReadyOutputFoldersWhenDiscoveringOrders() throws IOException {
        Path root = Files.createTempDirectory("order-discovery-test");
        try {
            Path readyFolder = root.resolve("Ready-11W_P1");
            Files.createDirectories(readyFolder.resolve("order-placeholder"));
            Files.writeString(readyFolder.resolve("order-placeholder/design.json"), "{}");
            Files.writeString(readyFolder.resolve("order-placeholder/design.svg"), "<svg></svg>");

            Path orderDir = root.resolve("Order-001");
            Files.createDirectories(orderDir);
            Files.writeString(orderDir.resolve("design.json"), "{}");
            Files.writeString(orderDir.resolve("design.svg"), "<svg></svg>");

            OrderDiscoveryService service = new OrderDiscoveryService();
            List<File> discovered = service.findOrderLeafFolders(root.toFile(), 2);

            assertEquals(1, discovered.size());
            assertEquals(orderDir.toFile().getCanonicalFile(), discovered.get(0).getCanonicalFile());
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }
}
