package com.osman.core.order;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable view over the consolidated order quantity manifest generated during design creation.
 */
public final class OrderQuantitiesManifest {

    public static final String DEFAULT_FILENAME = "order-quantities.json";

    private final Path source;
    private final Map<String, OrderSummary> ordersById;

    private OrderQuantitiesManifest(Path source, Map<String, OrderSummary> ordersById) {
        this.source = source;
        this.ordersById = Collections.unmodifiableMap(new LinkedHashMap<>(ordersById));
    }

    public Path source() {
        return source;
    }

    public Collection<OrderSummary> orders() {
        return ordersById.values();
    }

    public Set<String> orderIds() {
        return ordersById.keySet();
    }

    public OrderSummary find(String orderId) {
        return ordersById.get(orderId);
    }

    public static OrderQuantitiesManifest load(Path path) throws IOException {
        try {
            String content = Files.readString(path);
            JSONObject root = new JSONObject(content);
            JSONArray ordersArray = root.optJSONArray("orders");
            Map<String, OrderSummary> ordersById = new LinkedHashMap<>();
            if (ordersArray != null) {
                for (int i = 0; i < ordersArray.length(); i++) {
                    JSONObject orderObj = ordersArray.optJSONObject(i);
                    if (orderObj == null) {
                        continue;
                    }
                    String orderId = orderObj.optString("orderId", "").trim();
                    if (orderId.isEmpty()) {
                        continue;
                    }
                    int orderQuantity = Math.max(orderObj.optInt("orderQuantity", 0), 0);
                    JSONArray itemsArray = orderObj.optJSONArray("items");
                    List<ItemSummary> items = new ArrayList<>();
                    if (itemsArray != null) {
                        for (int j = 0; j < itemsArray.length(); j++) {
                            JSONObject itemObj = itemsArray.optJSONObject(j);
                            if (itemObj == null) {
                                continue;
                            }
                            String itemId = itemObj.optString("orderItemId", "").trim();
                            if (itemId.isEmpty()) {
                                continue;
                            }
                            int qty = Math.max(itemObj.optInt("itemQuantity", 0), 0);
                            items.add(new ItemSummary(itemId, qty));
                        }
                    }
                    if (orderQuantity <= 0) {
                        orderQuantity = items.stream().mapToInt(ItemSummary::itemQuantity).sum();
                    }
                    ordersById.put(orderId, new OrderSummary(orderId, orderQuantity, List.copyOf(items)));
                }
            }
            return new OrderQuantitiesManifest(path, ordersById);
        } catch (JSONException ex) {
            throw new IOException("Failed to parse manifest " + path + ": " + ex.getMessage(), ex);
        }
    }

    public record OrderSummary(String orderId, int orderQuantity, List<ItemSummary> items) {
    }

    public record ItemSummary(String orderItemId, int itemQuantity) {
    }
}
