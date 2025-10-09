package com.osman.integration.amazon;

import com.osman.logging.AppLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Handles the filesystem layout and downloading of customer assets.
 */
public class AmazonOrderDownloadService {
    private static final Logger LOGGER = AppLogger.get();
    private static final DateTimeFormatter FOLDER_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient httpClient;
    private final Path baseDirectory;

    public AmazonOrderDownloadService() {
        this(HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build(),
            defaultBaseDirectory());
    }

    public AmazonOrderDownloadService(HttpClient httpClient) {
        this(httpClient, defaultBaseDirectory());
    }

    public AmazonOrderDownloadService(HttpClient httpClient, Path baseDirectory) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.baseDirectory = baseDirectory == null ? defaultBaseDirectory() : baseDirectory;
    }

    /**
     * Downloads every item in the batch. Returns the root directory that was created for the run.
     */
    public Path downloadBatch(OrderBatch batch, DownloadProgressListener listener) throws IOException {
        validateBatch(batch);
        Objects.requireNonNull(listener, "listener");
        return downloadInternal(batch, batch.groups(), listener);
    }

    /**
     * Downloads only the specified item type within the provided batch.
     */
    public Path downloadItemType(OrderBatch batch, String itemType, DownloadProgressListener listener) throws IOException {
        validateBatch(batch);
        Objects.requireNonNull(itemType, "itemType");
        Objects.requireNonNull(listener, "listener");

        ItemTypeGroup group = batch.groups().get(itemType);
        if (group == null || group.isEmpty()) {
            throw new IllegalArgumentException("Item type '%s' not found in batch.".formatted(itemType));
        }

        Map<String, ItemTypeGroup> subset = Map.of(itemType, group);
        return downloadInternal(batch, subset, listener);
    }

    private Path downloadInternal(OrderBatch batch,
                                  Map<String, ItemTypeGroup> groups,
                                  DownloadProgressListener listener) throws IOException {
        Files.createDirectories(baseDirectory);
        Path batchDirectory = baseDirectory.resolve("AmazonOrders-" + FOLDER_FORMATTER.format(LocalDateTime.now()));
        Files.createDirectories(batchDirectory);

        int totalItems = countItems(groups);
        listener.onStart(totalItems, batchDirectory);

        int processed = 0;
        for (Map.Entry<String, ItemTypeGroup> entry : groups.entrySet()) {
            String itemType = entry.getKey();
            ItemTypeGroup itemTypeGroup = entry.getValue();

            Path itemTypeFolder = batchDirectory.resolve(itemType);
            Files.createDirectories(itemTypeFolder);

            for (CustomerGroup customer : itemTypeGroup.customers().values()) {
                for (CustomerOrder order : customer.orders().values()) {
                    Path orderFolder = createOrderFolder(itemTypeFolder, customer, order);
                    writeMetadata(orderFolder, customer, order, itemType);

                    for (CustomerOrderItem item : order.items()) {
                        processed++;
                        listener.onItemStarted(item, processed, totalItems);
                        try {
                            Path downloaded = downloadSingleItem(orderFolder, item);
                            listener.onItemCompleted(item, downloaded, processed, totalItems);
                        } catch (Exception ex) {
                            LOGGER.warning("Failed to download " + item.orderItemId() + ": " + ex.getMessage());
                            listener.onItemFailed(item, ex, processed, totalItems);
                        }
                    }
                }
            }
        }

        listener.onComplete(processed, totalItems, batchDirectory);
        return batchDirectory;
    }

    private static void validateBatch(OrderBatch batch) {
        if (batch == null || batch.isEmpty()) {
            throw new IllegalArgumentException("Batch is empty; nothing to download.");
        }
    }

    private static Path defaultBaseDirectory() {
        String home = System.getProperty("user.home");
        return Paths.get(home, "Downloads", "AmazonOrders");
    }

    private static int countItems(OrderBatch batch) {
        return countItems(batch.groups());
    }

    private static int countItems(Map<String, ItemTypeGroup> groups) {
        return groups.values().stream()
            .mapToInt(group -> group.customers().values().stream()
                .mapToInt(customer -> customer.orders().values().stream()
                    .mapToInt(order -> order.items().size())
                    .sum())
                .sum())
            .sum();
    }

    protected Path downloadSingleItem(Path orderFolder, CustomerOrderItem item) throws IOException, InterruptedException {
        Path targetFile = resolveTargetFile(orderFolder, item.orderItemId());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(item.downloadUrl()))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            Files.write(targetFile, response.body());
            return targetFile;
        }

        throw new IOException("Unexpected status " + response.statusCode() + " for " + item.downloadUrl());
    }

    private static Path resolveTargetFile(Path orderFolder, String orderItemId) throws IOException {
        String baseName = orderItemId + ".zip";
        Path target = orderFolder.resolve(baseName);
        int duplicateIndex = 1;
        while (Files.exists(target)) {
            target = orderFolder.resolve(orderItemId + "_" + duplicateIndex + ".zip");
            duplicateIndex++;
        }
        return target;
    }

    private static Path createOrderFolder(Path itemTypeFolder, CustomerGroup customer, CustomerOrder order) throws IOException {
        String orderIdSegment = order.orderId().replaceAll("[^A-Za-z0-9-]", "_");
        String folderName = "%s_%s".formatted(customer.sanitizedBuyerName(), orderIdSegment);
        Path folder = itemTypeFolder.resolve(folderName);
        Files.createDirectories(folder);
        return folder;
    }

    private static void writeMetadata(Path orderFolder,
                                      CustomerGroup customer,
                                      CustomerOrder order,
                                      String itemType) throws IOException {
        Path metadata = orderFolder.resolve("order-info.txt");
        if (Files.exists(metadata)) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Buyer: ").append(customer.originalBuyerName()).append(System.lineSeparator());
        builder.append("Order ID: ").append(order.orderId()).append(System.lineSeparator());
        builder.append("Item Type: ").append(itemType).append(System.lineSeparator());
        builder.append("Items:").append(System.lineSeparator());
        for (CustomerOrderItem item : order.items()) {
            builder.append("  - Item ID: ").append(item.orderItemId()).append(System.lineSeparator());
            builder.append("    Download: ").append(item.downloadUrl()).append(System.lineSeparator());
        }

        Files.writeString(metadata, builder.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Listener for download progress. Methods are no-ops by default so callers can override selectively.
     */
    public interface DownloadProgressListener {
        default void onStart(int totalItems, Path targetRoot) {
        }

        default void onItemStarted(CustomerOrderItem item, int processedCount, int totalCount) {
        }

        default void onItemCompleted(CustomerOrderItem item, Path downloadedFile, int processedCount, int totalCount) {
        }

        default void onItemFailed(CustomerOrderItem item, Exception error, int processedCount, int totalCount) {
        }

        default void onComplete(int processedCount, int totalCount, Path targetRoot) {
        }
    }
}
