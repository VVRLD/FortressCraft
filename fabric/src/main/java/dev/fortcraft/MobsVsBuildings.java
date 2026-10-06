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
}
