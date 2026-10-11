package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * Hostile Minecraft mobs (zombies, slimes, ...) right next to one of the Engineer's buildings hit
 * it, once a second each, with their normal attack damage; TF2 takes the same share of the
 * building's health as a hit would take of a Minecraft player's 20. Checked twice a second on
 * Minecraft's built-in server.
 *
 * Since 2026-10-11 (Alex: mobs should interact with buildings and collide, but barely hurt them):
 * mobs can't walk through buildings (pushed out of their boxes every other frame), hostile mobs
 * with nothing closer to chase go for a building within 12 blocks, and they swing when they hit
 * one (robots play their swing). TF2 takes only 2 health per Minecraft damage point (fortcraft_collision.cpp), so zombies
 * and the like are weak against buildings.
 */
public final class MobsVsBuildings {
	private static final long INTERVAL_NANOS = 500_000_000L;
	private static final double REACH = 1.0;      // blocks around the building's box
	private static final long COOLDOWN_TICKS = 20; // one hit a second per mob

	private static long lastCheck;
	private static final Map<UUID, Long> lastHit = new HashMap<>();
	private static int logged;

	private MobsVsBuildings() {
	}

	/** Called every frame while TF2 is linked (render thread). */
	public static void tick(Minecraft minecraft) {
		var server = minecraft.getSingleplayerServer();
		long now = System.nanoTime();
		if (server != null && minecraft.level != null && (++frames & 1) == 0) {
			collide(minecraft, server);
		}
		if (server == null || minecraft.level == null || now - lastCheck < INTERVAL_NANOS) {
			return;
		}
		lastCheck = now;
		List<FortLink.Tf2Building> buildings = FortLink.readBuildings();
		if (buildings == null || buildings.isEmpty()) {
			return;
		}
		var dimension = minecraft.level.dimension();
		server.execute(() -> {
			var level = server.getLevel(dimension);
			if (level == null) {
				return;
			}
			long tick = level.getGameTime();
			for (FortLink.Tf2Building b : buildings) {
				AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(REACH);
				for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> m instanceof Enemy && m.isAlive())) {
					if (!mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) {
						continue;
					}
					Long last = lastHit.get(mob.getUUID());
					if (last != null && tick - last < COOLDOWN_TICKS && tick >= last) {
						continue;
					}
					lastHit.put(mob.getUUID(), tick);
					float damage = (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
					if (damage <= 0) {
						continue;
					}
					FortLink.writeBuildingHit(b.ent(), damage);
					mob.getLookControl().setLookAt((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2, (b.minZ() + b.maxZ()) / 2);
					Robots.noteAttack(mob.getId());
					if (logged++ < 20) {
						FortCraft.LOG.info("FortCraft: {} hit TF2 building {} for {} Minecraft health",
							mob.getName().getString(), b.ent(), String.format("%.1f", damage));
					}
				}
			}
			if (lastHit.size() > 256) {
				lastHit.clear();
			}
		});
	}

	private static int frames;

	/**
	 * Mobs collide with the Engineer's buildings: any mob overlapping a building's box is pushed
	 * out the shortest way sideways. Hostile mobs with no target nearer than the building walk to
	 * the nearest building within 12 blocks to hit it.
	 */
	private static void collide(Minecraft minecraft, net.minecraft.client.server.IntegratedServer server) {
		List<FortLink.Tf2Building> buildings = FortLink.readBuildings();
		if (buildings == null || buildings.isEmpty()) {
			return;
		}
		var dimension = minecraft.level.dimension();
		boolean seek = (frames % 20) == 0;
		server.execute(() -> {
			var level = server.getLevel(dimension);
			if (level == null) {
				return;
			}
			for (FortLink.Tf2Building b : buildings) {
				AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
				for (Mob mob : level.getEntitiesOfClass(Mob.class, box, Mob::isAlive)) {
					AABB m = mob.getBoundingBox();
					if (!m.intersects(box)) {
						continue;
					}
					double pushPlusX = box.maxX - m.minX, pushMinusX = m.maxX - box.minX;
					double pushPlusZ = box.maxZ - m.minZ, pushMinusZ = m.maxZ - box.minZ;
					double best = Math.min(Math.min(pushPlusX, pushMinusX), Math.min(pushPlusZ, pushMinusZ));
					double x = mob.getX(), z = mob.getZ();
					if (best == pushPlusX) {
						x += pushPlusX + 0.01;
					} else if (best == pushMinusX) {
						x -= pushMinusX + 0.01;
					} else if (best == pushPlusZ) {
						z += pushPlusZ + 0.01;
					} else {
						z -= pushMinusZ + 0.01;
					}
					mob.setPos(x, mob.getY(), z);
					mob.setDeltaMovement(mob.getDeltaMovement().multiply(0.0, 1.0, 0.0));
				}
				if (!seek) {
					continue;
				}
				net.minecraft.world.phys.Vec3 centre = box.getCenter();
				for (Mob mob : level.getEntitiesOfClass(Mob.class, box.inflate(12.0), m -> m instanceof Enemy && m.isAlive())) {
					var target = mob.getTarget();
					double toBuilding = mob.position().distanceTo(centre);
					if ((target != null && mob.distanceTo(target) < toBuilding) || toBuilding < 1.5) {
						continue;
					}
					mob.getNavigation().moveTo(centre.x, b.minY(), centre.z, 1.0);
				}
			}
		});
	}
}
