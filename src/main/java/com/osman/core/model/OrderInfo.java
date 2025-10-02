package com.osman.core.model;

/**
 * Immutable value object representing the details extracted for a single Amazon order.
 */
public class OrderInfo {
    private final String orderId;
    private final String customerName;
    private final String fontName;
    private final int quantity;
    private final String orderItemId;
    private final String label;

    public OrderInfo(String orderId, String customerName, String fontName, int quantity, String orderItemId, String label) {
        this.orderId = orderId;
        this.customerName = customerName;
        this.fontName = fontName;
        this.quantity = quantity;
        this.orderItemId = orderItemId;
        this.label = label;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getFontName() {
        return fontName;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getOrderItemId() {
        return orderItemId;
    }

    public String getLabel() {
        return label;
    }
}
