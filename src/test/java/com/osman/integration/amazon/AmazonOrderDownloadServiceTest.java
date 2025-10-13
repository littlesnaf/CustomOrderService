package com.osman.integration.amazon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class
AmazonOrderDownloadServiceTest {

    private Path tempDir;
    private OrderBatch batch;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amazon-download-test");
        AmazonTxtOrderParser parser = new AmazonTxtOrderParser();
        parser.setIncludeLateShipmentRows(true);
        List<AmazonOrderRecord> records;
        try (InputStream stream = getResource("ordertestfiles/sample-amazon-orders.txt")) {
            assertNotNull(stream, "Sample resource missing");
            records = parser.parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        batch = new AmazonOrderGroupingService().group(records);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
            }
        }
    }

    @Test
    void downloadsBatchIntoStructuredFolders() throws IOException {
        TestableDownloadService service = new TestableDownloadService(tempDir);
        Path outputRoot = service.downloadBatch(batch, new AmazonOrderDownloadService.DownloadProgressListener() {
        });

        assertTrue(outputRoot.startsWith(tempDir));
        assertTrue(Files.isDirectory(outputRoot));

        Path itemTypeRoot11 = outputRoot.resolve("expedited").resolve("11").resolve("11W");
        Path imagesRoot11 = itemTypeRoot11.resolve(ItemTypeCategorizer.IMAGES_FOLDER_NAME);
        Path itemTypeRoot15 = outputRoot.resolve("standard").resolve("15").resolve("15R");
        Path imagesRoot15 = itemTypeRoot15.resolve(ItemTypeCategorizer.IMAGES_FOLDER_NAME);
        assertTrue(Files.isDirectory(itemTypeRoot11));
        assertTrue(Files.isDirectory(imagesRoot11));
        assertTrue(Files.isDirectory(itemTypeRoot15));
        assertTrue(Files.isDirectory(imagesRoot15));

        assertFalse(Files.exists(outputRoot.resolve(ItemTypeCategorizer.MUGS_FOLDER_NAME)),
            "Legacy Mugs folder should not be created");

        Path johnFolder = imagesRoot11.resolve("John_Doe_111-0687106-4490606");
        assertTrue(Files.isDirectory(johnFolder));
        assertTrue(Files.exists(johnFolder.resolve("140273890772121.zip")));
        assertTrue(Files.exists(johnFolder.resolve("order-info.txt")));

        Path janeFolder = imagesRoot15.resolve("Jane_Smith_222-2222222-2222222");
        assertTrue(Files.isDirectory(janeFolder));
        assertTrue(Files.exists(janeFolder.resolve("240278395364561.zip")));
        assertTrue(Files.exists(janeFolder.resolve("order-info.txt")));

        String metadata = Files.readString(janeFolder.resolve("order-info.txt"));
        assertTrue(metadata.contains("Jane Smith"));
        assertTrue(metadata.contains("Item ID: 240278395364561"));
    }

    @Test
    void downloadsOnlySelectedItemTypes() throws IOException {
        TestableDownloadService service = new TestableDownloadService(tempDir);
        Path outputRoot = service.downloadItemTypes(batch, List.of("11W"), new AmazonOrderDownloadService.DownloadProgressListener() {
        });

        Path itemTypeRoot = outputRoot.resolve("expedited").resolve("11").resolve("11W");
        Path imagesRoot = itemTypeRoot.resolve(ItemTypeCategorizer.IMAGES_FOLDER_NAME);

        assertTrue(Files.isDirectory(itemTypeRoot));
        assertTrue(Files.isDirectory(imagesRoot));
        assertFalse(Files.exists(outputRoot.resolve(ItemTypeCategorizer.MUGS_FOLDER_NAME)),
            "Legacy Mugs folder should not be created");
    }

    private InputStream getResource(String path) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
    }

    private static final class TestableDownloadService extends AmazonOrderDownloadService {
        private TestableDownloadService(Path baseDirectory) {
            super(java.net.http.HttpClient.newBuilder().build(), baseDirectory);
        }

        @Override
        protected Path downloadSingleItem(Path orderFolder, CustomerOrderItem item) throws IOException {
            Path target = orderFolder.resolve(item.orderItemId() + ".zip");
            Files.writeString(target, "fake data for " + item.orderItemId(), StandardCharsets.UTF_8);
            return target;
        }
    }
}
