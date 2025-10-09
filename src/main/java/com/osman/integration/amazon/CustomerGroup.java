package com.osman.integration.amazon;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a customer's grouped orders (keyed by Amazon orderId).
 */
public final class CustomerGroup {
    private final String originalBuyerName;
    private final String sanitizedBuyerName;
    private final Map<String, CustomerOrder> orders;

    CustomerGroup(String originalBuyerName,
                  String sanitizedBuyerName,
                  Map<String, CustomerOrder> orders) {
        this.originalBuyerName = originalBuyerName;
        this.sanitizedBuyerName = sanitizedBuyerName;
        this.orders = Collections.unmodifiableMap(orders);
    }

    public String originalBuyerName() {
        return originalBuyerName;
    }

    public String sanitizedBuyerName() {
        return sanitizedBuyerName;
    }

    public Map<String, CustomerOrder> orders() {
        return orders;
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }
}
