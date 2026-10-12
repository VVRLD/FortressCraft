package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Echo where you are (Alex, 2026-10-12): TF2's sounds (voice lines, guns...) get TF2's own room
 * echo ("dsp_room") from the space around the player in Minecraft. Twice a second Minecraft casts
 * 26 rays from the player's eyes up to 24 blocks and picks one of TF2's presets:
 * - open sky, or most rays escape: no echo (0);
 * - under water: TF2's water preset (14);
 * - mostly natural stone and little sky light: a cave, small / medium / large cavern (23-25);
 * - mostly stone otherwise (stone buildings): concrete room (17-19);
 * - otherwise (wood houses...): chamber (8-10).
 * Size is the rays' average free distance. A new preset is only sent after two samples agree,
 * so walking past a doorway doesn't flicker. TF2 fades between presets itself.
 */
public final class Acoustics {
	public static final int NONE = 0, WATER = 14, CHAMBER = 8, CONCRETE = 17, CAVERN = 23;
	private static final double REACH = 24.0;
	private static final Vec3[] RAYS = rays();

	private static long nextAt;
	private static int candidate = -1, sent = -1;

	private Acoustics() {
	}

	private static Vec3[] rays() {
		java.util.List<Vec3> out = new java.util.ArrayList<>();
		for (int x = -1; x <= 1; x++) {
			for (int y = -1; y <= 1; y++) {
				for (int z = -1; z <= 1; z++) {
					if (x != 0 || y != 0 || z != 0) {
						out.add(new Vec3(x, y, z).normalize());
					}
				}
			}
		}
		return out.toArray(new Vec3[0]);
	}

	/** Once per Minecraft frame while TF2 is linked. */
	public static void tick(Minecraft minecraft) {
		long now = System.nanoTime();
		if (now < nextAt || minecraft.level == null || minecraft.player == null) {
			return;
		}
		nextAt = now + 500_000_000L;
		int preset = measure(minecraft);
		if (preset != candidate) {
			candidate = preset;  // needs a second sample to agree
			return;
		}
		if (preset != sent) {
			sent = preset;
			FortLink.writeRoomSound(preset);
			FortCraft.LOG.info("FortCraft: sound echo preset {} ({})", preset, name(preset));
		}
	}

	private static String name(int preset) {
		return switch (preset) {
			case NONE -> "outdoors, no echo";
			case WATER -> "under water";
			case CAVERN, CAVERN + 1, CAVERN + 2 -> "cave";
			case CONCRETE, CONCRETE + 1, CONCRETE + 2 -> "stone room";
			default -> "room";
		};
	}

	private static int measure(Minecraft minecraft) {
		var level = minecraft.level;
		var player = minecraft.player;
		Vec3 eye = player.getEyePosition();
		BlockPos head = BlockPos.containing(eye);
		if (player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) {
			return WATER;
		}
		if (level.canSeeSky(head)) {
			return NONE;
		}
		int escaped = 0, stone = 0, hits = 0;
		double total = 0;
		for (Vec3 dir : RAYS) {
			BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(dir.scale(REACH)), ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, player));
			if (hit.getType() == HitResult.Type.MISS) {
				escaped++;
				total += REACH;
				continue;
			}
			total += eye.distanceTo(hit.getLocation());
			hits++;
			BlockState state = level.getBlockState(hit.getBlockPos());
			if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
				|| state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.STONE_BRICKS)
				|| state.is(BlockTags.STONE_ORE_REPLACEABLES) || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) {
				stone++;
			}
		}
		if (escaped * 10 >= RAYS.length * 4) {
			return NONE;  // a roof overhead but open around (under a tree, a porch): outdoors
		}
		double average = total / RAYS.length;
		int size = average < 5.0 ? 0 : average < 11.0 ? 1 : 2;
		boolean stoney = hits > 0 && stone * 2 > hits;
		int sky = level.getBrightness(LightLayer.SKY, head);
		if (stoney && sky < 6) {
			return CAVERN + size;
		}
		return (stoney ? CONCRETE : CHAMBER) + size;
	}
}
