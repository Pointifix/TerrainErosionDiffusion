package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

import java.util.ArrayList;

/**
 * Layermap: heightmap represented as a grid of RLE linked-list layers.
 * Maps to Layermap class in layermap.h.
 */
public class Layermap {

    public final int dimX, dimY;
    public final SecPool pool;
    private final Sec[] dat;  // 2D array of pointers to top Sec at each cell

    public Layermap(int dimX, int dimY, int poolSize) {
        this.dimX = dimX;
        this.dimY = dimY;
        this.pool = new SecPool(poolSize);
        this.dat = new Sec[dimX * dimY];
    }

    // ── Queries ──

    public double height(int x, int y) {
        Sec top = top(x, y);
        if (top == null) return 0.0;
        return top.floor + top.size;
    }

    /** Bilinear interpolated height. */
    public double height(double px, double py) {
        int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py);
        double wx = px - x0, wy = py - y0;
        return (1 - wx) * (1 - wy) * height(x0, y0)
             + (1 - wx) *      wy  * height(x0, y0 + 1)
             +      wx  * (1 - wy) * height(x0 + 1, y0)
             +      wx  *      wy  * height(x0 + 1, y0 + 1);
    }

    /** Surface normal at integer position. Height is scaled by SCALE to get proper slope.
     *  Horizontal components point DOWNHILL (negative gradient) so that water particles
     *  move in the direction of the normal and flow downhill naturally. */
    public double[] normal(int x, int y, int scale) {
        double nx = 0, ny = 0, nz = 0;
        int k = 0;
        double s = (double) scale;

        if (x > 0 && y > 0) {
            nx += s * ( height(x - 1, y) - height(x, y));
            nz += s * ( height(x, y - 1) - height(x, y));
            ny += 1.0; k++;
        }
        if (x > 0 && y < dimY - 1) {
            nx += s * ( height(x - 1, y) - height(x, y));
            nz += s * (-height(x, y + 1) + height(x, y));
            ny += 1.0; k++;
        }
        if (x < dimX - 1 && y > 0) {
            nx += s * (-height(x + 1, y) + height(x, y));
            nz += s * ( height(x, y - 1) - height(x, y));
            ny += 1.0; k++;
        }
        if (x < dimX - 1 && y < dimY - 1) {
            nx += s * (-height(x + 1, y) + height(x, y));
            nz += s * (-height(x, y + 1) + height(x, y));
            ny += 1.0; k++;
        }

        if (k == 0) return new double[]{0, 1, 0};
        double inv = 1.0 / k;
        nx *= inv; ny *= inv; nz *= inv;
        double mag = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (mag < 1e-10) return new double[]{0, 1, 0};
        return new double[]{nx / mag, ny / mag, nz / mag};
    }

    /** Surface type at position. */
    public int surface(int x, int y) {
        Sec top = top(x, y);
        return top == null ? 0 : top.type;
    }

    /** Water height (sum of type-0 layers) at position. */
    public double waterHeight(int x, int y) {
        double wh = 0;
        Sec s = top(x, y);
        while (s != null) {
            if (s.type == 0) wh += s.size;
            s = s.prev;
        }
        return wh;
    }

    /** Top Sec at position. */
    public Sec top(int x, int y) {
        if (x < 0 || x >= dimX || y < 0 || y >= dimY) return null;
        return dat[x * dimY + y];
    }

    // ── Modifiers ──

    /** Add a layer at position. Handles type merging and water swap. */
    public void add(int x, int y, Sec e) {
        if (e == null) return;
        if (e.size <= 0) { pool.unget(e); return; }

        int idx = x * dimY + y;

        // Empty spot
        if (dat[idx] == null) {
            dat[idx] = e;
            return;
        }

        // Same type: merge
        if (dat[idx].type == e.type) {
            dat[idx].size += e.size;
            pool.unget(e);
            return;
        }

        // Adding soil on top of water (Air): swap, add soil below, put water back on top
        if (dat[idx].type == 0) { // Air on top
            Sec top = dat[idx];
            dat[idx] = top.prev;  // Remove water from top
            add(x, y, e);         // Add soil
            add(x, y, top);       // Put water back
            return;
        }

        // Push onto stack
        e.prev = dat[idx];
        e.floor = height(x, y);
        dat[idx] = e;
    }

    /** Remove height from top of cell. Returns overflow amount. */
    public double remove(int x, int y, double h) {
        int idx = x * dimY + y;
        if (dat[idx] == null) return 0.0;

        if (dat[idx].size <= 0.0) {
            Sec e = dat[idx];
            dat[idx] = e.prev;
            pool.unget(e);
            return 0.0;
        }

        if (h <= 0.0) return 0.0;

        double diff = h - dat[idx].size;
        dat[idx].size -= h;

        if (diff >= 0.0) {
            Sec e = dat[idx];
            dat[idx] = e.prev;
            pool.unget(e);
            return diff;
        }
        return 0.0;
    }

    // ── Initialization ──

    /** Initialize from noise layers, matching Layermap::initialize in layermap.h. */
    public void initialize(int seed, ArrayList<NoiseLayer> layers) {
        pool.reset();
        for (int i = 0; i < dat.length; i++) dat[i] = null;

        int maxSeed = 10000;
        for (int l = 0; l < layers.size(); l++) {
            NoiseLayer nl = layers.get(l);
            double f = (double) l / (double) layers.size();
            int z = seed + (int) (f * maxSeed);

            for (int i = 0; i < dimX; i++) {
                for (int j = 0; j < dimY; j++) {
                    double h = nl.get(
                        (double) i / dimX,
                        (double) j / dimY,
                        (z % maxSeed) / 1.0
                    );
                    add(i, j, pool.get(h, nl.soilType));
                }
            }
        }
    }
}
