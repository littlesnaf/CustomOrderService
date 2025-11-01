package com.osman.ui.labelfinder;

import java.awt.image.BufferedImage;
import java.util.List;

record RenderedOrder(List<BufferedImage> labelPages,
                     List<BufferedImage> slipPages,
                     BufferedImage combinedPreview,
                     LabelLocation labelLocation,
                     PageGroup labelSource,
                     PageGroup slipSource) {
}
