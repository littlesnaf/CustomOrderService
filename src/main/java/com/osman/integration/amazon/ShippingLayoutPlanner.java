package com.osman.integration.amazon;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared helpers for determining shipping layout derived from parsed orders.
 */
public final class ShippingLayoutPlanner {

    private ShippingLayoutPlanner() {
    }

    public enum ShippingSpeed {
        STANDARD("standard", "Standard"),
        EXPEDITED("expedited", "Expedited");

        private final String folderName;
        private final String displayName;

        ShippingSpeed(String folderName, String displayName) {
            this.folderName = folderName;
            this.displayName = displayName;
        }

        public String folderName() {
            return folderName;
        }

        public String displayName() {
            return displayName;
        }

        public static ShippingSpeed from(String level) {
            if (level != null && level.trim().equalsIgnoreCase("Standard")) {
                return STANDARD;
            }
            return EXPEDITED;
        }
    }

    public record MixMetadata(Set<String> mixOrderIds, Map<String, String> mixOrderOunces) {
    }

    public static MixMetadata computeMixMetadata(Map<String, ItemTypeGroup> groups) {
        Map<String, Set<String>> orderItemTypes = new LinkedHashMap<>();
        Map<String, Set<String>> orderOunces = new LinkedHashMap<>();

        groups.values().forEach(group -> {
            String itemType = group.itemType();
            String ounce = extractOunce(itemType);
            group.customers().values().forEach(customer -> customer.orders().values().forEach(order -> {
                orderItemTypes.computeIfAbsent(order.orderId(), id -> new LinkedHashSet<>()).add(itemType);
                if (ounce != null && !ounce.isBlank()) {
                    orderOunces.computeIfAbsent(order.orderId(), id -> new LinkedHashSet<>()).add(ounce);
                }
            }));
        });

        Set<String> mixOrderIds = new LinkedHashSet<>();
        Map<String, String> mixOrderOunces = new LinkedHashMap<>();

        orderItemTypes.forEach((orderId, itemTypes) -> {
            if (itemTypes.size() > 1) {
                mixOrderIds.add(orderId);
                Set<String> ounces = orderOunces.getOrDefault(orderId, Set.of());
                if (ounces.size() == 1) {
                    mixOrderOunces.put(orderId, ounces.iterator().next());
                } else {
                    mixOrderOunces.put(orderId, "mix");
                }
            }
        });

        return new MixMetadata(mixOrderIds, mixOrderOunces);
    }

    public static ShippingSpeed resolveShippingSpeed(CustomerOrder order) {
        if (order == null || order.items().isEmpty()) {
            return ShippingSpeed.STANDARD;
        }
        return order.items().stream()
            .map(CustomerOrderItem::sourceRecord)
            .map(ShippingLayoutPlanner::resolveShippingSpeed)
            .filter(speed -> speed != null)
            .findFirst()
            .orElse(ShippingSpeed.STANDARD);
    }

    public static ShippingSpeed resolveShippingSpeed(AmazonOrderRecord record) {
        if (record == null) {
            return ShippingSpeed.STANDARD;
        }
        return ShippingSpeed.from(record.shipServiceLevel());
    }

    public static String extractOunce(String itemType) {
        if (itemType == null) {
            return null;
        }
        String trimmed = itemType.trim();
        if (trimmed.isEmpty()) {
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
}
