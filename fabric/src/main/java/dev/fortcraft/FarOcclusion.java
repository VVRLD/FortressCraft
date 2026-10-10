package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Far TF2 objects (stickies, buildings, rockets more than 10 blocks away) behind Minecraft blocks.
 * TF2's invisible depth layer only covers the blocks scanned near the player, so those showed
 * through houses and hills (Alex, 2026-10-09). TF2 lists them; here we check Minecraft's own line
 * of sight from the camera to each (centre, top, bottom and both sides) and tell TF2 which are
 * fully blocked, and TF2 doesn't draw those. Objects partly behind a block show whole.
 *
 * Since v44 TF2 lists every object, near ones and its own player too; only objects beyond the
 * depth layer (10 blocks) are ever hidden. Since v45 we also send, for each object reaching into
 * Minecraft water, the height of the water surface there. TF2 draws the part below it dim, blue
 * and half see-through, so Minecraft's water shows over it (a sticky on a lake bed, a player
 * wading; Alex, 2026-10-10).
 */
public final class FarOcclusion {
	private static long frame;
	private static final int[] hidden = new int[512];
	private static final int[] wetEnts = new int[64];
	private static final float[] wetSurface = new float[64];
	private static final double DEPTH_LAYER_BLOCKS = 10.0;
	private static long logAt;

	private FarOcclusion() {
	}

	/** Once per Minecraft frame while TF2 is linked. */
	public static void tick(Minecraft minecraft) {
		if (minecraft.level == null || (++frame & 1) != 0) {
			return;  // every other frame is plenty
		}
		FortLink.FarObject[] objects = FortLink.readFarObjects();
		if (objects == null) {
			return;
		}
		Vec3 eye = minecraft.gameRenderer.mainCamera().position();
		Vec3 look = Vec3.directionFromRotation(0, minecraft.gameRenderer.mainCamera().yRot());
		Vec3 side = new Vec3(-look.z, 0, look.x);
		int n = 0, wet = 0;
		for (FortLink.FarObject o : objects) {
			Vec3 centre = new Vec3(o.x(), o.y(), o.z());
			float surface = waterSurface(minecraft, centre, Math.min(o.radius(), 3.0));
			if (!Float.isNaN(surface) && wet < wetEnts.length) {
				wetEnts[wet] = o.ent();
				wetSurface[wet++] = surface;
			}
			if (centre.distanceTo(eye) <= DEPTH_LAYER_BLOCKS) {
				continue;
			}
			double r = Math.min(o.radius(), 3.0) * 0.8;
			// Small things (cash bags) need only their middle and top; up to 512 are checked.
			Vec3[] points = r < 0.5
				? new Vec3[] { centre, centre.add(0, r, 0) }
				: new Vec3[] { centre, centre.add(0, r, 0), centre.add(0, -r * 0.6, 0), centre.add(side.scale(r)), centre.add(side.scale(-r)) };
			boolean seen = false;
			for (Vec3 p : points) {
				if (visible(minecraft, eye, p, Math.max(0.3, r))) {
					seen = true;
					break;
				}
			}
			if (n >= hidden.length) {
				continue;
			}
			if (!seen) {
				hidden[n++] = o.ent();
			}
		}
		FortLink.writeFarHidden(hidden, n);
		FortLink.writeWaterLines(wetEnts, wetSurface, wet);
		long now = System.nanoTime();
		if (now - logAt > 5_000_000_000L && objects.length > 0) {
			logAt = now;
			FortCraft.LOG.info("FortCraft: TF2 objects {}, hidden behind blocks {}, in water {}", objects.length, n, wet);
		}
	}

	/**
	 * The Minecraft y of the water surface in the object's column, if any of the object (centre
	 * +- radius up and down) is under water; NaN if none of it is.
	 */
	private static float waterSurface(Minecraft minecraft, Vec3 centre, double radius) {
		int x = (int) Math.floor(centre.x), z = (int) Math.floor(centre.z);
		double bottom = centre.y - radius, top = centre.y + radius;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = (int) Math.floor(bottom); y <= (int) Math.floor(top); y++) {
			pos.set(x, y, z);
			var fluid = minecraft.level.getFluidState(pos);
			if (!fluid.is(FluidTags.WATER)) {
				continue;
			}
			// Water here: climb to its surface (at most 64 blocks; deep lakes).
			for (int k = 0; k < 64; k++) {
				pos.set(x, y + 1, z);
				if (!minecraft.level.getFluidState(pos).is(FluidTags.WATER)) {
					break;
				}
				y++;
			}
			pos.set(x, y, z);
			float surface = y + minecraft.level.getFluidState(pos).getHeight(minecraft.level, pos);
			return surface > bottom ? surface : Float.NaN;
		}
		return Float.NaN;
	}

	/** True if nothing solid lies between the eye and (within slack of) the point. */
	private static boolean visible(Minecraft minecraft, Vec3 eye, Vec3 point, double slack) {
		HitResult hit = minecraft.level.clip(new ClipContext(eye, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(point) <= slack;
	}
}
