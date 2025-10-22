package com.osman.core.order;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates per-item contributions and produces the consolidated order manifest.
 */
public final class OrderQuantitiesManifestBuilder {

    private static final int DEFAULT_SCAN_DEPTH = 6;

    private final Map<String, OrderAccumulator> orders = new LinkedHashMap<>();

    public void collectFromFolder(File folder) {
        collectFromFolder(folder, DEFAULT_SCAN_DEPTH);
    }

    public void collectFromFolder(File folder, int maxDepth) {
        List<OrderContribution> contributions = OrderContributionReader.readAllFromFolder(folder, maxDepth);
        for (OrderContribution contribution : contributions) {
            addContribution(contribution);
        }
    }

    public void addContribution(OrderContribution contribution) {
        if (contribution == null) {
            return;
        }
        String orderId = normalize(contribution.orderId());
        String itemId = normalize(contribution.orderItemId());
        if (orderId == null || itemId == null) {
            return;
        }
        int quantity = Math.max(contribution.itemQuantity(), 1);
        OrderAccumulator accumulator = orders.computeIfAbsent(orderId, key -> new OrderAccumulator());
        accumulator.orderQuantity += quantity;
        accumulator.itemQuantities.merge(itemId, quantity, Integer::sum);
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    public void writeTo(Path target) throws IOException {
        if (isEmpty()) {
            return;
        }
        writeManifest(target, toOrderSummaries());
    }

    public Collection<OrderQuantitiesManifest.OrderSummary> toOrderSummaries() {
        List<OrderQuantitiesManifest.OrderSummary> summaries = new ArrayList<>();
        orders.forEach((orderId, accumulator) -> summaries.add(toSummary(orderId, accumulator)));
        summaries.sort(Comparator.comparing(OrderQuantitiesManifest.OrderSummary::orderId));
        return summaries;
    }

    public void mergeInto(Path target) throws IOException {
        if (isEmpty()) {
            return;
        }
        Map<String, OrderQuantitiesManifest.OrderSummary> merged = new LinkedHashMap<>();
        if (Files.exists(target)) {
            OrderQuantitiesManifest existing = OrderQuantitiesManifest.load(target);
            for (OrderQuantitiesManifest.OrderSummary summary : existing.orders()) {
                merged.put(summary.orderId(), summary);
            }
        }
        for (OrderQuantitiesManifest.OrderSummary summary : toOrderSummaries()) {
            merged.put(summary.orderId(), summary);
        }
        writeManifest(target, merged.values());
    }

    private OrderQuantitiesManifest.OrderSummary toSummary(String orderId, OrderAccumulator accumulator) {
        List<OrderQuantitiesManifest.ItemSummary> items = new ArrayList<>();
        accumulator.itemQuantities.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> items.add(new OrderQuantitiesManifest.ItemSummary(entry.getKey(), entry.getValue())));
        return new OrderQuantitiesManifest.OrderSummary(orderId, accumulator.orderQuantity, List.copyOf(items));
    }

    private void writeManifest(Path target, Collection<OrderQuantitiesManifest.OrderSummary> summaries) throws IOException {
        JSONObject root = new JSONObject();
        root.put("generatedAt", Instant.now().toString());
        JSONArray ordersArray = new JSONArray();
        summaries.stream()
            .sorted(Comparator.comparing(OrderQuantitiesManifest.OrderSummary::orderId))
            .forEach(summary -> ordersArray.put(toJson(summary)));
        root.put("orders", ordersArray);

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
            target,
            root.toString(2),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
    }

    private JSONObject toJson(OrderQuantitiesManifest.OrderSummary summary) {
        JSONObject orderObj = new JSONObject();
        orderObj.put("orderId", summary.orderId());
        orderObj.put("orderQuantity", summary.orderQuantity());
        JSONArray itemsArray = new JSONArray();
        List<OrderQuantitiesManifest.ItemSummary> items = summary.items();
        if (items != null) {
            items.stream()
                .sorted(Comparator.comparing(OrderQuantitiesManifest.ItemSummary::orderItemId))
                .forEach(item -> {
                    JSONObject itemObj = new JSONObject();
                    itemObj.put("orderItemId", item.orderItemId());
                    itemObj.put("itemQuantity", item.itemQuantity());
                    itemsArray.put(itemObj);
                });
        }
        orderObj.put("items", itemsArray);
        return orderObj;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class OrderAccumulator {
        private int orderQuantity;
        private final Map<String, Integer> itemQuantities = new LinkedHashMap<>();
    }
}
