package com.osman.integration.amazon;

import java.util.Objects;

/**
 * Immutable data transfer object representing a single row from the Amazon TXT export.
 */
public final class AmazonOrderRecord {
    private final String orderId;
    private final String orderItemId;
    private final String purchaseDate;
    private final String buyerName;
    private final String buyerEmail;
    private final String buyerPhoneNumber;
    private final String recipientName;
    private final String shipAddress1;
    private final String shipAddress2;
    private final String shipAddress3;
    private final String shipCity;
    private final String shipState;
    private final String shipPostalCode;
    private final String shipCountry;
    private final String shipServiceLevel;
    private final String shipServiceName;
    private final String productName;
    private final int quantityPurchased;
    private final int quantityShipped;
    private final String rawItemType;
    private final String normalizedItemType;
    private final String downloadUrl;
    private final boolean customizable;

    public AmazonOrderRecord(String orderId,
                             String orderItemId,
                             String purchaseDate,
                             String buyerName,
                             String buyerEmail,
                             String buyerPhoneNumber,
                             String recipientName,
                             String shipAddress1,
                             String shipAddress2,
                             String shipAddress3,
                             String shipCity,
                             String shipState,
                             String shipPostalCode,
                             String shipCountry,
                             String shipServiceLevel,
                             String shipServiceName,
                             String productName,
                             int quantityPurchased,
                             int quantityShipped,
                             String rawItemType,
                             String normalizedItemType,
                             String downloadUrl,
                             boolean customizable) {
        this.orderId = requireNonBlank(orderId, "orderId");
        this.orderItemId = requireNonBlank(orderItemId, "orderItemId");
        this.purchaseDate = normalizeNullable(purchaseDate);
        this.buyerName = requireNonBlank(buyerName, "buyerName");
        this.buyerEmail = normalizeNullable(buyerEmail);
        this.buyerPhoneNumber = normalizeNullable(buyerPhoneNumber);
        this.recipientName = normalizeNullable(recipientName);
        this.shipAddress1 = normalizeNullable(shipAddress1);
        this.shipAddress2 = normalizeNullable(shipAddress2);
        this.shipAddress3 = normalizeNullable(shipAddress3);
        this.shipCity = normalizeNullable(shipCity);
        this.shipState = normalizeNullable(shipState);
        this.shipPostalCode = normalizeNullable(shipPostalCode);
        this.shipCountry = normalizeNullable(shipCountry);
        this.shipServiceLevel = normalizeNullable(shipServiceLevel);
        this.shipServiceName = normalizeNullable(shipServiceName);
        this.productName = normalizeNullable(productName);
        this.quantityPurchased = quantityPurchased;
        this.quantityShipped = quantityShipped;
        this.rawItemType = requireNonBlank(rawItemType, "rawItemType");
        this.normalizedItemType = requireNonBlank(normalizedItemType, "normalizedItemType");
        this.downloadUrl = requireNonBlank(downloadUrl, "downloadUrl");
        this.customizable = customizable;
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

    public String purchaseDate() {
        return purchaseDate;
    }

    public String buyerName() {
        return buyerName;
    }

    public String buyerEmail() {
        return buyerEmail;
    }

    public String buyerPhoneNumber() {
        return buyerPhoneNumber;
    }

    public String recipientName() {
        return recipientName;
    }

    public String shipAddress1() {
        return shipAddress1;
    }

    public String shipAddress2() {
        return shipAddress2;
    }

    public String shipAddress3() {
        return shipAddress3;
    }

    public String shipCity() {
        return shipCity;
    }

    public String shipState() {
        return shipState;
    }

    public String shipPostalCode() {
        return shipPostalCode;
    }

    public String shipCountry() {
        return shipCountry;
    }

    public String shipServiceLevel() {
        return shipServiceLevel;
    }

    public String shipServiceName() {
        return shipServiceName;
    }

    public String productName() {
        return productName;
    }

    public int quantityPurchased() {
        return quantityPurchased;
    }

    public int quantityShipped() {
        return quantityShipped;
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

    public boolean customizable() {
        return customizable;
    }

    private static String normalizeNullable(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return "AmazonOrderRecord{" +
            "orderId='" + orderId + '\'' +
            ", orderItemId='" + orderItemId + '\'' +
            ", purchaseDate='" + purchaseDate + '\'' +
            ", buyerName='" + buyerName + '\'' +
            ", buyerEmail='" + buyerEmail + '\'' +
            ", buyerPhoneNumber='" + buyerPhoneNumber + '\'' +
            ", recipientName='" + recipientName + '\'' +
            ", shipAddress1='" + shipAddress1 + '\'' +
            ", shipAddress2='" + shipAddress2 + '\'' +
            ", shipAddress3='" + shipAddress3 + '\'' +
            ", shipCity='" + shipCity + '\'' +
            ", shipState='" + shipState + '\'' +
            ", shipPostalCode='" + shipPostalCode + '\'' +
            ", shipCountry='" + shipCountry + '\'' +
            ", shipServiceLevel='" + shipServiceLevel + '\'' +
            ", shipServiceName='" + shipServiceName + '\'' +
            ", productName='" + productName + '\'' +
            ", quantityPurchased=" + quantityPurchased +
            ", quantityShipped=" + quantityShipped +
            ", rawItemType='" + rawItemType + '\'' +
            ", normalizedItemType='" + normalizedItemType + '\'' +
            ", downloadUrl='" + downloadUrl + '\'' +
            ", customizable=" + customizable +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AmazonOrderRecord that)) return false;
        return orderId.equals(that.orderId)
            && orderItemId.equals(that.orderItemId)
            && Objects.equals(purchaseDate, that.purchaseDate)
            && buyerName.equals(that.buyerName)
            && Objects.equals(buyerEmail, that.buyerEmail)
            && Objects.equals(buyerPhoneNumber, that.buyerPhoneNumber)
            && Objects.equals(recipientName, that.recipientName)
            && Objects.equals(shipAddress1, that.shipAddress1)
            && Objects.equals(shipAddress2, that.shipAddress2)
            && Objects.equals(shipAddress3, that.shipAddress3)
            && Objects.equals(shipCity, that.shipCity)
            && Objects.equals(shipState, that.shipState)
            && Objects.equals(shipPostalCode, that.shipPostalCode)
            && Objects.equals(shipCountry, that.shipCountry)
            && Objects.equals(shipServiceLevel, that.shipServiceLevel)
            && Objects.equals(shipServiceName, that.shipServiceName)
            && Objects.equals(productName, that.productName)
            && quantityPurchased == that.quantityPurchased
            && quantityShipped == that.quantityShipped
            && rawItemType.equals(that.rawItemType)
            && normalizedItemType.equals(that.normalizedItemType)
            && downloadUrl.equals(that.downloadUrl)
            && customizable == that.customizable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, orderItemId, purchaseDate, buyerName, buyerEmail, buyerPhoneNumber,
            recipientName, shipAddress1, shipAddress2, shipAddress3, shipCity, shipState, shipPostalCode,
            shipCountry, shipServiceLevel, shipServiceName, productName, quantityPurchased, quantityShipped,
            rawItemType, normalizedItemType, downloadUrl, customizable);
    }
}
