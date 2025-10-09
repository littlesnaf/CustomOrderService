package com.osman.integration.amazon;

import java.util.Objects;

/**
 * Immutable data transfer object representing a single row from the Amazon TXT export.
 */
public final class AmazonOrderRecord {
    private final String orderId;
    private final String orderItemId;
    private final String buyerName;
    private final String rawItemType;
    private final String normalizedItemType;
    private final String downloadUrl;

    public AmazonOrderRecord(String orderId,
                             String orderItemId,
                             String buyerName,
                             String rawItemType,
                             String normalizedItemType,
                             String downloadUrl) {
        this.orderId = requireNonBlank(orderId, "orderId");
        this.orderItemId = requireNonBlank(orderItemId, "orderItemId");
        this.buyerName = requireNonBlank(buyerName, "buyerName");
        this.rawItemType = requireNonBlank(rawItemType, "rawItemType");
        this.normalizedItemType = requireNonBlank(normalizedItemType, "normalizedItemType");
        this.downloadUrl = requireNonBlank(downloadUrl, "downloadUrl");
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " cannot be null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }

    public String orderId() {
        return orderId;
    }

    public String orderItemId() {
        return orderItemId;
    }

    public String buyerName() {
        return buyerName;
    }

    public String rawItemType() {
        return rawItemType;
    }

    public String normalizedItemType() {
        return normalizedItemType;
    }

    public String downloadUrl() {
        return downloadUrl;
    }

    @Override
    public String toString() {
        return "AmazonOrderRecord{" +
            "orderId='" + orderId + '\'' +
            ", orderItemId='" + orderItemId + '\'' +
            ", buyerName='" + buyerName + '\'' +
            ", rawItemType='" + rawItemType + '\'' +
            ", normalizedItemType='" + normalizedItemType + '\'' +
            ", downloadUrl='" + downloadUrl + '\'' +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AmazonOrderRecord that)) return false;
        return orderId.equals(that.orderId)
            && orderItemId.equals(that.orderItemId)
            && buyerName.equals(that.buyerName)
            && rawItemType.equals(that.rawItemType)
            && normalizedItemType.equals(that.normalizedItemType)
            && downloadUrl.equals(that.downloadUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, orderItemId, buyerName, rawItemType, normalizedItemType, downloadUrl);
    }
}
