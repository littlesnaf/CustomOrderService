package com.osman.integration.amazon;

import com.osman.logging.AppLogger;

import com.osman.integration.amazon.AmazonOrderRecord;

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
import java.util.AbstractMap;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Handles the filesystem layout and downloading of customer assets.
 */
public class  AmazonOrderDownloadService {
    private static final Logger LOGGER = AppLogger.get();
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

        Map<String, ItemTypeGroup> personalizedGroups = filterPersonalized(batch.groups());
        if (personalizedGroups.isEmpty()) {
            throw new IllegalArgumentException("Batch does not contain personalized mug item types.");
        }

        return downloadInternal(batch, personalizedGroups, listener);
    }

    /**
     * Downloads only the specified item type within the provided batch.
     */
    public Path downloadItemType(OrderBatch batch, String itemType, DownloadProgressListener listener) throws IOException {
        validateBatch(batch);
        Objects.requireNonNull(itemType, "itemType");
        Objects.requireNonNull(listener, "listener");

        if (batch.groups().containsKey(itemType)) {
            return downloadItemTypes(batch, java.util.List.of(itemType), listener);
        }

        String matchingKey = batch.groups().entrySet().stream()
            .filter(entry -> itemType.equals(entry.getValue().itemType()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Item type '%s' not found in batch.".formatted(itemType)));

        return downloadItemTypes(batch, java.util.List.of(matchingKey), listener);
    }

    /**
     * Downloads only the specified item types within the provided batch.
     */
    public Path downloadItemTypes(OrderBatch batch,
                                  Collection<String> itemTypes,
                                  DownloadProgressListener listener) throws IOException {
        validateBatch(batch);
        Objects.requireNonNull(itemTypes, "itemTypes");
        Objects.requireNonNull(listener, "listener");

        if (itemTypes.isEmpty()) {
            throw new IllegalArgumentException("No item types specified for download.");
        }

        Map<String, ItemTypeGroup> subset = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        Set<String> nonPersonalized = new LinkedHashSet<>();

        for (String requested : itemTypes) {
            if (requested == null || requested.isBlank()) {
                continue;
            }

            Map.Entry<String, ItemTypeGroup> resolved = resolveGroup(batch, requested);
            if (resolved == null || resolved.getValue() == null || resolved.getValue().isEmpty()) {
                missing.add(requested);
                continue;
            }

            ItemTypeGroup group = resolved.getValue();
            if (group.category() != ItemTypeCategorizer.Category.MUG_CUSTOM) {
                nonPersonalized.add(group.itemType());
                continue;
            }

            subset.putIfAbsent(resolved.getKey(), group);
        }

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Item type(s) not found in batch: " + missing);
        }

        if (!nonPersonalized.isEmpty()) {
            throw new IllegalArgumentException(
                "Item type(s) not customizable mugs: " + nonPersonalized
            );
        }

        if (subset.isEmpty()) {
            throw new IllegalArgumentException("No personalized mug item types selected for download.");
        }

        return downloadInternal(batch, subset, listener);
    }

    private Map.Entry<String, ItemTypeGroup> resolveGroup(OrderBatch batch, String requested) {
        Map<String, ItemTypeGroup> groups = batch.groups();
        ItemTypeGroup direct = groups.get(requested);
        if (direct != null) {
            return new AbstractMap.SimpleImmutableEntry<>(requested, direct);
        }

        for (Map.Entry<String, ItemTypeGroup> entry : groups.entrySet()) {
            if (requested.equals(entry.getValue().itemType())) {
                return entry;
            }
        }
        return null;
    }

    private Map<String, ItemTypeGroup> filterPersonalized(Map<String, ItemTypeGroup> groups) {
        Map<String, ItemTypeGroup> filtered = new LinkedHashMap<>();
        groups.forEach((key, value) -> {
            if (value.category() == ItemTypeCategorizer.Category.MUG_CUSTOM && !value.isEmpty()) {
                filtered.put(key, value);
            }
        });
        return filtered;
    }

    private Path downloadInternal(OrderBatch batch,
                                  Map<String, ItemTypeGroup> groups,
                                  DownloadProgressListener listener) throws IOException {
        Files.createDirectories(baseDirectory);
        Path batchDirectory = baseDirectory;

        int totalItems = countItems(groups);
        listener.onStart(totalItems, batchDirectory);

        MixMetadata mixMetadata = computeMixMetadata(groups);
        Set<String> mixOrderIds = mixMetadata.mixOrderIds();
        Map<String, String> mixOrderOunces = mixMetadata.mixOrderOunces();
        Map<ShippingSpeed, Map<String, Path>> rootsBySpeed = new EnumMap<>(ShippingSpeed.class);

        int processed = 0;
        for (Map.Entry<String, ItemTypeGroup> entry : groups.entrySet()) {
            ItemTypeGroup itemTypeGroup = entry.getValue();

            for (CustomerGroup customer : itemTypeGroup.customers().values()) {
                for (CustomerOrder order : customer.orders().values()) {
                    ShippingSpeed speed = resolveShippingSpeed(order);
                    boolean isMixOrder = mixOrderIds.contains(order.orderId());
                    String mixOunce = mixOrderOunces.get(order.orderId());

                    Path itemTypeRoot = resolveItemTypeRoot(
                        batchDirectory,
                        speed,
                        itemTypeGroup,
                        isMixOrder,
                        mixOunce,
                        rootsBySpeed
                    );

                    Path imagesFolder = ItemTypeCategorizer.resolveImagesFolder(itemTypeRoot);
                    Files.createDirectories(imagesFolder);

                    Path orderFolder = createOrderFolder(imagesFolder, customer, order);
                    writeMetadata(orderFolder, customer, order, itemTypeGroup.itemType());

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

    private static ShippingSpeed resolveShippingSpeed(CustomerOrder order) {
        if (order == null || order.items().isEmpty()) {
            return ShippingSpeed.STANDARD;
        }
        return order.items().stream()
            .map(CustomerOrderItem::sourceRecord)
            .map(AmazonOrderDownloadService::resolveShippingSpeed)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(ShippingSpeed.STANDARD);
    }

    private static ShippingSpeed resolveShippingSpeed(AmazonOrderRecord record) {
        if (record == null) {
            return ShippingSpeed.STANDARD;
        }
        return ShippingSpeed.from(record.shipServiceLevel());
    }

    private static Path createOrderFolder(Path imagesFolder, CustomerGroup customer, CustomerOrder order) throws IOException {
        String orderIdSegment = order.orderId().replaceAll("[^A-Za-z0-9-]", "_");
        String recipientName = order.items().stream()
                .map(CustomerOrderItem::sourceRecord)
                .filter(Objects::nonNull)
                .map(AmazonOrderRecord::recipientName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(customer.originalBuyerName());
        String sanitizedRecipient = NameSanitizer.sanitizeForFolder(recipientName);
        if (sanitizedRecipient == null || sanitizedRecipient.isBlank()) {
            sanitizedRecipient = customer.sanitizedBuyerName();
        }
        String folderName = "%s_%s".formatted(sanitizedRecipient, orderIdSegment);
        Path folder = imagesFolder.resolve(folderName);
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

    private Path resolveItemTypeRoot(Path batchDirectory,
                                     ShippingSpeed speed,
                                     ItemTypeGroup itemTypeGroup,
                                     boolean isMixOrder,
                                     String mixOunce,
                                     Map<ShippingSpeed, Map<String, Path>> rootsBySpeed) {
        Map<String, Path> cache = rootsBySpeed.computeIfAbsent(speed, s -> new LinkedHashMap<>());
        if (isMixOrder) {
            String ounceKey = (mixOunce == null || mixOunce.isBlank()) ? "mix" : mixOunce;
            String cacheKey = "mix|" + ounceKey;
            return cache.computeIfAbsent(cacheKey, key -> createMixRoot(batchDirectory, speed, ounceKey));
        }

        String cacheKey = "type|" + itemTypeGroup.itemType();
        return cache.computeIfAbsent(cacheKey, key -> createTypeRoot(batchDirectory, speed, itemTypeGroup));
    }

    private static MixMetadata computeMixMetadata(Map<String, ItemTypeGroup> groups) {
        Map<String, Set<String>> orderItemTypes = new LinkedHashMap<>();
        Map<String, Set<String>> orderOunces = new LinkedHashMap<>();

        for (ItemTypeGroup group : groups.values()) {
            String itemType = group.itemType();
            String ounce = extractOunce(itemType);
            for (CustomerGroup customer : group.customers().values()) {
                for (CustomerOrder order : customer.orders().values()) {
                    orderItemTypes.computeIfAbsent(order.orderId(), id -> new LinkedHashSet<>()).add(itemType);
                    if (ounce != null && !ounce.isBlank()) {
                        orderOunces.computeIfAbsent(order.orderId(), id -> new LinkedHashSet<>()).add(ounce);
                    }
                }
            }
        }

        Set<String> mixOrderIds = new LinkedHashSet<>();
        Map<String, String> mixOrderOunces = new LinkedHashMap<>();

        for (Map.Entry<String, Set<String>> entry : orderItemTypes.entrySet()) {
            String orderId = entry.getKey();
            Set<String> itemTypes = entry.getValue();
            if (itemTypes.size() > 1) {
                mixOrderIds.add(orderId);
                Set<String> ounces = orderOunces.getOrDefault(orderId, Set.of());
                if (ounces.size() == 1) {
                    mixOrderOunces.put(orderId, ounces.iterator().next());
                } else {
                    mixOrderOunces.put(orderId, "mix");
                }
            }
        }

        return new MixMetadata(mixOrderIds, mixOrderOunces);
    }

    private static Path createTypeRoot(Path batchDirectory,
                                       ShippingSpeed speed,
                                       ItemTypeGroup itemTypeGroup) {
        Path speedRoot = ensureDirectory(batchDirectory.resolve(speed.folderName()));
        Path typeRoot = ItemTypeCategorizer.resolveItemTypeFolder(speedRoot, itemTypeGroup);
        return ensureDirectory(typeRoot);
    }

    private static Path createMixRoot(Path batchDirectory,
                                      ShippingSpeed speed,
                                      String ounceKey) {
        Path speedRoot = ensureDirectory(batchDirectory.resolve(speed.folderName()));
        Path target = ("mix".equalsIgnoreCase(ounceKey) || ounceKey == null || ounceKey.isBlank())
            ? speedRoot.resolve("mix")
            : speedRoot.resolve(ounceKey).resolve("mix");
        return ensureDirectory(target);
    }

    private static Path ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException("Unable to create directory " + path, e);
        }
        return path;
    }

    private static String extractOunce(String itemType) {
        if (itemType == null) {
            return null;
        }
        String trimmed = itemType.trim();
        if (trimmed.length() < 2) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isDigit(ch)) {
                digits.append(ch);
                if (digits.length() == 2) {
                    break;
                }
            } else if (digits.length() > 0) {
                break;
            }
        }
        return digits.length() == 2 ? digits.toString() : null;
    }

    private record MixMetadata(Set<String> mixOrderIds, Map<String, String> mixOrderOunces) {
    }

    private enum ShippingSpeed {
        STANDARD("standard"),
        EXPEDITED("expedited");

        private final String folderName;

        ShippingSpeed(String folderName) {
            this.folderName = folderName;
        }

        String folderName() {
            return folderName;
        }

        static ShippingSpeed from(String level) {
            if (level != null && level.trim().equalsIgnoreCase("Standard")) {
                return STANDARD;
            }
            return EXPEDITED;
        }
    }
}
