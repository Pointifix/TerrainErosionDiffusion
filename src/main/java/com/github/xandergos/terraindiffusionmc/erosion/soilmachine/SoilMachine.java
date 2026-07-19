package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

import java.io.File;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;

/**
 * Java port of weigert/SoilMachine — hydraulic erosion with multi-layer terrain.
 *
 * Supports two modes:
 *   1. Single-map mode (original): generates and erodes a single grid from the .soil config.
 *   2. Chunk mode: generates seamless infinite terrain by processing overlapping padded chunks.
 *      Each chunk is (chunkSize+2P)x(chunkSize+2P); the inner chunkSize x chunkSize core is kept.
 *
 * Usage:
 *   java SoilMachine [options]
 *
 * Options:
 *   -SEED [#]           Run using seed. No seed = random
 *   -soil [file]        Specify relative path to .soil file
 *   -o [dir]            Output directory (default: ./output)
 *   -iter [#]           Number of erosion iterations (default: 500)
 *   -parallel           Use parallel erosion (snapshot+accumulate)
 *   -threads [#]        Thread count for parallel mode (default: all cores)
 *   -chunk-size [#]     Core chunk size (default: 128). 0 = use .soil size (single-map mode)
 *   -padding [#]        Ghost zone padding (default: 64)
 *   -chunk x y          Generate a single chunk at chunk coordinates (x, y)
 *   -generate-chunks x1,y1 x2,y2 ...  Generate multiple chunks sequentially
 *   -mosaic x1,y1 x2,y2  Generate a rectangular block of chunks and stitch into single images
 *
 * Chunk output:
 *   Each chunk produces: chunk_{cx}_{cy}_{type}.png
 *   With -mosaic, also produces stitched: {type}_mosaic.png
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

        // Chunk parameters
        int chunkSize = 0;    // 0 = use .soil grid size (single-map mode)
        int padding = 64;
        ArrayList<int[]> chunkList = null;  // list of [cx, cy]

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-SEED")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-"))
                    seed = Integer.parseInt(args[++i]);
            } else if (arg.equals("-soil")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-"))
                    soilFile = args[++i];
            } else if (arg.equals("-o")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-"))
                    outputDir = args[++i];
            } else if (arg.equals("-iter")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-"))
                    iterations = Integer.parseInt(args[++i]);
            } else if (arg.equals("-parallel")) {
                parallel = true;
            } else if (arg.equals("-threads")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-"))
                    threads = Integer.parseInt(args[++i]);
            } else if (arg.equals("-chunk-size")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-"))
                    chunkSize = Integer.parseInt(args[++i]);
            } else if (arg.equals("-padding")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-"))
                    padding = Integer.parseInt(args[++i]);
            } else if (arg.equals("-chunk")) {
                if (i + 2 < args.length) {
                    int cx = Integer.parseInt(args[++i]);
                    int cy = Integer.parseInt(args[++i]);
                    if (chunkList == null) chunkList = new ArrayList<>();
                    chunkList.add(new int[]{cx, cy});
                }
            } else if (arg.equals("-generate-chunks") || arg.equals("-mosaic")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    chunkList = new ArrayList<>();
                    // Collect all coordinate pairs until next flag
                    while (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        i++;
                        String coord = args[i];
                        String[] parts = coord.split(",");
                        if (parts.length == 2) {
                            int cx = Integer.parseInt(parts[0].trim());
                            int cy = Integer.parseInt(parts[1].trim());
                            chunkList.add(new int[]{cx, cy});
                        }
                    }
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
        int scale = config.scale;
        int nWater = config.nWater;

        // Determine chunk mode
        boolean chunkMode = chunkSize > 0 || chunkList != null;
        if (chunkMode) {
            if (chunkSize <= 0) chunkSize = 128;  // default chunk size
            System.out.printf("Chunk mode: core=%d, padding=%d, expanded=%d%n",
                chunkSize, padding, chunkSize + 2 * padding);
            System.out.printf("Scale: %d, Water particles/frame: %d%n", scale, nWater);
            System.out.printf("Soil types: %d, Noise layers: %d%n", soils.length, config.layers.size());
        } else {
            // Single-map mode: use .soil grid size
            int sizeX = config.sizeX, sizeY = config.sizeY;
            System.out.printf("Grid: %dx%d, Scale: %d, Water particles/frame: %d%n", sizeX, sizeY, scale, nWater);
            System.out.printf("Soil types: %d, Noise layers: %d%n", soils.length, config.layers.size());
        }

        File dir = new File(outputDir);
        if (!dir.exists()) dir.mkdirs();

        if (chunkMode) {
            // ── Chunk Generation ──
            runChunkMode(seed, config, soils, scale, nWater, iterations, parallel, threads,
                         chunkSize, padding, chunkList, dir);
        } else {
            // ── Single-map Generation (original behavior) ──
            runSingleMode(seed, config, soils, config.sizeX, config.sizeY, scale, nWater,
                          iterations, parallel, threads, dir);
        }
    }

    // ── Single-map Mode ──

    private static void runSingleMode(int seed, SoilParser.Config config, SoilType[] soils,
                                       int sizeX, int sizeY, int scale, int nWater,
                                       int iterations, boolean parallel, int threads, File dir) {
        Layermap map = new Layermap(sizeX, sizeY, 10_000_000);
        map.initialize(seed, config.layers);

        System.out.printf("Mode: %s%n", parallel ? "parallel" : "sequential");

        long t0 = System.currentTimeMillis();
        runErosion(map, soils, sizeX, sizeY, scale, nWater, seed, iterations, parallel, threads);
        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("Done in %.1f seconds%n", elapsed / 1000.0);

        exportAll(map, soils, scale, sizeX, sizeY, 0, 0, sizeX, sizeY, dir, "");

        System.out.printf("Memory pool usage: %.1f%%%n",
            100.0 * (1.0 - (double) map.pool.freeCount() / 10_000_000));
    }

    // ── Chunk Mode ──

    private static void runChunkMode(int seed, SoilParser.Config config, SoilType[] soils,
                                      int scale, int nWater, int iterations, boolean parallel, int threads,
                                      int chunkSize, int padding, ArrayList<int[]> chunks, File dir) {
        int expandedSize = chunkSize + 2 * padding;
        System.out.printf("Mode: %s%n", parallel ? "parallel" : "sequential");

        long t0 = System.currentTimeMillis();

        for (int ci = 0; ci < chunks.size(); ci++) {
            int cx = chunks.get(ci)[0], cy = chunks.get(ci)[1];
            int worldOffX = cx * chunkSize - padding;
            int worldOffY = cy * chunkSize - padding;

            System.out.printf("%n── Chunk (%d, %d) [%d/%d] ──%n", cx, cy, ci + 1, chunks.size());
            System.out.printf("  World offset: (%d, %d), expanded canvas: %d×%d%n",
                worldOffX, worldOffY, expandedSize, expandedSize);

            // Create expanded canvas
            Layermap map = new Layermap(expandedSize, expandedSize, 10_000_000);
            map.initialize(seed, config.layers, worldOffX, worldOffY, chunkSize, chunkSize);

            // Run erosion on expanded canvas
            runErosion(map, soils, expandedSize, expandedSize, scale, nWater, seed, iterations, parallel, threads);

            // Export only the inner core
            String suffix = String.format("_%d_%d", cx, cy);
            exportAll(map, soils, scale, expandedSize, expandedSize, padding, padding, chunkSize, chunkSize, dir, suffix);

            System.out.printf("  Chunk (%d, %d) complete.%n", cx, cy);
        }

        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("%nAll %d chunks done in %.1f seconds%n", chunks.size(), elapsed / 1000.0);
    }

    // ── Shared Erosion Runner ──

    private static void runErosion(Layermap map, SoilType[] soils, int sizeX, int sizeY,
                                    int scale, int nWater, int seed, int iterations,
                                    boolean parallel, int threads) {
        WaterParticle.initFrequency(sizeX, sizeY);

        if (parallel) {
            ForkJoinPool pool = new ForkJoinPool(threads);
            ParallelHydraulicErosion parErosion = new ParallelHydraulicErosion(sizeX, sizeY, scale, soils);
            parErosion.erode(map, iterations, nWater, seed, pool);
            pool.shutdown();
        } else {
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
    }

    // ── Shared Export ──

    private static void exportAll(Layermap map, SoilType[] soils, int scale,
                                   int fullSizeX, int fullSizeY,
                                   int offX, int offY, int outW, int outH,
                                   File dir, String suffix) {
        try {
            ImageExport.exportHeightmapRegion(map, scale, offX, offY, outW, outH,
                new File(dir, "heightmap" + suffix + ".png"));
            ImageExport.exportColorRegion(map, soils, offX, offY, outW, outH,
                new File(dir, "colormap" + suffix + ".png"));
            ImageExport.exportNormalsRegion(map, scale, offX, offY, outW, outH,
                new File(dir, "normals" + suffix + ".png"));
            ImageExport.exportFrequency(WaterParticle.frequency, fullSizeX, fullSizeY,
                offX, offY, outW, outH,
                new File(dir, "frequency" + suffix + ".png"));
            ImageExport.exportNaturalRegion(map, soils, WaterParticle.frequency, fullSizeX, scale,
                offX, offY, outW, outH,
                new File(dir, "natural" + suffix + ".png"));
        } catch (Exception e) {
            System.err.println("Export error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java SoilMachine [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -SEED [#]               Run using seed. No seed = random");
        System.out.println("  -soil [file]            Specify relative path to .soil file");
        System.out.println("  -o [dir]                Output directory (default: ./output)");
        System.out.println("  -iter [#]               Number of erosion iterations (default: 500)");
        System.out.println("  -parallel               Use parallel erosion");
        System.out.println("  -threads [#]            Thread count for parallel mode (default: all cores)");
        System.out.println();
        System.out.println("Chunk mode:");
        System.out.println("  -chunk-size [#]         Core chunk size (default: 128)");
        System.out.println("  -padding [#]            Ghost zone padding (default: 64)");
        System.out.println("  -chunk x y              Generate single chunk at (x, y)");
        System.out.println("  -generate-chunks x1,y1 x2,y2 ...");
        System.out.println("                            Generate multiple chunks sequentially");
        System.out.println("  -mosaic x1,y1 x2,y2     Generate rectangular block, stitch into single images");
        System.out.println();
        System.out.println("Output files (always produced in output dir):");
        System.out.println("  heightmap.png           Grayscale heightmap");
        System.out.println("  colormap.png            Soil type color map");
        System.out.println("  normals.png             Surface normal map");
        System.out.println("  frequency.png           Water frequency map");
        System.out.println("  natural.png             Combined natural rendering");
        System.out.println();
        System.out.println("Chunk output: chunk_{cx}_{cy}_{type}.png");
        System.out.println("Mosaic output: stitched {type}_mosaic.png");
    }
}
