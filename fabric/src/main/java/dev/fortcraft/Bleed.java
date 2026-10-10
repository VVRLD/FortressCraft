package dev.fortcraft;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * TF2 bleeding on Minecraft mobs (Boston Basher, Tribalman's Shiv, Southern Hospitality...).
 * TF2 bleeds a player for 4 damage every half second for the weapon's bleed time; mobs get the
 * same, through the normal hit path (damage numbers included). A new hit restarts the time.
 */
public final class Bleed {
	private static final long TICK_NANOS = 500_000_000L;
	private static final float TF2_DAMAGE_PER_TICK = 4.0f;
	private static final Map<Integer, long[]> bleeding = new HashMap<>();  // entity id -> {end, next tick}

	private Bleed() {
	}

	/** Called on Minecraft's integrated server when a bleeding weapon hits a mob. */
	static synchronized void start(int entityId, int seconds) {
		long now = System.nanoTime();
		long[] b = bleeding.get(entityId);
		long end = now + seconds * 1_000_000_000L;
		if (b == null) {
			bleeding.put(entityId, new long[] { end, now + TICK_NANOS });
		} else {
			b[0] = Math.max(b[0], end);
		}
	}

	/** Once per Minecraft frame while TF2 is linked. */
	public static synchronized void tick(Minecraft minecraft) {
		if (bleeding.isEmpty() || minecraft.level == null) {
			return;
		}
		long now = System.nanoTime();
		for (Iterator<Map.Entry<Integer, long[]>> it = bleeding.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			long[] b = e.getValue();
			Entity target = minecraft.level.getEntity(e.getKey());
			if (now > b[0] || !(target instanceof LivingEntity living) || !living.isAlive()) {
				it.remove();
				continue;
			}
			if (now >= b[1]) {
				b[1] += TICK_NANOS;
				Combat.hurt(minecraft, target, TF2_DAMAGE_PER_TICK, Combat.DAMAGE_SCALE, false, 0);
			}
		}
	}
}
