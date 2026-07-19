package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;

/**
 * Parallel hydraulic erosion using a hybrid approach:
 * - Move+Interact phase runs in parallel on flat snapshot arrays
 * - Flood/Cascade/Seep phase runs sequentially on the RLE Layermap
 *
 * This preserves correctness because flood/cascade/seep depend on the RLE
 * layer structure (water layers, saturation, porosity) which cannot be
 * faithfully represented in flat arrays.
 */
public class ParallelHydraulicErosion {

    private final int sizeX, sizeY, scale;
    private final SoilType[] soils;

    private static final double VOLUME_FACTOR = 0.015;
    private static final double MIN_VOL = 0.01;
    private static final int SPILL = 3;

    private static final int[][] NEIGHBORS = {
        {-1, -1}, {-1, 0}, {-1, 1}, {0, -1},
        {0, 1}, {1, -1}, {1, 0}, {1, 1}
    };

    public ParallelHydraulicErosion(int sizeX, int sizeY, int scale, SoilType[] soils) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.scale = scale;
        this.soils = soils;
    }

    // ── Flat Map Snapshot ──

    public static class FlatSnapshot {
        public final double[][] height;
        public final int[][] surface;
        public final int sizeX, sizeY;

        public FlatSnapshot(Layermap map) {
            this.sizeX = map.dimX;
            this.sizeY = map.dimY;
            this.height = new double[sizeY][sizeX];
            this.surface = new int[sizeY][sizeX];
            for (int y = 0; y < sizeY; y++)
                for (int x = 0; x < sizeX; x++) {
                    height[y][x] = map.height(x, y);
                    surface[y][x] = map.surface(x, y);
                }
        }

        private FlatSnapshot(int sizeX, int sizeY) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.height = new double[sizeY][sizeX];
            this.surface = new int[sizeY][sizeX];
        }
    }

    // ── Delta Accumulator ──

    public static class DeltaMap {
        public final double[][] delta;
        public final int sizeX, sizeY;

        public DeltaMap(int sizeX, int sizeY) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.delta = new double[sizeY][sizeX];
        }

        public double getHeight(FlatSnapshot snap, int x, int y) {
            if (x < 0 || x >= sizeX || y < 0 || y >= sizeY) return 0;
            return snap.height[y][x] + delta[y][x];
        }

        public void addHeight(int x, int y, double amount) {
            if (x < 0 || x >= sizeX || y < 0 || y >= sizeY) return;
            delta[y][x] += amount;
        }

        public double removeHeight(FlatSnapshot snap, int x, int y, double amount) {
            if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || amount <= 0) return 0;
            double currentH = snap.height[y][x] + delta[y][x];
            double removed = Math.min(currentH, amount);
            delta[y][x] -= removed;
            return removed;
        }
    }

    // ── Particle final state (for sequential flood phase) ──

    private static class ParticleFinalState {
        final int x, y;
        final double volume, sediment;
        final int spill;

        ParticleFinalState(int x, int y, double volume, double sediment, int spill) {
            this.x = x; this.y = y;
            this.volume = volume; this.sediment = sediment;
            this.spill = spill;
        }
    }

    // ── Normal Computation ──

    private double[] computeNormal(FlatSnapshot snap, DeltaMap dm, int x, int y) {
        double nx = 0, ny = 0, nz = 0;
        int k = 0;
        double s = (double) scale;

        if (x > 0 && y > 0) {
            nx += s * (-dm.getHeight(snap, x - 1, y) + dm.getHeight(snap, x, y));
            nz += s * (-dm.getHeight(snap, x, y - 1) + dm.getHeight(snap, x, y));
            ny += 1.0; k++;
        }
        if (x > 0 && y < sizeY - 1) {
            nx += s * (-dm.getHeight(snap, x - 1, y) + dm.getHeight(snap, x, y));
            nz += s * ( dm.getHeight(snap, x, y + 1) - dm.getHeight(snap, x, y));
            ny += 1.0; k++;
        }
        if (x < sizeX - 1 && y > 0) {
            nx += s * ( dm.getHeight(snap, x + 1, y) - dm.getHeight(snap, x, y));
            nz += s * (-dm.getHeight(snap, x, y - 1) + dm.getHeight(snap, x, y));
            ny += 1.0; k++;
        }
        if (x < sizeX - 1 && y < sizeY - 1) {
            nx += s * ( dm.getHeight(snap, x + 1, y) - dm.getHeight(snap, x, y));
            nz += s * ( dm.getHeight(snap, x, y + 1) - dm.getHeight(snap, x, y));
            ny += 1.0; k++;
        }

        if (k == 0) return new double[]{0, 1, 0};
        double inv = 1.0 / k;
        nx *= inv; ny *= inv; nz *= inv;
        double mag = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (mag < 1e-10) return new double[]{0, 1, 0};
        return new double[]{nx / mag, ny / mag, nz / mag};
    }

    private double heightInterp(FlatSnapshot snap, DeltaMap dm, double px, double py) {
        int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py);
        double wx = px - x0, wy = py - y0;
        return (1 - wx) * (1 - wy) * dm.getHeight(snap, x0, y0)
             + (1 - wx) *      wy  * dm.getHeight(snap, x0, y0 + 1)
             +      wx  * (1 - wy) * dm.getHeight(snap, x0 + 1, y0)
             +      wx  *      wy  * dm.getHeight(snap, x0 + 1, y0 + 1);
    }

    // ── Thermal Cascade ──

    private void cascade(FlatSnapshot snap, DeltaMap dm, double px, double py, int transferLoop) {
        int ix = (int) Math.round(px);
        int iy = (int) Math.round(py);

        int count = 0;
        int[] nnx = new int[8], nny = new int[8];
        double[] nh = new double[8];
        for (int[] d : NEIGHBORS) {
            int nx = ix + d[0], ny = iy + d[1];
            if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY) continue;
            nnx[count] = nx; nny[count] = ny;
            nh[count] = dm.getHeight(snap, nx, ny);
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

            double diff = (dm.getHeight(snap, ix, iy) - dm.getHeight(snap, nx, ny)) * (double) scale / 80.0;
            if (diff == 0) continue;

            int tx, ty, bx, by;
            if (diff > 0) { tx = ix; ty = iy; bx = nx; by = ny; }
            else { tx = nx; ty = ny; bx = ix; by = iy; }

            int surfType = snap.surface[ty][tx];
            SoilType param = soils[surfType];

            double excess = Math.abs(diff) - param.maxDiff;
            if (excess <= 0) continue;

            double transfer = param.settling * excess / 2.0;
            double cellH = dm.getHeight(snap, tx, ty);
            transfer = Math.min(transfer, cellH);
            if (transfer <= 0) continue;

            dm.removeHeight(snap, tx, ty, transfer);
            dm.addHeight(bx, by, transfer);

            if (transferLoop > 0) cascade(snap, dm, nx, ny, transferLoop - 1);
        }
    }

    // ── Parallel Phase: Move + Interact only ──

    private void runParticleMoveInteract(FlatSnapshot snap, DeltaMap dm, float[] readFreq, float[] track,
                                          Random rng, List<ParticleFinalState> states) {
        double px = rng.nextInt(sizeX);
        double py = rng.nextInt(sizeY);
        double sx = 0, sy = 0;
        double volume = 1.0, sediment = 0.0;

        while (true) {
            boolean alive = true;
            while (alive) {
                int ix = (int) Math.round(px);
                int iy = (int) Math.round(py);
                if (ix < 0 || ix >= sizeX || iy < 0 || iy >= sizeY) { volume = 0; break; }

                double[] n = computeNormal(snap, dm, ix, iy);

                int freqIdx = iy * sizeX + ix;
                int surfType = snap.surface[iy][ix];
                SoilType param = soils[surfType];
                double freqVal = Math.min(readFreq[freqIdx], 5.0);
                double friction = param.friction * Math.max(0, 1.0 - freqVal);

                double nx = n[0], nz = n[2];
                if (Math.sqrt(nx * nx + nz * nz) * friction < 1e-5) { volume = 0; break; }

                track[freqIdx] += volume;

                sx = nx * (1 - friction) + sx * friction;
                sy = nz * (1 - friction) + sy * friction;
                double mag = Math.sqrt(sx * sx + sy * sy);
                if (mag < 1e-5) { volume = 0; break; }
                double s = Math.sqrt(2.0) / mag;
                sx *= s; sy *= s;
                px += sx; py += sy;

                if (px < 0 || px >= sizeX - 1 || py < 0 || py >= sizeY - 1) { volume = 0; break; }

                ix = (int) Math.round(px);
                iy = (int) Math.round(py);
                if (ix < 0 || ix >= sizeX || iy < 0 || iy >= sizeY) { volume = 0; break; }

                double hHere = heightInterp(snap, dm, px, py);
                double[] nn = computeNormal(snap, dm, ix, iy);
                double hThere = heightInterp(snap, dm, px + nn[0], py + nn[2]);
                double cEq = soils[snap.surface[iy][ix]].solubility * (hHere - hThere) * (double) scale / 80.0;
                cEq = Math.max(0, Math.min(1, cEq));

                double cDiff = cEq - sediment;
                if (cDiff > 0) {
                    sediment += soils[snap.surface[iy][ix]].equrate * cDiff;
                    double amount = soils[snap.surface[iy][ix]].equrate * cDiff * volume;
                    double diff = dm.removeHeight(snap, ix, iy, amount);
                    while (Math.abs(diff) > 1e-8) diff = dm.removeHeight(snap, ix, iy, diff);
                } else if (cDiff < 0) {
                    sediment += soils[snap.surface[iy][ix]].equrate * cDiff;
                    dm.addHeight(ix, iy, -soils[snap.surface[iy][ix]].equrate * cDiff * volume);
                }

                cascade(snap, dm, px, py, 0);

                double evaprate = 0.01 * (1.0 - 0.2 * freqVal);
                if (evaprate < 0) evaprate = 0;
                sediment /= (1.0 - evaprate);
                if (sediment > 1.0) sediment = 1.0;
                volume *= (1.0 - evaprate);
                alive = volume > MIN_VOL && Double.isFinite(volume) && Double.isFinite(sediment);
            }

            if (!Double.isFinite(volume) || !Double.isFinite(sediment) || volume < MIN_VOL) break;

            int ix = (int) Math.round(px);
            int iy = (int) Math.round(py);
            if (ix < 0 || ix >= sizeX || iy < 0 || iy >= sizeY) break;

            // Record state for sequential flood phase
            synchronized (states) {
                states.add(new ParticleFinalState(ix, iy, volume, sediment, SPILL));
            }
            break;
        }
    }

    // ── Write Back ──

    private void writeBack(Layermap map, FlatSnapshot snap) {
        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                double targetH = snap.height[y][x];
                double currentH = map.height(x, y);
                double diff = targetH - currentH;
                if (diff > 0) {
                    map.add(x, y, map.pool.get(diff, snap.surface[y][x]));
                } else if (diff < 0) {
                    double rem = map.remove(x, y, -diff);
                    while (rem > 1e-8) rem = map.remove(x, y, rem);
                }
            }
        }
    }

    // ── Hybrid Erosion ──

    public void erode(Layermap map, int iterations, int nWater, long baseSeed, ForkJoinPool pool) {
        int nThreads = pool.getParallelism();
        System.out.printf("Parallel erosion: %d threads, %d particles/iteration%n", nThreads, nWater);

        float[] globalFrequency = new float[sizeX * sizeY];

        for (int iter = 0; iter < iterations; iter++) {
            // ── Phase 1: Snapshot ──
            FlatSnapshot snap = new FlatSnapshot(map);

            // ── Phase 2: Parallel move+interact ──
            int batchSize = (nWater + nThreads - 1) / nThreads;
            DeltaMap[] threadDeltas = new DeltaMap[nThreads];
            float[][] threadTrack = new float[nThreads][sizeX * sizeY];
            List<ParticleFinalState> allStates = new ArrayList<>();
            final long iterSeed = baseSeed + (long) iter * nWater;

            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int batch = 0; batch < nThreads; batch++) {
                final int b = batch;
                final List<ParticleFinalState> threadStates = new ArrayList<>();
                futures.add(pool.submit(() -> {
                    int start = b * batchSize;
                    int end = Math.min(start + batchSize, nWater);
                    DeltaMap dm = new DeltaMap(sizeX, sizeY);
                    threadDeltas[b] = dm;
                    float[] track = new float[sizeX * sizeY];

                    for (int i = start; i < end; i++) {
                        Random rng = new Random(iterSeed + i);
                        runParticleMoveInteract(snap, dm, globalFrequency, track, rng, threadStates);
                    }
                    threadTrack[b] = track;
                    synchronized (allStates) {
                        allStates.addAll(threadStates);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }

            // ── Phase 3: Merge deltas into snapshot ──
            for (int b = 0; b < nThreads; b++) {
                DeltaMap dm = threadDeltas[b];
                if (dm == null) continue;
                for (int y = 0; y < sizeY; y++)
                    for (int x = 0; x < sizeX; x++) {
                        snap.height[y][x] += dm.delta[y][x];
                        if (snap.height[y][x] < 0) snap.height[y][x] = 0;
                    }
            }

            // ── Phase 4: Write back to RLE map ──
            writeBack(map, snap);

            // ── Phase 5: Sequential flood + cascade + seep on RLE map ──
            for (ParticleFinalState state : allStates) {
                if (!Double.isFinite(state.volume) || state.volume < MIN_VOL) continue;
                int x = state.x, y = state.y;
                if (x < 0 || x >= sizeX || y < 0 || y >= sizeY) continue;

                WaterParticle wp = new WaterParticle(sizeX, sizeY, new Random(0));
                wp.px = x;
                wp.py = y;
                wp.volume = state.volume;
                wp.sediment = state.sediment;
                wp.flood(map, soils, scale, state.spill);
            }

            WaterParticle.globalSeep(map, soils, scale);

            // ── Phase 6: Merge tracks + low-pass filter ──
            float[] mergedTrack = new float[sizeX * sizeY];
            for (int b = 0; b < nThreads; b++) {
                float[] track = threadTrack[b];
                if (track == null) continue;
                for (int i = 0; i < sizeX * sizeY; i++)
                    mergedTrack[i] += track[i];
            }
            float lrate = 0.01f, K = 50.0f;
            for (int i = 0; i < sizeX * sizeY; i++)
                globalFrequency[i] = (1 - lrate) * globalFrequency[i] + lrate * K * mergedTrack[i] / (1 + K * mergedTrack[i]);

            if ((iter + 1) % 100 == 0 || iter == 0) {
                System.out.printf("  iteration %d/%d%n", iter + 1, iterations);
            }
        }
    }
}
