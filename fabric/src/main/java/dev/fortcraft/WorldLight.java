package dev.fortcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * How bright Minecraft's world is at the player's eyes, 0..1, for TF2 to dim its models by (TF2
 * draws them full-bright: its own map's lighting doesn't reach Minecraft's world). Uses the
 * same light level Minecraft lights blocks with: the brighter of block light and sky light (sky
 * light already darkened for night), through Minecraft's brightness curve. Eased over a few
 * frames so walking past a torch doesn't flicker.
 */
public final class WorldLight {
	private static final float MIN_LIGHT = 0.08f; // Minecraft's own darkest isn't pitch black
	private static float shown = 1.0f;

	private WorldLight() {
	}

	public static float at(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null) {
			return 1.0f;
		}
		BlockPos eyes = BlockPos.containing(minecraft.player.getEyePosition());
		int level = minecraft.level.getRawBrightness(eyes, minecraft.level.getSkyDarken());
		float f = level / 15.0f;
		float curve = f / (4.0f - 3.0f * f); // Minecraft's light-level-to-brightness curve
		float target = MIN_LIGHT + (1.0f - MIN_LIGHT) * curve;
		shown += (target - shown) * 0.1f;
		return shown;
	}
}
