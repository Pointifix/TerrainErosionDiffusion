package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

import java.util.Arrays;

/**
 * Base particle class with thermal cascade. Maps to particle.h.
 */
public class Particle {

    public double px, py;
    public double sx, sy;
    public boolean alive = true;

    private static final int[][] NEIGHBORS = {
        {-1, -1}, {-1, 0}, {-1, 1}, {0, -1},
        {0, 1}, {1, -1}, {1, 0}, {1, 1}
    };

    public Particle() {}

    /**
     * Thermal erosion cascade. Moves soil from high to low based on maxdiff/settling.
     * Maps to Particle::cascade() in particle.h.
     */
    public static void cascade(double px, double py, Layermap map, SoilType[] soils, int scale, int transferLoop) {
        int ix = (int) Math.round(px);
        int iy = (int) Math.round(py);

        // Gather valid neighbors
        int count = 0;
        int[] nnx = new int[8], nny = new int[8];
        double[] nh = new double[8];
        for (int[] d : NEIGHBORS) {
            int nx = ix + d[0], ny = iy + d[1];
            if (nx < 0 || nx >= map.dimX || ny < 0 || ny >= map.dimY) continue;
            nnx[count] = nx;
            nny[count] = ny;
            nh[count] = map.height(nx, ny);
            count++;
        }

        // Sort by height descending
        int[] order = new int[count];
        for (int i = 0; i < count; i++) order[i] = i;
        for (int i = 0; i < count - 1; i++)
            for (int j = i + 1; j < count; j++)
                if (nh[order[j]] > nh[order[i]]) {
                    int t = order[i]; order[i] = order[j]; order[j] = t;
                }

        for (int i = 0; i < count; i++) {
            int idx = order[i];
            int nx = nnx[idx], ny = nny[idx];

            double diff = (map.height(ix, iy) - map.height(nx, ny)) * (double) scale / 80.0;
            if (diff == 0) continue;

            int tx, ty, bx, by;
            if (diff > 0) { tx = ix; ty = iy; bx = nx; by = ny; }
            else { tx = nx; ty = ny; bx = ix; by = iy; }

            int surfaceType = map.surface(tx, ty);
            SoilType param = soils[surfaceType];

            double excess = Math.abs(diff) - param.maxDiff;
            if (excess <= 0) continue;

            double transfer = param.settling * excess / 2.0;
            transfer = Math.min(transfer, map.top(tx, ty) != null ? map.top(tx, ty).size : 0);
            if (transfer <= 0) continue;

            boolean recascade = false;
            double removed = map.remove(tx, ty, transfer);
            if (removed != 0) recascade = true;

            // Cascade the transferred soil as the surface type
            int cascadeType = param.cascades >= 0 ? param.cascades : surfaceType;
            map.add(bx, by, map.pool.get(transfer, cascadeType));

            if (recascade && transferLoop > 0) {
                cascade(nx, ny, map, soils, scale, transferLoop - 1);
            }
        }
    }
}
