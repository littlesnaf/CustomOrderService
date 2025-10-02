package com.osman.core.render;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides access to predefined mug templates keyed by ounce size.
 */
public final class TemplateRegistry {
    private static final Map<Integer, MugTemplate> TEMPLATES = new ConcurrentHashMap<>();

    static {
        TEMPLATES.put(11, MugTemplate.of(
                2580, 1440, 3200,
                144, 78, 952, 974,
                1524, 75, 944, 975,
                336, 1048, 1016, 1040,
                1808, 1048, 1016, 1040
        ));
        TEMPLATES.put(15, MugTemplate.of(
                2580, 1572, 3200,
                146, 112, 944, 1088,
                1518, 111, 944, 1088,
                367, 1036, 1050, 1210,
                1831, 1036, 1050, 1210
        ));
    }

    private TemplateRegistry() {
    }

    public static MugTemplate forOunces(int ounces) {
        return TEMPLATES.getOrDefault(ounces, TEMPLATES.get(11));
    }

    public static final class MugTemplate {
        public final int finalWidth;
        public final int finalHeight;
        public final int renderSize;

        public final int area1X;
        public final int area1Y;
        public final int area1Width;
        public final int area1Height;

        public final int area2X;
        public final int area2Y;
        public final int area2Width;
        public final int area2Height;

        public final int crop1X;
        public final int crop1Y;
        public final int crop1Width;
        public final int crop1Height;

        public final int crop2X;
        public final int crop2Y;
        public final int crop2Width;
        public final int crop2Height;

        private MugTemplate(int finalWidth,
                             int finalHeight,
                             int renderSize,
                             int area1X,
                             int area1Y,
                             int area1Width,
                             int area1Height,
                             int area2X,
                             int area2Y,
                             int area2Width,
                             int area2Height,
                             int crop1X,
                             int crop1Y,
                             int crop1Width,
                             int crop1Height,
                             int crop2X,
                             int crop2Y,
                             int crop2Width,
                             int crop2Height) {
            this.finalWidth = finalWidth;
            this.finalHeight = finalHeight;
            this.renderSize = renderSize;
            this.area1X = area1X;
            this.area1Y = area1Y;
            this.area1Width = area1Width;
            this.area1Height = area1Height;
            this.area2X = area2X;
            this.area2Y = area2Y;
            this.area2Width = area2Width;
            this.area2Height = area2Height;
            this.crop1X = crop1X;
            this.crop1Y = crop1Y;
            this.crop1Width = crop1Width;
            this.crop1Height = crop1Height;
            this.crop2X = crop2X;
            this.crop2Y = crop2Y;
            this.crop2Width = crop2Width;
            this.crop2Height = crop2Height;
        }

        private static MugTemplate of(int finalWidth,
                                      int finalHeight,
                                      int renderSize,
                                      int area1X,
                                      int area1Y,
                                      int area1Width,
                                      int area1Height,
                                      int area2X,
                                      int area2Y,
                                      int area2Width,
                                      int area2Height,
                                      int crop1X,
                                      int crop1Y,
                                      int crop1Width,
                                      int crop1Height,
                                      int crop2X,
                                      int crop2Y,
                                      int crop2Width,
                                      int crop2Height) {
            return new MugTemplate(finalWidth, finalHeight, renderSize,
                    area1X, area1Y, area1Width, area1Height,
                    area2X, area2Y, area2Width, area2Height,
                    crop1X, crop1Y, crop1Width, crop1Height,
                    crop2X, crop2Y, crop2Width, crop2Height);
        }
    }
}
