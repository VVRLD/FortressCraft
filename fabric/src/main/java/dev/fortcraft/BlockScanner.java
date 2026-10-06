package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Sends the solid blocks around the player to TF2 as boxes, so TF2's movement collides with
 * them. Full cubes are merged into larger boxes (runs along X, then stacked along Z) to keep
 * the list short; other shapes (slabs, stairs, fences) are sent box by box.
 *
 * Also the blocks around and just ahead of every TF2 projectile in flight: TF2 only knows the
 * blocks it is sent, so a rocket that left the player's area flew on through hills for ever.
 */
public final class BlockScanner {
	private static final int RADIUS = 12;   // blocks around the player, horizontally
	private static final int BELOW = 10;
	private static final int ABOVE = 16;    // rocket jumps go high
	private static final int EVERY_FRAMES = 6;
	private static final int EVERY_FRAMES_PROJECTILES = 2;  // rockets cover ~23 blocks a second
	private static final double LEAD_SECONDS = 0.25;  // scan this far ahead of a projectile
	private static final int PROJECTILE_MARGIN = 2;   // and this many blocks around its path
	private static final int PROJECTILE_MAX_SPAN = 16;
	private static final int MAX_PROJECTILES = 32;

	private static final int SX = RADIUS * 2 + 1, SY = BELOW + ABOVE + 1, SZ = RADIUS * 2 + 1;
	private static final boolean[] full = new boolean[SX * SY * SZ];
	private static final boolean[] used = new boolean[SX * SY * SZ];
	private static final float[] boxes = new float[FortLink.MAX_BOXES * 6];
	private static int count;
	private static final float[] visual = new float[FortLink.MAX_VISUAL_BOXES * 6];
	private static int visualCount;
	private static boolean visualOn;
	private static long frame;
	private static int lastLoggedCount = -1;
	private static final float[] waterBoxes = new float[FortLink.MAX_WATER_BOXES * 6];

	// Last seen position of each projectile (TF2 entity index), to know where it's heading.
	private record Seen(float x, float y, float z, long nanos) {
	}

	private static final Map<Integer, Seen> lastSeen = new HashMap<>();

	private BlockScanner() {
	}

	public static void tick(ClientLevel level, double px, double py, double pz) {
		writeNearbyWater(level, px, py, pz);
		var projectiles = FortLink.readProjectiles();
		int every = projectiles.isEmpty() ? EVERY_FRAMES : EVERY_FRAMES_PROJECTILES;
		if (++frame % every != 0) {
			return;
		}
		int ox = (int) Math.floor(px) - RADIUS, oy = (int) Math.floor(py) - BELOW, oz = (int) Math.floor(pz) - RADIUS;
		count = 0;
		visualCount = 0;
		visualOn = true;
		scanRegion(level, ox, oy, oz, SX, SY, SZ);
		visualOn = false;  // projectile areas: collision only
		FortLink.writeVisualBoxes(visual, visualCount);

		long now = System.nanoTime();
		Map<Integer, Seen> seen = new HashMap<>();
		int n = 0;
		for (FortLink.Projectile p : projectiles) {
			if (n++ >= MAX_PROJECTILES) {
				break;
			}
			seen.put(p.id(), new Seen(p.x(), p.y(), p.z(), now));
			double ax = p.x(), ay = p.y(), az = p.z();
			double bx = ax, by = ay, bz = az;
			Seen before = lastSeen.get(p.id());
			if (before != null && now > before.nanos()) {
				double k = LEAD_SECONDS / ((now - before.nanos()) / 1e9);
				bx += (p.x() - before.x()) * k;
				by += (p.y() - before.y()) * k;
				bz += (p.z() - before.z()) * k;
			}
			int x0 = (int) Math.floor(Math.min(ax, bx)) - PROJECTILE_MARGIN, x1 = (int) Math.floor(Math.max(ax, bx)) + PROJECTILE_MARGIN;
			int y0 = (int) Math.floor(Math.min(ay, by)) - PROJECTILE_MARGIN, y1 = (int) Math.floor(Math.max(ay, by)) + PROJECTILE_MARGIN;
			int z0 = (int) Math.floor(Math.min(az, bz)) - PROJECTILE_MARGIN, z1 = (int) Math.floor(Math.max(az, bz)) + PROJECTILE_MARGIN;
			// Already covered by the player's area: nothing to add.
			if (x0 >= ox && x1 < ox + SX && y0 >= oy && y1 < oy + SY && z0 >= oz && z1 < oz + SZ) {
				continue;
			}
			scanRegion(level, x0, y0, z0, Math.min(x1 - x0 + 1, PROJECTILE_MAX_SPAN), Math.min(y1 - y0 + 1, PROJECTILE_MAX_SPAN), Math.min(z1 - z0 + 1, PROJECTILE_MAX_SPAN));
		}
		lastSeen.clear();
		lastSeen.putAll(seen);

		FortLink.writeBoxes(boxes, count);
		if (count != lastLoggedCount && projectiles.isEmpty()) {
			FortCraft.LOG.info("FortCraft: sent {} block boxes around {} {} {}", count, (int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
			lastLoggedCount = count;
		}
	}

	/** A 5x9x5 fluid window follows TF2's player every frame, including deep water. */
	private static void writeNearbyWater(ClientLevel level, double px, double py, double pz) {
		int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int n = 0;
		for (int y = cy - 4; y <= cy + 4; y++) {
			for (int z = cz - 2; z <= cz + 2; z++) {
				for (int x = cx - 2; x <= cx + 2; x++) {
					pos.set(x, y, z);
					var fluid = level.getFluidState(pos);
					if (!fluid.is(FluidTags.WATER) || n >= FortLink.MAX_WATER_BOXES) {
						continue;
					}
					float height = fluid.getHeight(level, pos);
					if (height <= 0) {
						continue;
					}
					int o = n++ * 6;
					waterBoxes[o] = x;
					waterBoxes[o + 1] = y;
					waterBoxes[o + 2] = z;
					waterBoxes[o + 3] = x + 1;
					waterBoxes[o + 4] = y + height;
					waterBoxes[o + 5] = z + 1;
				}
			}
		}
		FortLink.writeWaterBoxes(waterBoxes, n);
	}

	/** Adds the boxes of one region (sx * sy * sz blocks from ox, oy, oz; at most the player's area). */
	private static void scanRegion(ClientLevel level, int ox, int oy, int oz, int sx, int sy, int sz) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					pos.set(ox + x, oy + y, oz + z);
					BlockState state = level.getBlockState(pos);
					VoxelShape shape = state.getCollisionShape(level, pos);
					boolean isFull = !shape.isEmpty() && Block.isShapeFullBlock(shape);
					int i = (y * sz + z) * sx + x;
					full[i] = isFull;
					used[i] = false;
					if (!isFull && !shape.isEmpty()) {
						int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
						shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> add(bx + x1, by + y1, bz + z1, bx + x2, by + y2, bz + z2));
					}
					if (shape.isEmpty() && visualOn && !state.isAir()) {
						// Walk-through but visible (grass, flowers, torches...): its outline, for TF2's depth only.
						VoxelShape outline = state.getShape(level, pos);
						if (!outline.isEmpty() && visualCount < FortLink.MAX_VISUAL_BOXES) {
							var b = outline.bounds();
							int o = visualCount++ * 6;
							visual[o] = (float) (pos.getX() + b.minX);
							visual[o + 1] = (float) (pos.getY() + b.minY);
							visual[o + 2] = (float) (pos.getZ() + b.minZ);
							visual[o + 3] = (float) (pos.getX() + b.maxX);
							visual[o + 4] = (float) (pos.getY() + b.maxY);
							visual[o + 5] = (float) (pos.getZ() + b.maxZ);
						}
					}
				}
			}
		}

		// Full cubes: runs along X, merged with the identical run in the next Z row.
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					int i = (y * sz + z) * sx + x;
					if (!full[i] || used[i]) {
						continue;
					}
					int x2 = x;
					while (x2 + 1 < sx && full[i + (x2 + 1 - x)] && !used[i + (x2 + 1 - x)]) {
						x2++;
					}
					int z2 = z;
					while (z2 + 1 < sz && rowFree(y, z2 + 1, x, x2, sx, sz)) {
						z2++;
					}
					for (int zz = z; zz <= z2; zz++) {
						for (int xx = x; xx <= x2; xx++) {
							used[(y * sz + zz) * sx + xx] = true;
						}
					}
					add(ox + x, oy + y, oz + z, ox + x2 + 1, oy + y + 1, oz + z2 + 1);
				}
			}
		}
	}

	private static boolean rowFree(int y, int z, int x1, int x2, int sx, int sz) {
		for (int x = x1; x <= x2; x++) {
			int i = (y * sz + z) * sx + x;
			if (!full[i] || used[i]) {
				return false;
			}
		}
		return true;
	}

	private static void add(double x1, double y1, double z1, double x2, double y2, double z2) {
		if (count >= FortLink.MAX_BOXES) {
			return;
		}
		int o = count * 6;
		boxes[o] = (float) x1;
		boxes[o + 1] = (float) y1;
		boxes[o + 2] = (float) z1;
		boxes[o + 3] = (float) x2;
		boxes[o + 4] = (float) y2;
		boxes[o + 5] = (float) z2;
		count++;
	}
}
