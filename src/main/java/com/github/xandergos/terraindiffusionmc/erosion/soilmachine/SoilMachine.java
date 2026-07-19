package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

import java.io.File;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;

/**
 * Java port of weigert/SoilMachine — hydraulic erosion with multi-layer terrain.
 *
 * Usage:
 *   java SoilMachine [options]
 *
 * Options:
 *   -SEED [#]       Run using seed. No seed = random
 *   -soil [file]    Specify relative path to .soil file
 *   -o [dir]        Output directory (default: ./output)
 *   -iter [#]       Number of erosion iterations (default: 500)
 *   -parallel       Use parallel erosion (snapshot+accumulate)
 *   -threads [#]    Thread count for parallel mode (default: all cores)
 *
 * Always produces in output dir:
 *   heightmap.png, colormap.png, normals.png, frequency.png, natural.png
 */
public class SoilMachine {

    public static void main(String[] args) {
        System.out.println("SoilMachine Java Port");

        Integer seed = null;
        String soilFile = "soil/default.soil";
        String outputDir = "output";
        int iterations = 500;
        boolean parallel = false;
        int threads = Runtime.getRuntime().availableProcessors();

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
            } else if (arg.equals("-o")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    outputDir = args[++i];
                }
            } else if (arg.equals("-iter")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    iterations = Integer.parseInt(args[++i]);
                }
            } else if (arg.equals("-parallel")) {
                parallel = true;
            } else if (arg.equals("-threads")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    threads = Integer.parseInt(args[++i]);
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
        System.out.printf("Mode: %s%n", parallel ? "parallel" : "sequential");

        long t0 = System.currentTimeMillis();
        if (parallel) {
            ForkJoinPool pool = new ForkJoinPool(threads);
            ParallelHydraulicErosion parErosion = new ParallelHydraulicErosion(sizeX, sizeY, scale, soils);
            parErosion.erode(map, iterations, nWater, seed, pool);
            pool.shutdown();
        } else {
            WaterParticle.initFrequency(sizeX, sizeY);
            Random rng = new Random(seed);
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
        }
        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("Done in %.1f seconds%n", elapsed / 1000.0);

        // ── Export all images ──
        File dir = new File(outputDir);
        if (!dir.exists()) dir.mkdirs();

        try {
            ImageExport.exportHeightmap(map, scale, new File(dir, "heightmap.png"));
            ImageExport.exportColor(map, soils, new File(dir, "colormap.png"));
            ImageExport.exportNormals(map, scale, new File(dir, "normals.png"));
            ImageExport.exportFrequency(sizeX, sizeY, new File(dir, "frequency.png"));
            ImageExport.exportNatural(map, soils, WaterParticle.frequency, scale, new File(dir, "natural.png"));
        } catch (Exception e) {
            System.err.println("Export error: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.printf("Memory pool usage: %.1f%%%n",
            100.0 * (1.0 - (double) map.pool.freeCount() / 10_000_000));
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java SoilMachine [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -SEED [#]       Run using seed. No seed = random");
        System.out.println("  -soil [file]    Specify relative path to .soil file");
        System.out.println("  -o [dir]        Output directory (default: ./output)");
        System.out.println("  -iter [#]       Number of erosion iterations (default: 500)");
        System.out.println("  -parallel       Use parallel erosion (snapshot+accumulate)");
        System.out.println("  -threads [#]    Thread count for parallel mode (default: all cores)");
        System.out.println("  -h, --help      Show this help");
        System.out.println();
        System.out.println("Output files (always produced in output dir):");
        System.out.println("  heightmap.png   Grayscale heightmap");
        System.out.println("  colormap.png    Soil type color map");
        System.out.println("  normals.png     Surface normal map");
        System.out.println("  frequency.png   Water frequency map");
        System.out.println("  natural.png     Combined natural rendering");
    }
}
