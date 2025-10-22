package com.osman.core.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderDiscoveryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void skipsReadyOutputFoldersWhenDiscoveringOrders() throws IOException {
        Path readyFolder = tempDir.resolve("Ready-11W_P1");
        Files.createDirectories(readyFolder.resolve("order-placeholder"));
        Files.writeString(readyFolder.resolve("order-placeholder/design.json"), "{}");
        Files.writeString(readyFolder.resolve("order-placeholder/design.svg"), "<svg></svg>");

        Path orderDir = tempDir.resolve("Order-001");
        Files.createDirectories(orderDir);
        Files.writeString(orderDir.resolve("design.json"), "{}");
        Files.writeString(orderDir.resolve("design.svg"), "<svg></svg>");

        OrderDiscoveryService service = new OrderDiscoveryService();
        List<File> discovered = service.findOrderLeafFolders(tempDir.toFile(), 2);

        assertEquals(1, discovered.size());
        assertEquals(orderDir.toFile().getCanonicalFile(), discovered.get(0).getCanonicalFile());
    }

    @Test
    void flagsOrderLikeFoldersMissingAssets() throws IOException {
        Path emptyOrder = tempDir.resolve("Joyce_Fake_111-5807308-9522625");
        Files.createDirectories(emptyOrder);

        OrderDiscoveryService service = new OrderDiscoveryService();
        OrderDiscoveryService.OrderSearchResult result = service.discoverOrderFolders(tempDir.toFile(), 2);

        assertEquals(0, result.orderFolders().size());
        assertEquals(1, result.incompleteOrderFolders().size());
        assertEquals(
            emptyOrder.toFile().getCanonicalFile(),
            result.incompleteOrderFolders().get(0).getCanonicalFile()
        );
    }
}
