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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmazonOrderDownloadServiceTest {

    private Path tempDir;
    private OrderBatch batch;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amazon-download-test");
        AmazonTxtOrderParser parser = new AmazonTxtOrderParser();
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

        Path mugs = outputRoot.resolve("11W");
        Path vacay = outputRoot.resolve("VACAYBEACH");
        assertTrue(Files.isDirectory(mugs));
        assertTrue(Files.isDirectory(vacay));

        Path johnFolder = mugs.resolve("John_Doe_111-0687106-4490606");
        assertTrue(Files.isDirectory(johnFolder));
        assertTrue(Files.exists(johnFolder.resolve("140273890772121.zip")));
        assertTrue(Files.exists(johnFolder.resolve("order-info.txt")));

        Path alexFolder = mugs.resolve("Alex_Brown_111-4313891-0593053");
        assertTrue(Files.isDirectory(alexFolder));
        try (var paths = Files.list(alexFolder)) {
            assertEquals(3, paths
                .filter(path -> path.getFileName().toString().endsWith(".zip"))
                .count());
        }

        String metadata = Files.readString(alexFolder.resolve("order-info.txt"));
        assertTrue(metadata.contains("Alex Brown"));
        assertTrue(metadata.contains("Item ID: 140287339851241"));
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
