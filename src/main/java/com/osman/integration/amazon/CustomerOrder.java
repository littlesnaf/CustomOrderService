package com.osman.integration.amazon;

import java.util.Collections;
import java.util.List;

/**
 * Collection of all order items belonging to the same Amazon order id for a single customer.
 */
public final class CustomerOrder {
    private final String orderId;
    private final List<CustomerOrderItem> items;

    CustomerOrder(String orderId, List<CustomerOrderItem> items) {
        this.orderId = orderId;
        this.items = Collections.unmodifiableList(items);
    }

    public String orderId() {
        return orderId;
    }

    public List<CustomerOrderItem> items() {
        return items;
    }
}
