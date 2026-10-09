package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import net.minecraft.client.Minecraft;
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
 */
public final class FarOcclusion {
	private static long frame;
	private static final int[] hidden = new int[64];
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
		int n = 0;
		for (FortLink.FarObject o : objects) {
			Vec3 centre = new Vec3(o.x(), o.y(), o.z());
			double r = Math.min(o.radius(), 3.0) * 0.8;
			Vec3[] points = { centre, centre.add(0, r, 0), centre.add(0, -r * 0.6, 0), centre.add(side.scale(r)), centre.add(side.scale(-r)) };
			boolean seen = false;
			for (Vec3 p : points) {
				if (visible(minecraft, eye, p, Math.max(0.3, r))) {
					seen = true;
					break;
				}
			}
			if (!seen && n < hidden.length) {
				hidden[n++] = o.ent();
			}
		}
		FortLink.writeFarHidden(hidden, n);
		long now = System.nanoTime();
		if (now - logAt > 5_000_000_000L && objects.length > 0) {
			logAt = now;
			FortCraft.LOG.info("FortCraft: far TF2 objects {}, hidden behind blocks {}", objects.length, n);
		}
	}

	/** True if nothing solid lies between the eye and (within slack of) the point. */
	private static boolean visible(Minecraft minecraft, Vec3 eye, Vec3 point, double slack) {
		HitResult hit = minecraft.level.clip(new ClipContext(eye, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(point) <= slack;
	}
}
