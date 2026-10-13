package dev.fortcraft;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

/**
 * Low-end mode (Alex, 2026-10-12): for weaker PCs and laptops, started with
 * PLAY-FORTCRAFT-LOW-END.cmd, which leaves the file fortcraft_low_end.txt in fabric/run (the normal
 * launcher removes it). Then both games run at 60 fps instead of 240, TF2 draws at 720p with low
 * graphics (tools/run_tf2.bat, fortcraft_link.cpp), and Minecraft's own settings are turned down
 * once at start: fast graphics, 6 chunks render / 5 simulation distance, fewer particles, no
 * clouds, no entity shadows, no smooth lighting, no biome blending. Those Minecraft settings are
 * saved like any change in Options, so they stay after a normal start until changed back.
 */
public final class LowEnd {
	public static final boolean ON = Files.exists(Path.of("fortcraft_low_end.txt"));
	private static boolean applied;

	private LowEnd() {
	}

	/** Once, on Minecraft's first frame. */
	static void apply(Minecraft minecraft) {
		if (!ON || applied) {
			return;
		}
		applied = true;
		Options o = minecraft.options;
		o.applyGraphicsPreset(GraphicsPreset.FAST);
		o.renderDistance().set(Math.min(o.renderDistance().get(), 6));
		o.simulationDistance().set(Math.min(o.simulationDistance().get(), 5));
		o.particles().set(ParticleStatus.DECREASED);
		o.cloudStatus().set(CloudStatus.OFF);
		o.entityShadows().set(false);
		o.ambientOcclusion().set(false);
		o.biomeBlendRadius().set(0);
		o.save();
		FortCraft.LOG.info("FortCraft: low-end mode: 60 fps, fast graphics, render distance {}, simulation {}",
			o.renderDistance().get(), o.simulationDistance().get());
	}
}
