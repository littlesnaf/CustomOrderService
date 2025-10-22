package com.osman.core.order;

/**
 * Minimal representation of an order contribution parsed from Amazon Custom JSON.
 *
 * @param orderId      Amazon order identifier.
 * @param orderItemId  Amazon order item identifier.
 * @param itemQuantity Quantity for the given order item (at least 1).
 */
public record OrderContribution(String orderId, String orderItemId, int itemQuantity) {
}
