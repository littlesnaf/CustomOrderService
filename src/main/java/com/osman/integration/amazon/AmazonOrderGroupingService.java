package com.osman.integration.amazon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Groups parsed order records by item type, customer, and order id.
 */
public class AmazonOrderGroupingService {

    public OrderBatch group(List<AmazonOrderRecord> records) {
        if (records == null || records.isEmpty()) {
            return OrderBatch.empty();
        }

        Map<String, ItemTypeGroupBuilder> builders = new LinkedHashMap<>();

        for (AmazonOrderRecord record : records) {
            ItemTypeGroupBuilder groupBuilder = builders.computeIfAbsent(
                groupKey(record),
                key -> new ItemTypeGroupBuilder(record.normalizedItemType(), ItemTypeCategorizer.resolveCategory(record))
            );
            groupBuilder.add(record);
        }

        Map<String, ItemTypeGroup> grouped = new LinkedHashMap<>();
        builders.forEach((key, builder) -> grouped.put(key, builder.build()));
        return new OrderBatch(grouped);
    }

    private String groupKey(AmazonOrderRecord record) {
        ItemTypeCategorizer.Category category = ItemTypeCategorizer.resolveCategory(record);
        return category.name() + "|" + record.normalizedItemType();
    }

    private static final class ItemTypeGroupBuilder {
        private final String itemType;
        private final ItemTypeCategorizer.Category category;
        private final Map<String, CustomerGroupBuilder> customers = new LinkedHashMap<>();

        private ItemTypeGroupBuilder(String itemType, ItemTypeCategorizer.Category category) {
            this.itemType = itemType;
            this.category = Objects.requireNonNull(category);
        }

        private void add(AmazonOrderRecord record) {
            String buyerName = record.buyerName();
            String sanitizedName = NameSanitizer.sanitizeForFolder(buyerName);
            CustomerGroupBuilder customerBuilder = customers.computeIfAbsent(
                sanitizedName,
                key -> new CustomerGroupBuilder(buyerName, sanitizedName)
            );
            customerBuilder.add(record);
        }

        private ItemTypeGroup build() {
            Map<String, CustomerGroup> finalized = new LinkedHashMap<>();
            customers.forEach((sanitized, builder) -> finalized.put(sanitized, builder.build()));
            return new ItemTypeGroup(itemType, category, finalized);
        }
    }

    private static final class CustomerGroupBuilder {
        private final String originalName;
        private final String sanitizedName;
        private final Map<String, CustomerOrderBuilder> orders = new LinkedHashMap<>();

        private CustomerGroupBuilder(String originalName, String sanitizedName) {
            this.originalName = originalName;
            this.sanitizedName = sanitizedName;
        }

        private void add(AmazonOrderRecord record) {
            CustomerOrderBuilder orderBuilder = orders.computeIfAbsent(
                record.orderId(),
                CustomerOrderBuilder::new
            );
            orderBuilder.add(record);
        }

        private CustomerGroup build() {
            Map<String, CustomerOrder> finalized = new LinkedHashMap<>();
            orders.forEach((orderId, builder) -> finalized.put(orderId, builder.build()));
            return new CustomerGroup(originalName, sanitizedName, finalized);
        }
    }

    private static final class CustomerOrderBuilder {
        private final String orderId;
        private final List<CustomerOrderItem> items = new ArrayList<>();

        private CustomerOrderBuilder(String orderId) {
            this.orderId = orderId;
        }

        private void add(AmazonOrderRecord record) {
            items.add(new CustomerOrderItem(
                record.orderItemId(),
                record.downloadUrl(),
                record
            ));
        }

        private CustomerOrder build() {
            return new CustomerOrder(orderId, List.copyOf(items));
        }
    }
}
