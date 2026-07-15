package com.github.xandergos.terraindiffusionmc.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Particle-based hydraulic erosion (Droplet Method) for coarse terrain heightmaps.
 *
 * <p>Simulates rain droplets flowing downhill, eroding terrain and depositing sediment.
 * Produces physically plausible V-shaped river valleys, alluvial fans, and a river_flux
 * map that tracks cumulative water flow through each cell.
 *
 * <p>Designed to run on the coarse stage output (~64x64, ~7.7 km/pixel) before the
 * latent/decoder diffusion stages, so the high-resolution model generates terrain
 * conforming to the eroded drainage patterns.
 */
public final class HydraulicErosionSimulator {

    private static final Logger LOG = LoggerFactory.getLogger(HydraulicErosionSimulator.class);

    // Physical constants (tuned for coarse-scale ~7.7 km/pixel terrain)
    private final float inertia;
    private final float capacityCoef;
    private final float depositionRate;
    private final float erosionRate;
    private final float evaporationRate;
    private final float gravity;

    private final int numDroplets;
    private final int maxLifetime;

    /** Default parameters suitable for coarse-scale terrain (~7.7 km/pixel). */
    public static HydraulicErosionSimulator defaults() {
        return new HydraulicErosionSimulator.Builder().build();
    }

    private HydraulicErosionSimulator(Builder b) {
        this.inertia = b.inertia;
        this.capacityCoef = b.capacityCoef;
        this.depositionRate = b.depositionRate;
        this.erosionRate = b.erosionRate;
        this.evaporationRate = b.evaporationRate;
        this.gravity = b.gravity;
        this.numDroplets = b.numDroplets;
        this.maxLifetime = b.maxLifetime;
    }

    /**
     * Result of an erosion simulation.
     */
    public static final class ErosionResult {
        /** The eroded heightmap (modified in-place copy of input). */
        public final float[][] erodedMap;
        /** Cumulative water flow through each cell — higher values indicate river channels. */
        public final float[][] riverFlux;

        public ErosionResult(float[][] erodedMap, float[][] riverFlux) {
            this.erodedMap = erodedMap;
            this.riverFlux = riverFlux;
        }
    }

    /**
     * Run hydraulic erosion on a 2D heightmap.
     *
     * <p>The heightmap is normalized to [0, 1] before erosion so that the physical
     * parameters (inertia, gravity, etc.) work regardless of the actual elevation range
     * (which can span thousands of meters on coarse tiles). The result is denormalized
     * back to the original range afterwards.
     *
     * @param heightmap  2D float array [H][W] of elevation in meters
     * @param seed       world seed for deterministic droplet placement
     * @return eroded heightmap and river flux map
     */
    public ErosionResult erode(float[][] heightmap, long seed) {
        int height = heightmap.length;
        int width = heightmap[0].length;

        // Find min/max for normalization
        float hMin = Float.MAX_VALUE, hMax = -Float.MAX_VALUE;
        for (int r = 0; r < height; r++)
            for (int c = 0; c < width; c++) {
                float v = heightmap[r][c];
                if (Float.isFinite(v)) {
                    if (v < hMin) hMin = v;
                    if (v > hMax) hMax = v;
                }
            }

        // Degenerate case: flat terrain, nothing to erode
        if (!Float.isFinite(hMin) || !Float.isFinite(hMax) || hMax - hMin < 1e-6f) {
            float[][] copy = new float[height][width];
            for (int r = 0; r < height; r++)
                System.arraycopy(heightmap[r], 0, copy[r], 0, width);
            return new ErosionResult(copy, new float[height][width]);
        }

        float hRange = hMax - hMin;

        // Normalize to [0, 1]
        float[][] normMap = new float[height][width];
        for (int r = 0; r < height; r++)
            for (int c = 0; c < width; c++)
                normMap[r][c] = (heightmap[r][c] - hMin) / hRange;

        // Run erosion on normalized data
        float[][] erodedNorm = new float[height][width];
        float[][] normFlux = new float[height][width];
        for (int r = 0; r < height; r++)
            System.arraycopy(normMap[r], 0, erodedNorm[r], 0, width);

        Random rng = new Random(seed ^ 0x5DEECE66DL);

        for (int d = 0; d < numDroplets; d++) {
            float px = rng.nextFloat() * (width - 1);
            float py = rng.nextFloat() * (height - 1);
            float dirX = 0f, dirY = 0f;
            float vel = 1f, water = 1f, sediment = 0f;

            for (int step = 0; step < maxLifetime; step++) {
                int ix = (int) px;
                int iy = (int) py;
                if (ix < 0 || ix >= width - 1 || iy < 0 || iy >= height - 1) break;

                float tx = px - ix, ty = py - iy;
                float h00 = erodedNorm[iy][ix];
                float h10 = erodedNorm[iy][Math.min(ix + 1, width - 1)];
                float h01 = erodedNorm[Math.min(iy + 1, height - 1)][ix];
                float h11 = erodedNorm[Math.min(iy + 1, height - 1)][Math.min(ix + 1, width - 1)];

                float gradX = (h10 - h00) * (1 - ty) + (h11 - h01) * ty;
                float gradY = (h01 - h00) * (1 - tx) + (h11 - h10) * tx;

                dirX = dirX * inertia - gradX * (1 - inertia);
                dirY = dirY * inertia - gradY * (1 - inertia);
                float mag = (float) Math.hypot(dirX, dirY);
                if (mag > 1e-6f) { dirX /= mag; dirY /= mag; }
                else { dirX = rng.nextFloat() * 2 - 1; dirY = rng.nextFloat() * 2 - 1; }

                float newPx = px + dirX, newPy = py + dirY;
                if (newPx < 0 || newPx >= width - 1 || newPy < 0 || newPy >= height - 1) break;

                int newIx = (int) newPx, newIy = (int) newPy;
                float newH00 = erodedNorm[newIy][newIx];
                float heightDiff = newH00 - h00;

                float sedimentCapacity = Math.max(-heightDiff, 0.0001f) * vel * water * capacityCoef;

                if (sediment > sedimentCapacity || heightDiff > 0) {
                    float depositAmt = heightDiff >= 0
                            ? Math.min(sediment, heightDiff)
                            : (sediment - sedimentCapacity) * depositionRate;
                    erodedNorm[iy][ix]         += depositAmt * (1 - tx) * (1 - ty);
                    erodedNorm[iy][newIx]      += depositAmt * tx * (1 - ty);
                    erodedNorm[newIy][ix]      += depositAmt * (1 - tx) * ty;
                    erodedNorm[newIy][newIx]   += depositAmt * tx * ty;
                    sediment -= depositAmt;
                } else {
                    float erosionAmt = Math.min((sedimentCapacity - sediment) * erosionRate, -heightDiff);
                    erodedNorm[iy][ix]         -= erosionAmt * (1 - tx) * (1 - ty);
                    erodedNorm[iy][newIx]      -= erosionAmt * tx * (1 - ty);
                    erodedNorm[newIy][ix]      -= erosionAmt * (1 - tx) * ty;
                    erodedNorm[newIy][newIx]   -= erosionAmt * tx * ty;
                    sediment += erosionAmt;
                }

                normFlux[iy][ix]         += water * (1 - tx) * (1 - ty);
                normFlux[iy][newIx]      += water * tx * (1 - ty);
                normFlux[newIy][ix]      += water * (1 - tx) * ty;
                normFlux[newIy][newIx]   += water * tx * ty;
                vel = (float) Math.sqrt(Math.max(0f, vel * vel - heightDiff * gravity));
                water *= (1f - evaporationRate);
                px = newPx;
                py = newPy;
            }
        }

        // Denormalize back to original range
        float[][] erodedMap = new float[height][width];
        for (int r = 0; r < height; r++)
            for (int c = 0; c < width; c++) {
                float v = erodedNorm[r][c] * hRange + hMin;
                erodedMap[r][c] = Float.isFinite(v) ? v : heightmap[r][c];
            }

        // Scale flux back to meters
        float[][] riverFlux = new float[height][width];
        for (int r = 0; r < height; r++)
            for (int c = 0; c < width; c++)
                riverFlux[r][c] = normFlux[r][c] * hRange;

        LOG.info("Hydraulic erosion: {} droplets on {}x{} grid, elev range [{}, {}] m",
                numDroplets, width, height, hMin, hMax);

        return new ErosionResult(erodedMap, riverFlux);
    }

    /**
     * Builder for configuring erosion parameters.
     */
    public static final class Builder {
        private float inertia = 0.02f;
        private float capacityCoef = 4.0f;
        private float depositionRate = 0.1f;
        private float erosionRate = 0.5f;
        private float evaporationRate = 0.02f;
        private float gravity = 8.0f;
        private int numDroplets = 200000;
        private int maxLifetime = 64;

        /** Droplet inertia (0-1). Higher = more momentum, less affected by slope. Default: 0.02 */
        public Builder inertia(float v) { this.inertia = v; return this; }
        /** Sediment capacity coefficient. Higher = rivers carry more sediment. Default: 4.0 */
        public Builder capacityCoef(float v) { this.capacityCoef = v; return this; }
        /** Deposition rate when carrying excess sediment. Default: 0.1 */
        public Builder depositionRate(float v) { this.depositionRate = v; return this; }
        /** Erosion rate when below capacity. Default: 0.5 */
        public Builder erosionRate(float v) { this.erosionRate = v; return this; }
        /** Water evaporation per step (0-1). Default: 0.02 */
        public Builder evaporationRate(float v) { this.evaporationRate = v; return this; }
        /** Gravity acceleration. Higher = faster downhill flow. Default: 8.0 */
        public Builder gravity(float v) { this.gravity = v; return this; }
        /** Number of rain droplets to simulate. Default: 200000 */
        public Builder numDroplets(int v) { this.numDroplets = v; return this; }
        /** Max steps per droplet lifetime. Default: 64 */
        public Builder maxLifetime(int v) { this.maxLifetime = v; return this; }

        public HydraulicErosionSimulator build() {
            return new HydraulicErosionSimulator(this);
        }
    }
}
