package com.osman.integration.amazon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmazonOrderDownloadServiceTest {

    private Path tempDir;
    private OrderBatch batch;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amazon-download-test");
        AmazonTxtOrderParser parser = new AmazonTxtOrderParser();
        parser.setIncludeLateShipmentRows(true);

        String header = String.join("\t",
            "order-id", "order-item-id", "purchase-date", "payments-date", "reporting-date", "promise-date",
            "days-past-promise", "buyer-email", "buyer-name", "cpf", "buyer-phone-number", "sku", "number-of-items",
            "product-name", "quantity-purchased", "quantity-shipped", "quantity-to-ship", "ship-service-level",
            "ship-service-name", "recipient-name", "ship-address-1", "ship-address-2", "ship-address-3", "ship-city",
            "ship-state", "ship-postal-code", "ship-country", "sales-channel", "customized-url", "customized-page",
            "is-business-order", "purchase-order-number", "price-designation", "is-prime",
            "is-buyer-requested-cancellation", "buyer-requested-cancel-reason", "verge-of-cancellation",
            "verge-of-lateShipment", "signature-confirmation-recommended", "buyer-identification-number",
            "buyer-identification-type"
        );

        String expeditedWhite = String.join("\t",
            "111-0687106-4490606", "140273890772121", "2025-09-24T12:02:50+00:00", "2025-09-24T05:02:50-07:00",
            "2025-09-24T12:48:11+00:00", "2025-09-26T06:59:59+00:00", "-1", "customer1@marketplace.amazon.com",
            "John Doe", "", "+1 111-111-1111", "SKU004-Photo-11W", "1",
            "HomeBee Personalized Coffee Mug | Custom Photo Text and Logo Ceramic Mug | Customized 11 Oz Tea Cup",
            "1", "0", "1", "NextDay", "Std US D2D Dom", "John Doe", "123 Any St", "", "",
            "DAWSONVILLE", "GA", "30534-4732", "US", "Amazon.com",
            "https://zme-caps.amazon.com/t/abcd1234/EFGH5678/23", "https://a.co/example",
            "false", "", "", "false", "false", "false", "false", "false", "", ""
        );

        String expeditedRed = String.join("\t",
            "111-0687106-4490606", "140273890772122", "2025-09-24T12:02:50+00:00", "2025-09-24T05:02:50-07:00",
            "2025-09-24T12:48:11+00:00", "2025-09-26T06:59:59+00:00", "-1", "customer1@marketplace.amazon.com",
            "John Doe", "", "+1 111-111-1111", "SKU004-Photo-11R", "1",
            "HomeBee Personalized Coffee Mug | Custom Photo Text and Logo Ceramic Mug | Customized 11 Oz Tea Cup",
            "1", "0", "1", "NextDay", "Std US D2D Dom", "John Doe", "123 Any St", "", "",
            "DAWSONVILLE", "GA", "30534-4732", "US", "Amazon.com",
            "https://zme-caps.amazon.com/t/abcd1234/EFGH5678/23", "https://a.co/example",
            "false", "", "", "false", "false", "false", "false", "false", "", ""
        );

        String standard15 = String.join("\t",
            "222-2222222-2222222", "240278395364561", "2025-09-25T10:15:07+00:00", "2025-09-25T03:15:07-07:00",
            "2025-09-25T10:45:32+00:00", "2025-09-27T06:59:59+00:00", "-2", "customer2@marketplace.amazon.com",
            "Jane Smith", "", "+1 222-222-2222", "15-SKU.PhotoMug.15R", "1",
            "Custom Personalized Coffee Mug", "1", "0", "1", "Standard", "Std US D2D Dom",
            "Jane Smith", "456 Example Ave", "Apt 2B", "", "ATLANTA", "GA", "30301-1234", "US", "Amazon.com",
            "https://zme-caps.amazon.com/t/ijkl5678/MNOP9012/23", "https://a.co/example2",
            "false", "", "", "false", "false", "false", "false", "false", "", ""
        );

        String data = String.join("\n", header, expeditedWhite, expeditedRed, standard15);

        List<AmazonOrderRecord> records = parser.parse(new StringReader(data));
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

        Path itemTypeRoot11 = outputRoot.resolve("manual").resolve("11").resolve("mix");
        Path imagesRoot11 = itemTypeRoot11.resolve(ItemTypeCategorizer.IMAGES_FOLDER_NAME);
        Path itemTypeRoot15 = outputRoot.resolve("automated").resolve("15").resolve("15R");
        Path imagesRoot15 = itemTypeRoot15.resolve(ItemTypeCategorizer.IMAGES_FOLDER_NAME);
        assertTrue(Files.isDirectory(itemTypeRoot11));
        assertTrue(Files.isDirectory(imagesRoot11));
        assertTrue(Files.isDirectory(itemTypeRoot15));
        assertTrue(Files.isDirectory(imagesRoot15));

        assertFalse(Files.exists(outputRoot.resolve("manual").resolve("11").resolve("11W")),
            "Mix orders should not create type-specific folders");

        assertFalse(Files.exists(outputRoot.resolve(ItemTypeCategorizer.MUGS_FOLDER_NAME)),
            "Legacy Mugs folder should not be created");

        Path johnFolder = imagesRoot11.resolve("John_Doe_111-0687106-4490606");
        assertTrue(Files.isDirectory(johnFolder));
        assertTrue(Files.exists(johnFolder.resolve("140273890772121.zip")));
        assertTrue(Files.exists(johnFolder.resolve("140273890772122.zip")));
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

        Path itemTypeRoot = outputRoot.resolve("manual").resolve("11").resolve("11W");
        Path imagesRoot = itemTypeRoot.resolve(ItemTypeCategorizer.IMAGES_FOLDER_NAME);

        assertTrue(Files.isDirectory(itemTypeRoot));
        assertTrue(Files.isDirectory(imagesRoot));
        assertFalse(Files.exists(outputRoot.resolve("manual").resolve("11").resolve("mix")),
            "Mix folder should not appear when only one item type is selected");
        assertFalse(Files.exists(outputRoot.resolve(ItemTypeCategorizer.MUGS_FOLDER_NAME)),
            "Legacy Mugs folder should not be created");
    }

    private static final class TestableDownloadService extends AmazonOrderDownloadService {
        private TestableDownloadService(Path baseDirectory) {
            super(java.net.http.HttpClient.newBuilder().build(), baseDirectory);
        }

        @Override
        protected Path downloadSingleItem(Path orderFolder, CustomerOrderItem item) throws IOException {
            Path target = orderFolder.resolve(item.orderItemId() + ".zip");
            Files.writeString(target, "fake data forr " + item.orderItemId(), StandardCharsets.UTF_8);
            return target;
        }
    }
}
