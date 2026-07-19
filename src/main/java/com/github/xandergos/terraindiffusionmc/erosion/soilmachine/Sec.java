package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

/**
 * A single layer section in the RLE linked-list heightmap. Maps to sec in layermap.h.
 */
public class Sec {

    public Sec next = null;  // Element above
    public Sec prev = null;  // Element below

    public int type;              // Soil type index
    public double size = 0.0;    // Run-Length (thickness)
    public double floor = 0.0;   // Cumulative height at bottom
    public double saturation = 0.0; // Water saturation (0-1)

    public Sec() {}

    public Sec(double size, int type) {
        this.size = size;
        this.type = type;
    }

    public void reset() {
        next = null;
        prev = null;
        type = 0;
        size = 0;
        floor = 0;
        saturation = 0;
    }
}
