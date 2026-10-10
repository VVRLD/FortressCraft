package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * TF2's fire weapons in Minecraft's world: each fire point TF2 reports (flamethrower flames,
 * flares, Scorch Shot and Detonator bursts, Dragon's Fury) sets living mobs within its radius on
 * fire and lights one fire block on a spot where Minecraft allows fire. Nothing is broken. A
 * negative radius (the Pyro's airblast) puts out fire blocks and burning mobs within it instead.
 */
public final class Fire {
	private static final float BURN_SECONDS = 5.0f;
	private static final long BLOCK_FIRE_GAP_NANOS = 150_000_000L; // at most ~7 new fire blocks a second

	private static int seen = -1;
	private static long lastBlockFire;
	private static int logged;

	private Fire() {
	}

	/** Called every frame while TF2 is linked (render thread); the world changes run on the server. */
	public static void tick(Minecraft minecraft) {
		var server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null || minecraft.level == null) {
			return;
		}
		int count = FortLink.fireCount();
		if (seen == -1 || count - seen > 64 || count < seen) {
			seen = count; // first look, or TF2 restarted: don't replay old ones
		}
		var dimension = minecraft.level.dimension();
		var playerId = minecraft.player.getUUID();
		while (seen != count) {
			float[] f = FortLink.fire(seen++);
			Vec3 at = new Vec3(f[0], f[1], f[2]);
			if (f[3] < 0) {
				double reach = -f[3];
				server.execute(() -> {
					ServerLevel level = server.getLevel(dimension);
					if (level != null) {
						extinguish(level, at, reach);
						blowAway(level, at, reach, server.getPlayerList().getPlayer(playerId));
					}
				});
				continue;
			}
			double radius = Math.max(0.5, f[3]);
			long now = System.nanoTime();
			boolean placeBlock = now - lastBlockFire > BLOCK_FIRE_GAP_NANOS;
			if (placeBlock) {
				lastBlockFire = now;
			}
			server.execute(() -> {
				ServerLevel level = server.getLevel(dimension);
				if (level != null) {
					burn(level, at, radius, placeBlock, server.getPlayerList().getPlayer(playerId));
				}
			});
		}
	}

	private static void burn(ServerLevel level, Vec3 at, double radius, boolean placeBlock, Object player) {
		int mobs = 0;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(radius),
			e -> e.isAlive() && e != player && !e.fireImmune())) {
			e.igniteForSeconds(BURN_SECONDS);
			mobs++;
		}
		BlockPos lit = placeBlock ? lightFire(level, at) : null;
		if (logged < 20 && (mobs > 0 || lit != null)) {
			logged++;
			FortCraft.LOG.info("FortCraft: TF2 fire at {} set {} mobs on fire{}", at, mobs, lit != null ? ", lit fire at " + lit : "");
		}
	}

	/**
	 * The Pyro's airblast also shoves mobs, as it shoves TF2 players: every mob within reach is
	 * pushed away from the player and up a little (Alex's tester: airblast did nothing to mobs).
	 */
	private static void blowAway(ServerLevel level, Vec3 at, double reach, Object player) {
		if (!(player instanceof net.minecraft.world.entity.Entity pyro)) {
			return;
		}
		int pushed = 0;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(reach),
			e -> e.isAlive() && e != pyro && e.position().distanceTo(at) <= reach)) {
			Vec3 away = e.position().subtract(pyro.position());
			away = new Vec3(away.x, 0, away.z);
			if (away.lengthSqr() < 1.0e-4) {
				away = pyro.getLookAngle();
			}
			away = away.normalize();
			e.push(away.x * 1.6, 0.45, away.z * 1.6);
			e.needsSync = true;  // send the new velocity to the client now
			pushed++;
		}
		if (pushed > 0) {
			FortCraft.LOG.info("FortCraft: airblast pushed {} mobs", pushed);
		}
	}

	/** Puts out every fire block and burning mob within reach (a sphere). */
	private static void extinguish(ServerLevel level, Vec3 at, double reach) {
		int mobs = 0;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(reach),
			e -> e.isOnFire() && e.position().distanceTo(at) <= reach)) {
			e.clearFire();
			mobs++;
		}
		int blocks = 0;
		BlockPos centre = BlockPos.containing(at);
		int r = (int) Math.ceil(reach);
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
			if (Vec3.atCenterOf(pos).distanceTo(at) <= reach && level.isLoaded(pos)
				&& level.getBlockState(pos).getBlock() instanceof BaseFireBlock) {
				level.removeBlock(pos, false);
				blocks++;
			}
		}
		FortCraft.LOG.info("FortCraft: airblast at {} put out {} fire blocks and {} burning mobs", at, blocks, mobs);
	}

	/** One fire block in an air spot next to the point where Minecraft allows fire; null if none. */
	private static BlockPos lightFire(ServerLevel level, Vec3 at) {
		BlockPos centre = BlockPos.containing(at);
		BlockPos[] candidates = { centre, centre.above(), centre.north(), centre.south(), centre.east(), centre.west(), centre.below() };
		for (BlockPos pos : candidates) {
			if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) {
				continue;
			}
			for (Direction side : Direction.values()) {
				if (BaseFireBlock.canBePlacedAt(level, pos, side)) {
					level.setBlockAndUpdate(pos, BaseFireBlock.getState(level, pos));
					return pos;
				}
			}
		}
		return null;
	}
}
