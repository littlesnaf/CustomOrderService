package com.osman.integration.amazon;

/**
 * Represents a single downloadable item within an order.
 */
public final class CustomerOrderItem {
    private final String orderItemId;
    private final String downloadUrl;
    private final AmazonOrderRecord sourceRecord;

    CustomerOrderItem(String orderItemId, String downloadUrl, AmazonOrderRecord sourceRecord) {
        this.orderItemId = orderItemId;
        this.downloadUrl = downloadUrl;
        this.sourceRecord = sourceRecord;
    }

    public String orderItemId() {
        return orderItemId;
    }

    public String downloadUrl() {
        return downloadUrl;
    }

    public AmazonOrderRecord sourceRecord() {
        return sourceRecord;
    }
}
