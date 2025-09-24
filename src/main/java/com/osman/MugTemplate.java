package com.osman;

public class MugTemplate {
    final int FINAL_WIDTH;
    final int FINAL_HEIGHT;
    final int RENDER_SIZE;

    final int area1X, area1Y, area1Width, area1Height; // FRONT (sol)
    final int area2X, area2Y, area2Width, area2Height; // BACK  (sağ)

    final int crop1X, crop1Y, crop1Width, crop1Height;
    final int crop2X, crop2Y, crop2Width, crop2Height;

    MugTemplate(int fw, int fh, int rs,
                int a1x, int a1y, int a1w, int a1h,
                int a2x, int a2y, int a2w, int a2h,
                int c1x, int c1y, int c1w, int c1h,
                int c2x, int c2y, int c2w, int c2h) {
        this.FINAL_WIDTH = fw;
        this.FINAL_HEIGHT = fh;
        this.RENDER_SIZE = rs;
        this.area1X = a1x; this.area1Y = a1y; this.area1Width = a1w; this.area1Height = a1h;
        this.area2X = a2x; this.area2Y = a2y; this.area2Width = a2w; this.area2Height = a2h;
        this.crop1X = c1x; this.crop1Y = c1y; this.crop1Width = c1w; this.crop1Height = c1h;
        this.crop2X = c2x; this.crop2Y = c2y; this.crop2Width = c2w; this.crop2Height = c2h;
    }

    // 11 oz: mevcut üretim ölçüleriniz
    static MugTemplate OZ11() {
        return new MugTemplate(
                2580, 1410, 3200,
                144, 78,  952,  974,     // area1 (front)
                1524, 75, 944,  975,     // area2 (back)
                336, 1048, 1016, 1040,   // crop1
                1808,1048, 1016, 1040    // crop2
        );
    }

    /**
     * UPDATED: 15 oz template for 2580x1572 canvas with a precise 115px top margin.
     */
    static MugTemplate OZ15() {
        return new MugTemplate(
                2580, 1572, 3200,

                // Final print area, with the desired 115px top margin
                146, 112, 944, 1088,   // area1 (ön yüz)
                1518, 111, 944, 1088,  // area2 (arka yüz)


                367, 1036, 1050, 1210,   // crop1
                1831, 1036, 1050, 1210    // crop2
        );
    }
}
