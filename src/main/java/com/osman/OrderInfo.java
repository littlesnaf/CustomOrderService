package com.osman;

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

    /**
     * Creates a new order descriptor populated with the parsed Amazon customization data.
     */
    public OrderInfo(String orderId, String customerName, String fontName, int quantity, String orderItemId, String label) {
        this.orderId = orderId;
        this.customerName = customerName;
        this.fontName = fontName;
        this.quantity = quantity;
        this.orderItemId = orderItemId;
        this.label = label;
    }

    // Getters for all fields
    /** @return the Amazon order identifier */
    public String getOrderId() { return orderId; }
    /** @return the customer name inferred from the containing folder */
    public String getCustomerName() { return customerName; }
    /** @return the font family requested for the order */
    public String getFontName() { return fontName; }
    /** @return the ordered quantity for the customization */
    public int getQuantity() { return quantity; }
    /** @return the Amazon order item identifier */
    public String getOrderItemId() { return orderItemId; }
    /** @return the printable label text extracted from JSON */
    public String getLabel() { return label; }
}
