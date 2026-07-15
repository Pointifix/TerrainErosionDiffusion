package com.github.xandergos.terraindiffusionmc.standalone;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.explorer.ExplorerServer;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.PipelineModels;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Standalone terrain explorer — runs the pipeline and web UI outside Minecraft.
 *
 * <p>Usage:
 * <pre>
 *   java -Xmx4g -jar terrain-diffusion-mc.jar com.github.xandergos.terraindiffusionmc.standalone.StandaloneExplorer
 * </pre>
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code terrain_diffusion.models_dir} — path to model files (default: ~/.minecraft/terrain-diffusion-models)</li>
 *   <li>{@code terrain_diffusion.inference_device} — "gpu", "cpu", or "auto" (default: "gpu")</li>
 *   <li>{@code terrain_diffusion.offload_models} — "true"/"false" (default: "true")</li>
 *   <li>{@code terrain_diffusion.explorer_port} — HTTP port (default: 19801)</li>
 *   <li>{@code terrain_diffusion.scale} — world scale 1-6 (default: 2)</li>
 *   <li>{@code terrain_diffusion.validate_model} — "true"/"false" (default: "false" for speed)</li>
 * </ul>
 */
public final class StandaloneExplorer {

    public static void main(String[] args) throws Exception {
        System.setProperty("terrain_diffusion.validate_model",
                System.getProperty("terrain_diffusion.validate_model", "false"));
        System.setProperty("terrain_diffusion.scale",
                System.getProperty("terrain_diffusion.scale", "2"));

        System.out.println("=== Terrain Diffusion Standalone Explorer ===");
        System.out.println("Models dir:  " + System.getProperty("terrain_diffusion.models_dir",
                System.getProperty("user.home") + "/.minecraft/terrain-diffusion-models"));
        System.out.println("Device:      " + System.getProperty("terrain_diffusion.inference_device", "gpu"));
        System.out.println("Offload:     " + System.getProperty("terrain_diffusion.offload_models", "true"));
        System.out.println("Scale:       " + System.getProperty("terrain_diffusion.scale", "2"));
        System.out.println("Erosion:     " + (TerrainDiffusionConfig.erosionEnabled() ? "enabled" : "disabled"));
        System.out.println();

        System.out.println("Loading ML models (this may take a minute)...");
        long start = System.currentTimeMillis();
        PipelineModels.load();
        PipelineModels.awaitLoad();
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("Models loaded in %.1f seconds%n%n", elapsed / 1000.0);

        int port = ExplorerServer.startIfNotRunning();
        System.out.println("Terrain explorer running at http://127.0.0.1:" + port);
        System.out.println("Press Enter to stop.");

        new BufferedReader(new InputStreamReader(System.in)).readLine();

        ExplorerServer.stop();
        PipelineModels.getInstance().close();
        System.out.println("Stopped.");
    }
}
