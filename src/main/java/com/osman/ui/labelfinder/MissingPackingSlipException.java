package com.osman.ui.labelfinder;

final class MissingPackingSlipException extends Exception {
    private final String orderId;

    MissingPackingSlipException(String orderId) {
        super("Missing packing slip for order " + orderId);
        this.orderId = orderId;
    }

    String getOrderId() {
        return orderId;
    }
}
