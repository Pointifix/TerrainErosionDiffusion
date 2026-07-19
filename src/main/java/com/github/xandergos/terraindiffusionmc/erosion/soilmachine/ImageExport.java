package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Image export utilities. Maps to exportcolor/exportheight in io.h.
 */
public class ImageExport {

    /** Export heightmap as grayscale PNG. */
    public static void exportHeightmap(Layermap map, int scale, File file) throws Exception {
        int w = map.dimX, h = map.dimY;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double v = map.height(x, y);
                if (v < hMin) hMin = v;
                if (v > hMax) hMax = v;
            }
        double range = hMax - hMin;
        if (range < 1e-10) range = 1;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (int) (255.0 * (map.height(x, y) - hMin) / range);
                v = Math.max(0, Math.min(255, v));
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("Exported heightmap: " + file.getAbsolutePath());
    }

    /** Export color map based on soil types. */
    public static void exportColor(Layermap map, SoilType[] soils, File file) throws Exception {
        int w = map.dimX, h = map.dimY;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Sec top = map.top(x, y);
                double r, g, b;
                if (top == null) {
                    r = g = b = 0;
                } else {
                    SoilType soil = soils[top.type];
                    r = soil.r; g = soil.g; b = soil.b;

                    // Water overlay: blend with blue
                    if (top.type == 0 && top.size > 0.001) {
                        double alpha = Math.min(1.0, top.size * 10);
                        r = lerp(alpha, r, 0.0);
                        g = lerp(alpha, g, 0.3);
                        b = lerp(alpha, b, 0.6);
                    }
                }
                int ri = clamp((int) (r * 255));
                int gi = clamp((int) (g * 255));
                int bi = clamp((int) (b * 255));
                img.setRGB(x, y, (ri << 16) | (gi << 8) | bi);
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("Exported color map: " + file.getAbsolutePath());
    }

    /** Export normal map. */
    public static void exportNormals(Layermap map, int scale, File file) throws Exception {
        int w = map.dimX, h = map.dimY;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double[] n = map.normal(x, y, scale);
                int r = clamp((int) ((n[0] * 0.5 + 0.5) * 255));
                int g = clamp((int) ((n[1] * 0.5 + 0.5) * 255));
                int b = clamp((int) ((n[2] * 0.5 + 0.5) * 255));
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("Exported normals: " + file.getAbsolutePath());
    }

    /**
     * Export natural rendering: topographic terrain classification based on height + slope.
     *
     * Below sea level  → water (blue, depth-darkened)
     * Above sea level  → classified by slope + relative height:
     *   flat + low     → grass (green)
     *   flat + mid     → shrubland (olive)
     *   flat + high    → alpine meadow (light green)
     *   flat + very high → snow (white)
     *   moderate slope → gravel/scree (tan/brown)
     *   steep slope    → rock (gray)
     *   beach zone     → sand (tan, near waterline)
     */
    public static void exportNatural(Layermap map, SoilType[] soils, float[] frequency, int scale, File file) throws Exception {
        int w = map.dimX, h = map.dimY;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        // Collect all heights to find sea level (5th percentile)
        double[] allHeights = new double[w * h];
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double v = map.height(x, y);
                allHeights[y * w + x] = v;
                if (v < hMin) hMin = v;
                if (v > hMax) hMax = v;
            }
        java.util.Arrays.sort(allHeights);
        double seaLevel = allHeights[(int) (w * h * 0.05)];
        double landMin = Math.max(seaLevel, hMin);
        double landRange = hMax - landMin;
        if (landRange < 1e-10) landRange = 1;
        double waterRange = Math.max(1e-10, seaLevel - hMin);

        float fMax = 0;
        for (int i = 0; i < w * h; i++)
            if (frequency[i] > fMax) fMax = frequency[i];
        if (fMax < 1e-10f) fMax = 1;

        // Light direction: from upper-left toward the surface
        double lightX = 0.45, lightY = 0.65, lightZ = -0.60;
        double lMag = Math.sqrt(lightX * lightX + lightY * lightY + lightZ * lightZ);
        lightX /= lMag; lightY /= lMag; lightZ /= lMag;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double hCenter = map.height(x, y);
                Sec top = map.top(x, y);

                // Use map.normal() for correct surface normal
                double[] norm = map.normal(x, y, scale);
                // norm[0]=x-slope, norm[1]=vertical, norm[2]=z-slope
                double slope = Math.sqrt(norm[0] * norm[0] + norm[2] * norm[2]); // 0=flat, ~1=vertical

                // ── 1. Determine terrain color from height + slope ──
                double r, g, b;

                if (hCenter < seaLevel) {
                    // Underwater
                    double depth = Math.min(1.0, (seaLevel - hCenter) / waterRange);
                    double t = smoothstep(depth);
                    r = lerp(t, 0.15, 0.04);
                    g = lerp(t, 0.50, 0.12);
                    b = lerp(t, 0.72, 0.35);
                } else {
                    double hAbove = Math.min(1.0, (hCenter - landMin) / landRange);
                    double s = smoothstep(slope);

                    // Beach: just above water, flat
                    if (hAbove < 0.04 && slope < 0.25) {
                        double bt = smoothstep(hAbove / 0.04);
                        r = lerp(bt, 0.76, 0.22);
                        g = lerp(bt, 0.70, 0.52);
                        b = lerp(bt, 0.50, 0.12);
                    }
                    // Steep → rock
                    else if (s > 0.5) {
                        double rockBlend = smoothstep((s - 0.5) / 0.5);
                        double[] grass = grassColor(hAbove);
                        r = lerp(rockBlend, grass[0], 0.44);
                        g = lerp(rockBlend, grass[1], 0.42);
                        b = lerp(rockBlend, grass[2], 0.40);
                    }
                    // Moderate slope → gravel/scree
                    else if (s > 0.25) {
                        double gravelBlend = smoothstep((s - 0.25) / 0.25);
                        double[] grass = grassColor(hAbove);
                        r = lerp(gravelBlend, grass[0], 0.55);
                        g = lerp(gravelBlend, grass[1], 0.42);
                        b = lerp(gravelBlend, grass[2], 0.28);
                    }
                    // Flat → vegetation (height-dependent)
                    else {
                        double[] gc = grassColor(hAbove);
                        r = gc[0]; g = gc[1]; b = gc[2];
                    }
                }

                // ── 2. Blend with soil type color (subtle accent) ──
                if (top != null && top.type != 0) {
                    SoilType soil = soils[top.type];
                    double soilMix = 0.2;
                    r = r * (1 - soilMix) + soil.r * soilMix;
                    g = g * (1 - soilMix) + soil.g * soilMix;
                    b = b * (1 - soilMix) + soil.b * soilMix;
                }

                // ── 3. Directional shading ──
                double diffuse = Math.max(0, norm[0] * lightX + norm[1] * lightY + norm[2] * lightZ);
                double shading = 0.25 + 0.75 * diffuse;

                // Slope darkening: steep faces darker
                double slopeDarken = 1.0 - 0.35 * smoothstep(slope);

                // ── 4. Water flow overlay ──
                double freq = frequency[y * w + x] / fMax;
                double waterAlpha = Math.pow(freq, 0.35) * 0.5;
                if (top != null && top.type == 0 && top.size > 0.001) {
                    double wAlpha = Math.min(1.0, top.size * 10);
                    waterAlpha = Math.max(waterAlpha, wAlpha * 0.7);
                }
                if (waterAlpha > 0.01 && hCenter >= seaLevel) {
                    r = lerp(waterAlpha, r, 0.15);
                    g = lerp(waterAlpha, g, 0.40);
                    b = lerp(waterAlpha, b, 0.65);
                }

                // ── 5. Apply shading ──
                r *= shading * slopeDarken;
                g *= shading * slopeDarken;
                b *= shading * slopeDarken;

                // ── 6. Vignette ──
                double cx = (x - w / 2.0) / (w / 2.0);
                double cy = (y - h / 2.0) / (h / 2.0);
                double vignette = 1.0 - 0.10 * (cx * cx + cy * cy);
                r *= vignette;
                g *= vignette;
                b *= vignette;

                int ri = clamp((int) (r * 255));
                int gi = clamp((int) (g * 255));
                int bi = clamp((int) (b * 255));
                img.setRGB(x, y, (ri << 16) | (gi << 8) | bi);
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("Exported natural render: " + file.getAbsolutePath());
    }

    /** Vegetation color varies with altitude above sea level. */
    private static double[] grassColor(double hAbove) {
        // 0.0=sea level (dark green), 0.5=mid (olive), 0.8=alpine (light), 1.0=snow (white)
        if (hAbove > 0.85) {
            double t = smoothstep((hAbove - 0.85) / 0.15);
            return new double[]{
                lerp(t, 0.35, 0.90),
                lerp(t, 0.45, 0.92),
                lerp(t, 0.25, 0.95)
            };
        } else if (hAbove > 0.55) {
            double t = (hAbove - 0.55) / 0.30;
            return new double[]{
                lerp(t, 0.30, 0.35),
                lerp(t, 0.48, 0.45),
                lerp(t, 0.18, 0.25)
            };
        } else {
            double t = hAbove / 0.55;
            return new double[]{
                lerp(t, 0.15, 0.30),
                lerp(t, 0.42, 0.48),
                lerp(t, 0.08, 0.18)
            };
        }
    }

    private static double smoothstep(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Export water frequency map. */
    public static void exportFrequency(int sizeX, int sizeY, File file) throws Exception {
        exportFrequency(WaterParticle.frequency, sizeX, sizeY, 0, 0, sizeX, sizeY, file);
    }

    /** Export water frequency map for a sub-region of the frequency array. */
    public static void exportFrequency(float[] freq, int fullSizeX, int fullSizeY,
                                        int offX, int offY, int outW, int outH, File file) throws Exception {
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        float fMax = 0;
        for (int y = offY; y < offY + outH; y++)
            for (int x = offX; x < offX + outW; x++)
                if (freq[y * fullSizeX + x] > fMax) fMax = freq[y * fullSizeX + x];
        if (fMax < 1e-10f) fMax = 1;

        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int v = (int) (255.0 * freq[(offY + y) * fullSizeX + (offX + x)] / fMax);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("Exported frequency: " + file.getAbsolutePath());
    }

    // ── Region export methods (for chunk generation) ──

    /** Export a sub-region of the heightmap. */
    public static void exportHeightmapRegion(Layermap map, int scale, int offX, int offY, int outW, int outH, File file) throws Exception {
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);

        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (int y = offY; y < offY + outH; y++)
            for (int x = offX; x < offX + outW; x++) {
                double v = map.height(x, y);
                if (v < hMin) hMin = v;
                if (v > hMax) hMax = v;
            }
        double range = hMax - hMin;
        if (range < 1e-10) range = 1;

        for (int y = 0; y < outH; y++)
            for (int x = 0; x < outW; x++) {
                int v = (int) (255.0 * (map.height(offX + x, offY + y) - hMin) / range);
                v = Math.max(0, Math.min(255, v));
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        ImageIO.write(img, "png", file);
        System.out.println("Exported heightmap: " + file.getAbsolutePath());
    }

    /** Export a sub-region of the color map. */
    public static void exportColorRegion(Layermap map, SoilType[] soils, int offX, int offY, int outW, int outH, File file) throws Exception {
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                Sec top = map.top(offX + x, offY + y);
                double r, g, b;
                if (top == null) {
                    r = g = b = 0;
                } else {
                    SoilType soil = soils[top.type];
                    r = soil.r; g = soil.g; b = soil.b;
                    if (top.type == 0 && top.size > 0.001) {
                        double alpha = Math.min(1.0, top.size * 10);
                        r = lerp(alpha, r, 0.0);
                        g = lerp(alpha, g, 0.3);
                        b = lerp(alpha, b, 0.6);
                    }
                }
                int ri = clamp((int) (r * 255));
                int gi = clamp((int) (g * 255));
                int bi = clamp((int) (b * 255));
                img.setRGB(x, y, (ri << 16) | (gi << 8) | bi);
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("Exported color map: " + file.getAbsolutePath());
    }

    /** Export a sub-region of the normals map. */
    public static void exportNormalsRegion(Layermap map, int scale, int offX, int offY, int outW, int outH, File file) throws Exception {
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                double[] n = map.normal(offX + x, offY + y, scale);
                int r = clamp((int) ((n[0] * 0.5 + 0.5) * 255));
                int g = clamp((int) ((n[1] * 0.5 + 0.5) * 255));
                int b = clamp((int) ((n[2] * 0.5 + 0.5) * 255));
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("Exported normals: " + file.getAbsolutePath());
    }

    /** Export a sub-region of the natural rendering. */
    public static void exportNaturalRegion(Layermap map, SoilType[] soils, float[] frequency,
                                            int fullSizeX, int scale,
                                            int offX, int offY, int outW, int outH, File file) throws Exception {
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);

        // Collect heights in the region for sea level calculation
        double[] allHeights = new double[outW * outH];
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (int y = 0; y < outH; y++)
            for (int x = 0; x < outW; x++) {
                double v = map.height(offX + x, offY + y);
                allHeights[y * outW + x] = v;
                if (v < hMin) hMin = v;
                if (v > hMax) hMax = v;
            }
        java.util.Arrays.sort(allHeights);
        double seaLevel = allHeights[(int) (outW * outH * 0.05)];
        double landMin = Math.max(seaLevel, hMin);
        double landRange = hMax - landMin;
        if (landRange < 1e-10) landRange = 1;
        double waterRange = Math.max(1e-10, seaLevel - hMin);

        float fMax = 0;
        for (int y = offY; y < offY + outH; y++)
            for (int x = offX; x < offX + outW; x++)
                if (frequency[y * fullSizeX + x] > fMax) fMax = frequency[y * fullSizeX + x];
        if (fMax < 1e-10f) fMax = 1;

        double lightX = 0.45, lightY = 0.65, lightZ = -0.60;
        double lMag = Math.sqrt(lightX * lightX + lightY * lightY + lightZ * lightZ);
        lightX /= lMag; lightY /= lMag; lightZ /= lMag;

        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int mx = offX + x, my = offY + y;
                double hCenter = map.height(mx, my);
                Sec top = map.top(mx, my);
                double[] norm = map.normal(mx, my, scale);
                double slope = Math.sqrt(norm[0] * norm[0] + norm[2] * norm[2]);

                double r, g, b;
                if (hCenter < seaLevel) {
                    double depth = Math.min(1.0, (seaLevel - hCenter) / waterRange);
                    double t = smoothstep(depth);
                    r = lerp(t, 0.15, 0.04);
                    g = lerp(t, 0.50, 0.12);
                    b = lerp(t, 0.72, 0.35);
                } else {
                    double hAbove = Math.min(1.0, (hCenter - landMin) / landRange);
                    double s = smoothstep(slope);
                    if (hAbove < 0.04 && slope < 0.25) {
                        double bt = smoothstep(hAbove / 0.04);
                        r = lerp(bt, 0.76, 0.22);
                        g = lerp(bt, 0.70, 0.52);
                        b = lerp(bt, 0.50, 0.12);
                    } else if (s > 0.5) {
                        double rockBlend = smoothstep((s - 0.5) / 0.5);
                        double[] grass = grassColor(hAbove);
                        r = lerp(rockBlend, grass[0], 0.44);
                        g = lerp(rockBlend, grass[1], 0.42);
                        b = lerp(rockBlend, grass[2], 0.40);
                    } else if (s > 0.25) {
                        double gravelBlend = smoothstep((s - 0.25) / 0.25);
                        double[] grass = grassColor(hAbove);
                        r = lerp(gravelBlend, grass[0], 0.55);
                        g = lerp(gravelBlend, grass[1], 0.42);
                        b = lerp(gravelBlend, grass[2], 0.28);
                    } else {
                        double[] gc = grassColor(hAbove);
                        r = gc[0]; g = gc[1]; b = gc[2];
                    }
                }

                if (top != null && top.type != 0) {
                    SoilType soil = soils[top.type];
                    double soilMix = 0.2;
                    r = r * (1 - soilMix) + soil.r * soilMix;
                    g = g * (1 - soilMix) + soil.g * soilMix;
                    b = b * (1 - soilMix) + soil.b * soilMix;
                }

                double diffuse = Math.max(0, norm[0] * lightX + norm[1] * lightY + norm[2] * lightZ);
                double shading = 0.25 + 0.75 * diffuse;
                double slopeDarken = 1.0 - 0.35 * smoothstep(slope);

                double freq = frequency[my * fullSizeX + mx] / fMax;
                double waterAlpha = Math.pow(freq, 0.35) * 0.5;
                if (top != null && top.type == 0 && top.size > 0.001) {
                    double wAlpha = Math.min(1.0, top.size * 10);
                    waterAlpha = Math.max(waterAlpha, wAlpha * 0.7);
                }
                if (waterAlpha > 0.01 && hCenter >= seaLevel) {
                    r = lerp(waterAlpha, r, 0.15);
                    g = lerp(waterAlpha, g, 0.40);
                    b = lerp(waterAlpha, b, 0.65);
                }

                r *= shading * slopeDarken;
                g *= shading * slopeDarken;
                b *= shading * slopeDarken;

                double cx = (x - outW / 2.0) / (outW / 2.0);
                double cy = (y - outH / 2.0) / (outH / 2.0);
                double vignette = 1.0 - 0.10 * (cx * cx + cy * cy);
                r *= vignette; g *= vignette; b *= vignette;

                int ri = clamp((int) (r * 255));
                int gi = clamp((int) (g * 255));
                int bi = clamp((int) (b * 255));
                img.setRGB(x, y, (ri << 16) | (gi << 8) | bi);
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("Exported natural render: " + file.getAbsolutePath());
    }

    // ── In-memory render methods (for mosaic stitching) ──

    /** Render a region of the natural map to a BufferedImage (no file I/O). */
    public static BufferedImage renderNaturalRegion(Layermap map, SoilType[] soils, float[] frequency,
                                                    int fullSizeX, int scale,
                                                    int offX, int offY, int outW, int outH) {
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);

        double[] allHeights = new double[outW * outH];
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (int y = 0; y < outH; y++)
            for (int x = 0; x < outW; x++) {
                double v = map.height(offX + x, offY + y);
                allHeights[y * outW + x] = v;
                if (v < hMin) hMin = v;
                if (v > hMax) hMax = v;
            }
        java.util.Arrays.sort(allHeights);
        double seaLevel = allHeights[(int) (outW * outH * 0.05)];
        double landMin = Math.max(seaLevel, hMin);
        double landRange = hMax - landMin;
        if (landRange < 1e-10) landRange = 1;
        double waterRange = Math.max(1e-10, seaLevel - hMin);

        float fMax = 0;
        for (int y = offY; y < offY + outH; y++)
            for (int x = offX; x < offX + outW; x++)
                if (frequency[y * fullSizeX + x] > fMax) fMax = frequency[y * fullSizeX + x];
        if (fMax < 1e-10f) fMax = 1;

        double lightX = 0.45, lightY = 0.65, lightZ = -0.60;
        double lMag = Math.sqrt(lightX * lightX + lightY * lightY + lightZ * lightZ);
        lightX /= lMag; lightY /= lMag; lightZ /= lMag;

        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int mx = offX + x, my = offY + y;
                double hCenter = map.height(mx, my);
                Sec top = map.top(mx, my);
                double[] norm = map.normal(mx, my, scale);
                double slope = Math.sqrt(norm[0] * norm[0] + norm[2] * norm[2]);

                double r, g, b;
                if (hCenter < seaLevel) {
                    double depth = Math.min(1.0, (seaLevel - hCenter) / waterRange);
                    double t = smoothstep(depth);
                    r = lerp(t, 0.15, 0.04); g = lerp(t, 0.50, 0.12); b = lerp(t, 0.72, 0.35);
                } else {
                    double hAbove = Math.min(1.0, (hCenter - landMin) / landRange);
                    double s = smoothstep(slope);
                    if (hAbove < 0.04 && slope < 0.25) {
                        double bt = smoothstep(hAbove / 0.04);
                        r = lerp(bt, 0.76, 0.22); g = lerp(bt, 0.70, 0.52); b = lerp(bt, 0.50, 0.12);
                    } else if (s > 0.5) {
                        double rockBlend = smoothstep((s - 0.5) / 0.5);
                        double[] grass = grassColor(hAbove);
                        r = lerp(rockBlend, grass[0], 0.44); g = lerp(rockBlend, grass[1], 0.42); b = lerp(rockBlend, grass[2], 0.40);
                    } else if (s > 0.25) {
                        double gravelBlend = smoothstep((s - 0.25) / 0.25);
                        double[] grass = grassColor(hAbove);
                        r = lerp(gravelBlend, grass[0], 0.55); g = lerp(gravelBlend, grass[1], 0.42); b = lerp(gravelBlend, grass[2], 0.28);
                    } else {
                        double[] gc = grassColor(hAbove); r = gc[0]; g = gc[1]; b = gc[2];
                    }
                }
                if (top != null && top.type != 0) {
                    SoilType soil = soils[top.type];
                    double sm = 0.2; r = r*(1-sm)+soil.r*sm; g = g*(1-sm)+soil.g*sm; b = b*(1-sm)+soil.b*sm;
                }
                double diffuse = Math.max(0, norm[0]*lightX + norm[1]*lightY + norm[2]*lightZ);
                double shading = 0.25 + 0.75 * diffuse;
                double slopeDarken = 1.0 - 0.35 * smoothstep(slope);
                double freq = frequency[my * fullSizeX + mx] / fMax;
                double waterAlpha = Math.pow(freq, 0.35) * 0.5;
                if (top != null && top.type == 0 && top.size > 0.001) {
                    waterAlpha = Math.max(waterAlpha, Math.min(1.0, top.size * 10) * 0.7);
                }
                if (waterAlpha > 0.01 && hCenter >= seaLevel) {
                    r = lerp(waterAlpha, r, 0.15); g = lerp(waterAlpha, g, 0.40); b = lerp(waterAlpha, b, 0.65);
                }
                r *= shading * slopeDarken; g *= shading * slopeDarken; b *= shading * slopeDarken;
                double cx2 = (x - outW/2.0)/(outW/2.0), cy2 = (y - outH/2.0)/(outH/2.0);
                double vignette = 1.0 - 0.10*(cx2*cx2 + cy2*cy2);
                r *= vignette; g *= vignette; b *= vignette;
                img.setRGB(x, y, (clamp((int)(r*255)) << 16) | (clamp((int)(g*255)) << 8) | clamp((int)(b*255)));
            }
        }
        return img;
    }

    /** Render a region of the heightmap to a BufferedImage (no file I/O). */
    public static BufferedImage renderHeightmapRegion(Layermap map, int scale, int offX, int offY, int outW, int outH) {
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (int y = offY; y < offY + outH; y++)
            for (int x = offX; x < offX + outW; x++) {
                double v = map.height(x, y);
                if (v < hMin) hMin = v;
                if (v > hMax) hMax = v;
            }
        double range = hMax - hMin; if (range < 1e-10) range = 1;
        for (int y = 0; y < outH; y++)
            for (int x = 0; x < outW; x++) {
                int v = (int)(255.0 * (map.height(offX+x, offY+y) - hMin) / range);
                v = Math.max(0, Math.min(255, v));
                img.setRGB(x, y, (v<<16)|(v<<8)|v);
            }
        return img;
    }

    /** Render a region of the colormap to a BufferedImage (no file I/O). */
    public static BufferedImage renderColorRegion(Layermap map, SoilType[] soils, int offX, int offY, int outW, int outH) {
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                Sec top = map.top(offX+x, offY+y);
                double r, g, b;
                if (top == null) { r = g = b = 0; }
                else {
                    SoilType soil = soils[top.type]; r = soil.r; g = soil.g; b = soil.b;
                    if (top.type == 0 && top.size > 0.001) {
                        double a = Math.min(1.0, top.size*10);
                        r = lerp(a,r,0.0); g = lerp(a,g,0.3); b = lerp(a,b,0.6);
                    }
                }
                img.setRGB(x, y, (clamp((int)(r*255))<<16)|(clamp((int)(g*255))<<8)|clamp((int)(b*255)));
            }
        }
        return img;
    }

    /** Render a region of the normals to a BufferedImage (no file I/O). */
    public static BufferedImage renderNormalsRegion(Layermap map, int scale, int offX, int offY, int outW, int outH) {
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                double[] n = map.normal(offX+x, offY+y, scale);
                img.setRGB(x, y,
                    (clamp((int)((n[0]*0.5+0.5)*255))<<16)|
                    (clamp((int)((n[1]*0.5+0.5)*255))<<8)|
                     clamp((int)((n[2]*0.5+0.5)*255)));
            }
        }
        return img;
    }

    /** Render a region of the frequency map to a BufferedImage (no file I/O). */
    public static BufferedImage renderFrequencyRegion(float[] freq, int fullSizeX, int offX, int offY, int outW, int outH) {
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        float fMax = 0;
        for (int y = offY; y < offY+outH; y++)
            for (int x = offX; x < offX+outW; x++)
                if (freq[y*fullSizeX+x] > fMax) fMax = freq[y*fullSizeX+x];
        if (fMax < 1e-10f) fMax = 1;
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int v = (int)(255.0 * freq[(offY+y)*fullSizeX+(offX+x)] / fMax);
                img.setRGB(x, y, (v<<16)|(v<<8)|v);
            }
        }
        return img;
    }

    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
