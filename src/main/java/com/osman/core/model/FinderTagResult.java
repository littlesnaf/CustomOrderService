package com.osman.core.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Result bucket used by the finder UI when combining labels, slips, and assets.
 */
public record FinderTagResult(String orderId,
                              List<LabelMatch> labelMatches,
                              List<Path> photoCandidates) {
}
