package com.osman.core.model;

import java.nio.file.Path;

/**
 * Metadata for an artwork asset that belongs to an order.
 */
public record DesignAsset(Path path, String label, DesignSide side) {
    public enum DesignSide { FRONT, BACK, BOTH }
}
