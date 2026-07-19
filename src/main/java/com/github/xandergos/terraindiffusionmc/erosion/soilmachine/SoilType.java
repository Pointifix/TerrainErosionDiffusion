package com.github.xandergos.terraindiffusionmc.erosion.soilmachine;

/**
 * Soil type definition and properties. Maps to SurfParam in surface.h.
 */
public class SoilType {

    public String name;
    public double density;
    public double porosity;
    public double solubility;
    public double equrate;
    public double friction;
    public double erosionRate;
    public double maxDiff;
    public double settling;
    public double suspension;
    public double abrasion;

    // Soil type this erodes into / transports as / cascades as
    public int erodes = -1;
    public int transports = -1;
    public int cascades = -1;
    public int abrades = -1;

    // Color (R, G, B in 0-1)
    public double r = 0.5, g = 0.5, b = 0.5;

    public SoilType() {}

    public SoilType(String name) {
        this.name = name;
    }

    /** Default Air soil type. */
    public static SoilType air() {
        SoilType air = new SoilType("Air");
        air.density = 0.0;
        air.porosity = 1.0;
        return air;
    }
}
