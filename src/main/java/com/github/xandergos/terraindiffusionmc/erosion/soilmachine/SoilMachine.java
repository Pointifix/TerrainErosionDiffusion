package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

import java.io.File;
import java.util.Random;

/**
 * Java port of weigert/SoilMachine — hydraulic erosion with multi-layer terrain.
 *
 * Usage:
 *   java SoilMachine [options]
 *
 * Options:
 *   -SEED [#]     Run using seed. No seed = random
 *   -soil [file]  Specify relative path to .soil file
 *   -oc [file]    Export color map to .png at path (on exit)
 *   -oh [file]    Export height map to .png at path (on exit)
 *   -on [file]    Export normal map to .png at path (on exit)
 *   -of [file]    Export water frequency map to .png at path (on exit)
 *   -iter [#]     Number of erosion iterations (default: 500)
 */
public class SoilMachine {

    public static void main(String[] args) {
        System.out.println("SoilMachine Java Port");

        // ── Parse Arguments ──
        Integer seed = null;
        String soilFile = "soil/default.soil";
        String ocFile = null;  // output color
        String ohFile = null;  // output height
        String onFile = null;  // output normals
        String ofFile = null;  // output frequency
        int iterations = 500;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-SEED")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    seed = Integer.parseInt(args[++i]);
                }
            } else if (arg.equals("-soil")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    soilFile = args[++i];
                }
            } else if (arg.equals("-oc")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    ocFile = args[++i];
                }
            } else if (arg.equals("-oh")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    ohFile = args[++i];
                }
            } else if (arg.equals("-on")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    onFile = args[++i];
                }
            } else if (arg.equals("-of")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    ofFile = args[++i];
                }
            } else if (arg.equals("-iter")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    iterations = Integer.parseInt(args[++i]);
                }
            } else if (arg.equals("-h") || arg.equals("--help")) {
                printUsage();
                return;
            }
        }

        if (seed == null) seed = new Random().nextInt(100000000);
        System.out.println("SEED: " + seed);

        // ── Load Soil Config ──
        SoilParser.Config config;
        try {
            config = SoilParser.load(soilFile);
        } catch (Exception e) {
            System.err.println("Error loading soil file: " + e.getMessage());
            return;
        }

        SoilType[] soils = config.soils.toArray(new SoilType[0]);
        int sizeX = config.sizeX, sizeY = config.sizeY;
        int scale = config.scale;
        int nWater = config.nWater;

        System.out.printf("Grid: %dx%d, Scale: %d, Water particles/frame: %d%n", sizeX, sizeY, scale, nWater);
        System.out.printf("Soil types: %d, Noise layers: %d%n", soils.length, config.layers.size());

        // ── Initialize ──
        Layermap map = new Layermap(sizeX, sizeY, 10_000_000);
        map.initialize(seed, config.layers);

        WaterParticle.initFrequency(sizeX, sizeY);

        // ── Run Erosion ──
        Random rng = new Random(seed);
        System.out.println("Running hydraulic erosion...");

        long t0 = System.currentTimeMillis();
        for (int iter = 0; iter < iterations; iter++) {
            for (int i = 0; i < nWater; i++) {
                WaterParticle particle = new WaterParticle(sizeX, sizeY, rng);
                int spill = WaterParticle.SPILL;

                while (true) {
                    while (particle.move(map, soils, scale) && particle.interact(map, soils, scale)) {}
                    if (!particle.flood(map, soils, scale, spill)) break;
                    spill--;
                    if (spill <= 0) break;
                }
            }

            WaterParticle.globalSeep(map, soils, scale);
            WaterParticle.mapFrequency(sizeX, sizeY);
            WaterParticle.resetFrequency(sizeX, sizeY);

            if ((iter + 1) % 100 == 0 || iter == 0) {
                System.out.printf("  iteration %d/%d%n", iter + 1, iterations);
            }
        }
        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("Done in %.1f seconds%n", elapsed / 1000.0);

        // ── Export ──
        try {
            if (ocFile != null) {
                ImageExport.exportColor(map, soils, new File(ocFile));
            }
            if (ohFile != null) {
                ImageExport.exportHeightmap(map, scale, new File(ohFile));
            }
            if (onFile != null) {
                ImageExport.exportNormals(map, scale, new File(onFile));
            }
            if (ofFile != null) {
                ImageExport.exportFrequency(sizeX, sizeY, new File(ofFile));
            }
        } catch (Exception e) {
            System.err.println("Export error: " + e.getMessage());
            e.printStackTrace();
        }

        // Print pool usage
        System.out.printf("Memory pool usage: %.1f%%%n",
            100.0 * (1.0 - (double) map.pool.freeCount() / 10_000_000));
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java SoilMachine [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -SEED [#]     Run using seed. No seed = random");
        System.out.println("  -soil [file]  Specify relative path to .soil file");
        System.out.println("  -oc [file]    Export color map to .png at path");
        System.out.println("  -oh [file]    Export height map to .png at path");
        System.out.println("  -on [file]    Export normal map to .png at path");
        System.out.println("  -of [file]    Export water frequency map to .png at path");
        System.out.println("  -iter [#]     Number of erosion iterations (default: 500)");
        System.out.println("  -h, --help    Show this help");
    }
}
