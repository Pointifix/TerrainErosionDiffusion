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

    /** Export water frequency map. */
    public static void exportFrequency(int sizeX, int sizeY, File file) throws Exception {
        BufferedImage img = new BufferedImage(sizeX, sizeY, BufferedImage.TYPE_INT_RGB);
        float fMax = 0;
        for (int i = 0; i < sizeX * sizeY; i++)
            if (WaterParticle.frequency[i] > fMax) fMax = WaterParticle.frequency[i];
        if (fMax < 1e-10f) fMax = 1;

        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                int v = (int) (255.0 * WaterParticle.frequency[y * sizeX + x] / fMax);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        ImageIO.write(img, "png", file);
        System.out.println("Exported frequency: " + file.getAbsolutePath());
    }

    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
