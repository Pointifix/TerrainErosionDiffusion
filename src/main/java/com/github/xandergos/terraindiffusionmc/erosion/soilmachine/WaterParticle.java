package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

/**
 * Water particle for hydraulic erosion. Maps to WaterParticle in water.h.
 * Moves along surface normals, picks up/deposits sediment, triggers thermal cascade.
 */
public class WaterParticle extends Particle {

    public double volume = 1.0;
    public double sediment = 0.0;

    public static final double MIN_VOL = 0.01;
    public static final double VOLUME_FACTOR = 0.015;
    public static final int SPILL = 3;

    // Frequency tracking (static, shared across all particles)
    public static float[] frequency;
    public static float[] track;

    private static final int[][] NEIGHBORS = {
        {-1, -1}, {-1, 0}, {-1, 1}, {0, -1},
        {0, 1}, {1, -1}, {1, 0}, {1, 1}
    };

    public WaterParticle(int sizeX, int sizeY, java.util.Random rng) {
        px = rng.nextInt(sizeX);
        py = rng.nextInt(sizeY);
    }

    // ── Frequency ──

    public static void initFrequency(int sizeX, int sizeY) {
        frequency = new float[sizeX * sizeY];
        track = new float[sizeX * sizeY];
    }

    public void updateFrequency(int x, int y, int sizeX) {
        if (x >= 0 && y >= 0) track[y * sizeX + x] += volume;
    }

    public static void resetFrequency(int sizeX, int sizeY) {
        java.util.Arrays.fill(track, 0.0f);
    }

    public static void mapFrequency(int sizeX, int sizeY) {
        float lrate = 0.01f, K = 50.0f;
        for (int i = 0; i < sizeX * sizeY; i++)
            frequency[i] = (1 - lrate) * frequency[i] + lrate * K * track[i] / (1 + K * track[i]);
    }

    // ── Move ──

    public boolean move(Layermap map, SoilType[] soils, int scale) {
        int ix = (int) Math.round(px);
        int iy = (int) Math.round(py);
        if (ix < 0 || ix >= map.dimX || iy < 0 || iy >= map.dimY) {
            volume = 0; return false;
        }

        double[] n = map.normal(ix, iy, scale);
        updateFrequency(ix, iy, map.dimX);

        int freqIdx = iy * map.dimX + ix;
        int surfType = map.surface(ix, iy);
        SoilType param = soils[surfType];

        double friction = param.friction * (1.0 - frequency[freqIdx]);
        double evaprate = 0.01 * (1.0 - 0.2 * frequency[freqIdx]);

        double nx = n[0], nz = n[2];
        double dirLen = Math.sqrt(nx * nx + nz * nz) * friction;
        if (dirLen < 1e-5) return false;

        // Mix speed toward surface normal direction
        sx = nx * (1 - friction) + sx * friction;
        sy = nz * (1 - friction) + sy * friction;
        double mag = Math.sqrt(sx * sx + sy * sy);
        if (mag < 1e-5) return false;
        double s = Math.sqrt(2.0) / mag;
        sx *= s; sy *= s;

        px += sx;
        py += sy;

        // Out of bounds
        if (px < 0 || px >= map.dimX - 1 || py < 0 || py >= map.dimY - 1) {
            volume = 0; return false;
        }
        return true;
    }

    // ── Interact ──

    public boolean interact(Layermap map, SoilType[] soils, int scale) {
        int ix = (int) Math.round(px);
        int iy = (int) Math.round(py);
        if (ix < 0 || ix >= map.dimX || iy < 0 || iy >= map.dimY) return false;

        // Equilibrium sediment
        double hHere = map.height(px, py);
        double[] n = map.normal(ix, iy, scale);
        double hThere = map.height(px + n[0], py + n[2]);
        double cEq = soils[map.surface(ix, iy)].solubility * (hHere - hThere) * (double) scale / 80.0;
        if (cEq < 0) cEq = 0;
        if (cEq > 1) cEq = 1;

        int freqIdx = iy * map.dimX + ix;

        // Erode sediment type based on frequency
        int contains = map.surface(ix, iy);

        double cDiff = cEq - sediment;

        if (cDiff > 0) {
            // Pick up sediment
            sediment += soils[contains].equrate * cDiff;
            double amount = soils[contains].equrate * cDiff * volume;
            double diff = map.remove(ix, iy, amount);
            while (Math.abs(diff) > 1e-8) {
                diff = map.remove(ix, iy, diff);
            }
        } else if (cDiff < 0) {
            // Deposit sediment
            sediment += soils[contains].equrate * cDiff;
            double amount = -soils[contains].equrate * cDiff * volume;
            map.add(ix, iy, map.pool.get(amount, contains));
        }

        // Thermal cascade
        Particle.cascade(px, py, map, soils, scale, 0);

        // Evaporate
        double evaprate = 0.01 * (1.0 - 0.2 * frequency[freqIdx]);
        sediment /= (1.0 - evaprate);
        if (sediment > 1.0) sediment = 1.0;
        volume *= (1.0 - evaprate);

        return volume > MIN_VOL;
    }

    // ── Flood ──

    public boolean flood(Layermap map, SoilType[] soils, int scale, int spill) {
        if (volume < MIN_VOL || spill <= 0) return false;

        int ix = (int) Math.round(px);
        int iy = (int) Math.round(py);
        if (ix < 0 || ix >= map.dimX || iy < 0 || iy >= map.dimY) return false;

        // Deposit remaining sediment
        int contains = map.surface(ix, iy);
        double sedDeposit = sediment * soils[contains].equrate;
        if (sedDeposit > 0) {
            map.add(ix, iy, map.pool.get(sedDeposit, contains));
        }
        Particle.cascade(px, py, map, soils, scale, 0);

        // Add water layer
        double waterH = volume * VOLUME_FACTOR;
        if (waterH > 0) {
            map.add(ix, iy, map.pool.get(waterH, 0)); // Air = water
            seep(ix, iy, map, soils);
        }

        // Water cascade
        waterCascade(ix, iy, map, soils, scale, spill);

        return false;
    }

    // ── Water Cascade ──

    private static void waterCascade(int cx, int cy, Layermap map, SoilType[] soils, int scale, int spill) {
        int count = 0;
        int[] nnx = new int[8], nny = new int[8];
        double[] nh = new double[8];
        for (int[] d : NEIGHBORS) {
            int nx = cx + d[0], ny = cy + d[1];
            if (nx < 0 || nx >= map.dimX || ny < 0 || ny >= map.dimY) continue;
            nnx[count] = nx; nny[count] = ny;
            nh[count] = map.height(nx, ny);
            count++;
        }

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

            Sec topA = map.top(cx, cy);
            Sec topB = map.top(nx, ny);
            if (topA == null) continue;

            double whA = topA.type == 0 ? topA.size : 0; // Air = water
            double whB = (topB != null && topB.type == 0) ? topB.size : 0;
            double fA = topA.floor;
            double fB = topB != null ? topB.floor : 0;

            double diff = (fA + whA - fB - whB) * (double) scale / 80.0;
            if (diff == 0) continue;

            int tx, ty, bx, by;
            if (diff > 0) { tx = cx; ty = cy; bx = nx; by = ny; }
            else { tx = nx; ty = ny; bx = cx; by = cy; }

            Sec top = map.top(tx, ty);
            if (top == null || top.type != 0) continue; // Only cascade air (water)

            double transfer = Math.abs(diff) / 2.0;
            transfer = Math.min(transfer, top.size);
            if (transfer <= 0) continue;

            map.remove(tx, ty, transfer);
            map.add(bx, by, map.pool.get(transfer, 0));

            if (spill > 0) {
                waterCascade(nx, ny, map, soils, scale, spill - 1);
            }
        }
    }

    // ── Seep ──

    private static void seep(int ix, int iy, Layermap map, SoilType[] soils) {
        Sec top = map.top(ix, iy);
        if (top == null) return;

        while (top != null && top.prev != null) {
            Sec prev = top.prev;
            double vol = top.size * top.saturation * soils[top.type].porosity;
            double nvol = prev.size * prev.saturation * soils[prev.type].porosity;
            double nevol = prev.size * (1.0 - prev.saturation) * soils[prev.type].porosity;

            double transfer = Math.min(vol, nevol);

            if (transfer > 0) {
                double seepage = 1.0;
                if (top.type == 0) { // Air
                    map.remove(ix, iy, seepage * transfer);
                } else {
                    top.saturation -= (seepage * transfer) / (top.size * soils[top.type].porosity);
                }
                prev.saturation += (seepage * transfer) / (prev.size * soils[prev.type].porosity);
            }
            top = prev;
        }
    }

    /** Global seep pass. Maps to WaterParticle::seep(Layermap&, Vertexpool&). */
    public static void globalSeep(Layermap map, SoilType[] soils, int scale) {
        for (int x = 0; x < map.dimX; x++) {
            for (int y = 0; y < map.dimY; y++) {
                seep(x, y, map, soils);
                waterCascade(x, y, map, soils, scale, 3);
            }
        }
    }
}
