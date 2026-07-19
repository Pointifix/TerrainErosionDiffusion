package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

/**
 * Noise layer for terrain generation. Maps to SurfLayer in surface.h.
 * Uses value noise with fBm (no external noise library needed).
 */
public class NoiseLayer {

    public int soilType;
    public double min = 0.0;
    public double bias = 0.0;
    public double scale = 1.0;
    public double octaves = 1.0;
    public double lacunarity = 1.0;
    public double gain = 0.0;
    public double frequency = 1.0;

    public NoiseLayer(int soilType) {
        this.soilType = soilType;
    }

    /** Sample noise at normalized coordinates (x, y in [0,1], z = seed offset). */
    public double get(double x, double y, double z) {
        double val = bias + scale * fbm(x * frequency, y * frequency, z, (int) octaves, lacunarity, gain);
        return Math.max(val, min);
    }

    /** Fractional Brownian Motion using value noise. */
    private static double fbm(double x, double y, double z, int oct, double lac, double gain) {
        double sum = 0, amp = 1, maxAmp = 0;
        for (int o = 0; o < oct; o++) {
            sum += amp * valueNoise(x, y, z);
            maxAmp += amp;
            x *= lac;
            y *= lac;
            amp *= gain;
        }
        return sum / maxAmp;
    }

    /** Value noise with smooth interpolation. */
    private static double valueNoise(double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);

        double v000 = hash(ix, iy, iz);
        double v100 = hash(ix + 1, iy, iz);
        double v010 = hash(ix, iy + 1, iz);
        double v110 = hash(ix + 1, iy + 1, iz);
        double v001 = hash(ix, iy, iz + 1);
        double v101 = hash(ix + 1, iy, iz + 1);
        double v011 = hash(ix, iy + 1, iz + 1);
        double v111 = hash(ix + 1, iy + 1, iz + 1);

        return lerp(fz,
            lerp(fy, lerp(fx, v000, v100), lerp(fx, v010, v110)),
            lerp(fy, lerp(fx, v001, v101), lerp(fx, v011, v111))
        );
    }

    private static double hash(int x, int y, int z) {
        long h = 0x5DEECE66DL;
        h ^= (long) x * 0x5DEECE66DL;
        h ^= (long) y * 0xBL;
        h ^= (long) z * 0x5DEECE66DL;
        h = (h * 0x5DEECE66DL + 0xBL) & 0x7FFFFFFFFFFFFFFFL;
        return (double) (h % 10000) / 5000.0 - 1.0;
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
