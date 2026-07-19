package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Parses .soil configuration files. Maps to loadsoil() in io.h.
 */
public class SoilParser {

    public static class Config {
        public ArrayList<SoilType> soils = new ArrayList<>();
        public ArrayList<NoiseLayer> layers = new ArrayList<>();
        public HashMap<String, Integer> soilMap = new HashMap<>();
        public int sizeX = 256, sizeY = 256, scale = 80;
        public int nWater = 250, nWind = 250;
    }

    public static Config load(String path) throws IOException {
        Config config = new Config();

        // Add Air as type 0
        SoilType air = SoilType.air();
        config.soils.add(air);
        config.soilMap.put("Air", 0);

        File file = new File(path);
        if (!file.exists()) {
            System.err.println("Error: Failed to open soil profile " + path);
            System.exit(1);
        }

        BufferedReader reader = new BufferedReader(new FileReader(file));
        String line;
        int linenr = 0;

        String currentSection = null;  // SOIL, LAYER, or WORLD
        SoilType currentSoil = null;
        int currentSoilIdx = -1;

        while ((line = reader.readLine()) != null) {
            linenr++;

            // Remove comments
            int hashPos = line.indexOf('#');
            if (hashPos >= 0) line = line.substring(0, hashPos);
            line = line.trim();
            if (line.isEmpty()) continue;

            // Close brace
            if (line.equals("}")) {
                currentSection = null;
                currentSoil = null;
                continue;
            }

            // Split tag and value
            int spacePos = line.indexOf(' ');
            if (spacePos < 0) {
                System.err.println("Error: Incorrect Syntax in Line " + linenr);
                System.exit(1);
            }
            String tag = line.substring(0, spacePos).trim();
            String val = line.substring(spacePos + 1).trim();

            // Section openers
            if (tag.equals("SOIL")) {
                int bracePos = val.indexOf('{');
                if (bracePos < 0) { System.err.println("Error: Line " + linenr); System.exit(1); }
                String name = val.substring(0, bracePos).trim();

                if (!config.soilMap.containsKey(name)) {
                    SoilType st = new SoilType(name);
                    config.soilMap.put(name, config.soils.size());
                    config.soils.add(st);
                }
                currentSoilIdx = config.soilMap.get(name);
                currentSoil = config.soils.get(currentSoilIdx);
                currentSection = "SOIL";
                System.out.println("Adding Soil Type " + name);
                continue;
            }

            if (tag.equals("LAYER")) {
                int bracePos = val.indexOf('{');
                if (bracePos < 0) { System.err.println("Error: Line " + linenr); System.exit(1); }
                String name = val.substring(0, bracePos).trim();
                if (!config.soilMap.containsKey(name)) {
                    System.err.println("Can't find SOIL " + name);
                    System.exit(1);
                }
                NoiseLayer nl = new NoiseLayer(config.soilMap.get(name));
                config.layers.add(nl);
                currentSection = "LAYER";
                System.out.println("Adding Layer Type " + name);
                continue;
            }

            if (tag.equals("WORLD")) {
                currentSection = "WORLD";
                continue;
            }

            // Parse properties within sections
            if ("SOIL".equals(currentSection) && currentSoil != null) {
                switch (tag) {
                    case "TRANSPORTS":
                        currentSoil.transports = resolveSoilIdx(config, val);
                        break;
                    case "ERODES":
                        currentSoil.erodes = resolveSoilIdx(config, val);
                        break;
                    case "CASCADES":
                        currentSoil.cascades = resolveSoilIdx(config, val);
                        break;
                    case "ABRADES":
                        currentSoil.abrades = resolveSoilIdx(config, val);
                        break;
                    case "DENSITY": currentSoil.density = Double.parseDouble(val); break;
                    case "POROSITY": currentSoil.porosity = Double.parseDouble(val); break;
                    case "SOLUBILITY": currentSoil.solubility = Double.parseDouble(val); break;
                    case "EQUILIBRIUM": currentSoil.equrate = Double.parseDouble(val); break;
                    case "FRICTION": currentSoil.friction = Double.parseDouble(val); break;
                    case "EROSIONRATE": currentSoil.erosionRate = Double.parseDouble(val); break;
                    case "MAXDIFF": currentSoil.maxDiff = Double.parseDouble(val); break;
                    case "SETTLING": currentSoil.settling = Double.parseDouble(val); break;
                    case "SUSPENSION": currentSoil.suspension = Double.parseDouble(val); break;
                    case "ABRASION": currentSoil.abrasion = Double.parseDouble(val); break;
                    case "COLOR": {
                        int hex = Integer.parseInt(val, 16);
                        currentSoil.r = ((hex >> 16) & 0xFF) / 255.0;
                        currentSoil.g = ((hex >> 8) & 0xFF) / 255.0;
                        currentSoil.b = (hex & 0xFF) / 255.0;
                        break;
                    }
                }
            }

            if ("LAYER".equals(currentSection) && !config.layers.isEmpty()) {
                NoiseLayer nl = config.layers.get(config.layers.size() - 1);
                switch (tag) {
                    case "MIN": nl.min = Double.parseDouble(val); break;
                    case "BIAS": nl.bias = Double.parseDouble(val); break;
                    case "SCALE": nl.scale = Double.parseDouble(val); break;
                    case "OCTAVES": nl.octaves = Double.parseDouble(val); break;
                    case "LACUNARITY": nl.lacunarity = Double.parseDouble(val); break;
                    case "GAIN": nl.gain = Double.parseDouble(val); break;
                    case "FREQUENCY": nl.frequency = Double.parseDouble(val); break;
                }
            }

            if ("WORLD".equals(currentSection)) {
                switch (tag) {
                    case "SIZEX": config.sizeX = Integer.parseInt(val); break;
                    case "SIZEY": config.sizeY = Integer.parseInt(val); break;
                    case "SCALE": config.scale = Integer.parseInt(val); break;
                    case "NWATER": config.nWater = Integer.parseInt(val); break;
                    case "NWIND": config.nWind = Integer.parseInt(val); break;
                }
            }
        }

        reader.close();
        return config;
    }

    private static int resolveSoilIdx(Config config, String name) {
        if (!config.soilMap.containsKey(name)) {
            SoilType st = new SoilType(name);
            config.soilMap.put(name, config.soils.size());
            config.soils.add(st);
        }
        return config.soilMap.get(name);
    }
}
