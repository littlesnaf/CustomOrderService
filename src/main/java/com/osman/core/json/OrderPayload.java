package com.osman.core.json;

import com.osman.core.model.OrderInfo;

/**
 * Snapshot of all parsed data from an order JSON so downstream consumers reuse the same payload.
 */
public record OrderPayload(OrderInfo info,
                           int totalQuantity,
                           String designSide,
                           JsonOrderLoader.ImageFileInfo images,
                           int mugOunces) {
}
