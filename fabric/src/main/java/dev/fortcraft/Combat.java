package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * TF2 weapons against Minecraft mobs, and Minecraft mobs against the TF2 player.
 *
 * TF2 reports every bullet, pellet and melee swing (start, direction, range, damage) and every
 * explosion. Each is traced through Minecraft's world: the nearest mob it reaches before a
 * block takes the damage, scaled down (TF2 damage / 5). Damage is applied on Minecraft's built-in
 * server as the player's attack, so mobs react and fight back.
 *
 * The other way round is ServerPlayerMixin: while TF2 is linked, damage to Minecraft's player
 * is cancelled and sent to TF2 (x5), where TF2's health takes it.
 */
public final class Combat {
	public static final float DAMAGE_SCALE = 5.0f;              // Minecraft damage = TF2 damage / 5 (TF2 hitting Minecraft mobs)
	// Damage tuning, all in one place: Minecraft damage = TF2 damage / these.
	private static final float BULLET_SCALE = 1.5f;             // bullets/pellets (Alex: shotgun still too weak at /3)
	private static final float MELEE_SCALE = 3.0f;              // melee swings
	private static final float MELEE_REACH = 3.0f;              // blocks; TF2's own swing reaches about 1 block (Alex: had to be really close)
	private static final float MELEE_BOX_GROW = 0.6f;           // melee hits mobs within this much of the aim line
	private static final float BLAST_RADIUS = 146.0f / 48.0f;  // a rocket's blast, in blocks
	private static final float BLAST_DAMAGE = 90.0f;           // a rocket's TF2 damage at the centre
	private static final double TERRAIN_BLAST_RADIUS = 2.0;     // conservative terrain crater
	private static final int MAX_BLAST_BLOCKS = 24;
	private static final double MEDIGUN_RANGE = 9.5;             // TF2's 450-unit reach at 48 units/block
	private static final long HEAL_INTERVAL_NANOS = 100_000_000L;
	private static final float HEAL_PER_INTERVAL = 0.5f;         // five Minecraft HP/s, about TF2's 24 HP/s / 5

	/** True while TF2 is linked; read by ServerPlayerMixin on the server thread. */
	public static volatile boolean linked;

	private static int shotsSeen = -1;
	private static int explosionsSeen = -1;
	private static boolean difficultySet;
	private static int lastHealTargetId = -1;
	private static long lastHealAt;
	private static long lastEffectAt;

	private Combat() {
	}

	/** Once per Minecraft frame while TF2 is linked. */
	public static void tick(Minecraft minecraft, int buttons) {
		if (minecraft.level == null || minecraft.player == null) {
			setHealTarget(null);
			return;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (!difficultySet && server != null) {
			// Hostile mobs don't exist on Peaceful, and the test world was created Peaceful.
			difficultySet = true;
			server.execute(() -> server.setDifficulty(Difficulty.NORMAL, true));
			FortCraft.LOG.info("FortCraft: difficulty set to Normal so hostile mobs can spawn");
		}

		sendMobBoxes(minecraft);
		healFriendlyMob(minecraft, buttons);
		sendSpawnPoint(minecraft);

		int count = FortLink.shotCount();
		if (shotsSeen < 0 || count - shotsSeen > 64 || count - shotsSeen < 0) {
			shotsSeen = count;  // first look, or TF2 restarted: don't replay old shots
		}
		// TF2 reports shotgun pellets separately. Minecraft's hurt cooldown discards
		// most consecutive per-pellet calls, so apply one summed hit per target/frame.
		Map<ShotGroup, Float> shotDamage = new LinkedHashMap<>();
		while (shotsSeen != count) {
			FortLink.Shot s = FortLink.shot(shotsSeen++);
			shoot(minecraft, s, shotDamage);
		}
		if (++miningCleanupFrames % 30 == 0 && server != null) {
			UUID playerId = minecraft.player.getUUID();
			server.execute(() -> {
				if (miningPos != null && System.nanoTime() - miningLastHit > MINING_RESET_NANOS) {
					ServerPlayer attacker = server.getPlayerList().getPlayer(playerId);
					if (attacker != null) {
						clearMiningCracks(server, attacker);
					}
				}
			});
		}
		for (var hit : shotDamage.entrySet()) {
			ShotGroup group = hit.getKey();
			hurt(minecraft, group.target(), hit.getValue(), group.scale(), group.backstab(), group.effects());
		}

		int ex = FortLink.explosionCount();
		if (explosionsSeen < 0 || ex - explosionsSeen > 32 || ex - explosionsSeen < 0) {
			explosionsSeen = ex;
		}
		while (explosionsSeen != ex) {
			float[] e = FortLink.explosion(explosionsSeen++);
			blast(minecraft, new Vec3(e[0], e[1], e[2]), e[3], e[4], (int) e[5]);
		}
	}

	private static void setHealTarget(LivingEntity target) {
		int id = target == null ? -1 : target.getId();
		if (id != lastHealTargetId) {
			FortCraft.LOG.info("FortCraft: Medigun {}", target == null ? "stopped healing Minecraft mob"
				: "targeting " + target.getDisplayName().getString() + " (entity " + id + ")");
			lastHealTargetId = id;
			lastHealAt = 0;
			lastEffectAt = 0;
		}
		if (target == null) {
			FortLink.writeMedicTarget(0, 0, 0, 0, 0, 0, "");
		} else {
			Vec3 centre = target.getBoundingBox().getCenter();
			FortLink.writeMedicTarget(id, (float) centre.x, (float) centre.y, (float) centre.z,
				target.getHealth(), target.getMaxHealth(), target.getDisplayName().getString());
		}
	}

	/** Medigun targets only living non-hostile mobs in its forward line of sight. */
	private static void healFriendlyMob(Minecraft minecraft, int buttons) {
		FortLink.Tf2Camera cam = FortLink.readCamera();
		if (cam == null || !cam.medigun() || cam.uiOpen() || cam.thirdPerson() || (buttons & FortLink.IN_ATTACK) == 0) {
			setHealTarget(null);
			return;
		}
		Vec3 start = new Vec3(cam.x(), cam.y(), cam.z());
		Vec3 end = start.add(Vec3.directionFromRotation(cam.pitch(), cam.yaw()).scale(MEDIGUN_RANGE));
		HitResult block = minecraft.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
			ClipContext.Fluid.NONE, minecraft.player));
		double limit = block.getType() == HitResult.Type.MISS ? MEDIGUN_RANGE : start.distanceTo(block.getLocation());
		LivingEntity best = null;
		double bestDistance = limit;
		for (Entity entity : minecraft.level.getEntities(minecraft.player, new AABB(start, end).inflate(1.0),
			e -> e instanceof LivingEntity && !(e instanceof Enemy) && !(e instanceof Player) && e.isAlive())) {
			var hit = entity.getBoundingBox().inflate(0.15).clip(start, end);
			if (hit.isPresent()) {
				double distance = start.distanceTo(hit.get());
				if (distance < bestDistance) {
					bestDistance = distance;
					best = (LivingEntity) entity;
				}
			}
		}
		setHealTarget(best);
		if (best == null) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastHealAt < HEAL_INTERVAL_NANOS) {
			return;
		}
		lastHealAt = now;
		boolean effect = now - lastEffectAt >= 1_000_000_000L || lastEffectAt == 0;
		if (effect) {
			lastEffectAt = now;
		}
		int entityId = best.getId();
		var dimension = minecraft.level.dimension();
		UUID playerId = minecraft.player.getUUID();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			ServerPlayer medic = server.getPlayerList().getPlayer(playerId);
			Entity entity = level == null ? null : level.getEntity(entityId);
			if (!(entity instanceof LivingEntity living) || entity instanceof Enemy || entity instanceof Player
				|| !living.isAlive() || medic == null
				|| medic.distanceToSqr(living) > MEDIGUN_RANGE * MEDIGUN_RANGE) {
				return;
			}
			Vec3 eye = medic.getEyePosition();
			Vec3 centre = living.getBoundingBox().getCenter();
			HitResult obstruction = level.clip(new ClipContext(eye, centre, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, medic));
			if (obstruction.getType() != HitResult.Type.MISS) {
				return;
			}
			if (living.getHealth() < living.getMaxHealth()) {
				living.heal(HEAL_PER_INTERVAL);
			}
			if (effect) {
				living.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 0, false, true));
				level.sendParticles(ParticleTypes.HEART, centre.x, living.getBoundingBox().maxY + 0.2, centre.z,
					2, 0.2, 0.15, 0.2, 0.01);
				FortCraft.LOG.info("FortCraft: Medigun healed {} to {}/{}", living.getDisplayName().getString(),
					String.format("%.1f", living.getHealth()), String.format("%.1f", living.getMaxHealth()));
			}
		});
	}

	/**
	 * Mobs' hitboxes to TF2 every frame, so TF2's rockets explode on a direct hit and its bullets
	 * stop at the mob (TF2 has no mobs of its own; this side applies the damage).
	 */
	private static void sendMobBoxes(Minecraft minecraft) {
		// End pillars put crystals far above the player; keep ordinary-world scans small.
		AABB mobArea = minecraft.player.getBoundingBox().inflate(MOB_RANGE);
		AABB area = minecraft.player.getBoundingBox().inflate(
			minecraft.level.dimension() == Level.END ? END_TARGET_RANGE : MOB_RANGE);
		int n = 0;
		for (Entity e : minecraft.level.getEntities(minecraft.player, area,
			e -> e instanceof EndCrystal && !e.isRemoved())) {
			if (n >= 128) {
				break;
			}
			n = addMobBox(e, n, true);
		}
		for (Entity e : minecraft.level.getEntities(minecraft.player, area.inflate(16.0),
			e -> e instanceof EnderDragon && e.isAlive())) {
			for (EnderDragonPart part : ((EnderDragon) e).getSubEntities()) {
				if (n >= 128) {
					break;
				}
				if (part.getBoundingBox().intersects(area)) {
					n = addMobBox(part, n, true);
				}
			}
		}
		for (Entity e : minecraft.level.getEntities(minecraft.player, mobArea,
			e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof EnderDragon))) {
			if (n >= 128) {
				break;
			}
			n = addMobBox(e, n, e instanceof Enemy);
		}
		FortLink.writeMobBoxes(mobBoxes, mobHostile, n);
	}

	private static int addMobBox(Entity e, int n, boolean hostile) {
		AABB b = e.getBoundingBox();
		int o = n * 6;
		mobBoxes[o] = (float) b.minX;
		mobBoxes[o + 1] = (float) b.minY;
		mobBoxes[o + 2] = (float) b.minZ;
		mobBoxes[o + 3] = (float) b.maxX;
		mobBoxes[o + 4] = (float) b.maxY;
		mobBoxes[o + 5] = (float) b.maxZ;
		mobHostile[n] = (byte) (hostile ? 1 : 0);
		return n + 1;
	}

	private static final double MOB_RANGE = 48.0;
	private static final double END_TARGET_RANGE = 192.0;
	private static final float[] mobBoxes = new float[128 * 6];
	private static final byte[] mobHostile = new byte[128];

	/**
	 * Where TF2 should respawn the player, by Minecraft's own rules: the bed, respawn anchor or
	 * /spawnpoint position if the player has one that still works, otherwise the world spawn (the
	 * nearest spot around it with solid ground and two free blocks above: never inside terrain).
	 * Worked out on Minecraft's built-in server, which can load the chunks there.
	 */
	private static void sendSpawnPoint(Minecraft minecraft) {
		if (++spawnFrames % 30 != 1) {
			return;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null || spawnBusy) {
			return;
		}
		UUID id = minecraft.player.getUUID();
		spawnBusy = true;
		server.execute(() -> {
			try {
				ServerPlayer sp = server.getPlayerList().getPlayer(id);
				if (sp != null) {
					findSpawn(server, sp);
				}
			} finally {
				spawnBusy = false;
			}
		});
	}

	/** Server thread. */
	private static void findSpawn(IntegratedServer server, ServerPlayer sp) {
		String kind = "world spawn";
		Vec3 at = null;
		ServerPlayer.RespawnConfig config = sp.getRespawnConfig();
		if (config != null && config.respawnData().dimension() == Level.OVERWORLD) {
			at = bedOrAnchor(server.overworld(), config);
			kind = config.forced() ? "spawnpoint" : "bed or anchor";
		}
		if (at == null) {
			kind = "world spawn";
			ServerLevel level = server.overworld();
			BlockPos spawn = level.getRespawnData().pos();
			long key = spawn.asLong();
			if (key != worldSpawnKey || ++worldSpawnChecks % 20 == 0) {
				worldSpawnKey = key;
				worldSpawnAt = null;
				search:
				for (int r = 0; r <= SPAWN_SEARCH; r++) {
					for (int dx = -r; dx <= r; dx++) {
						for (int dz = -r; dz <= r; dz++) {
							if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
								continue;  // ring by ring, nearest first
							}
							int x = spawn.getX() + dx, z = spawn.getZ() + dz;
							int y = safeFeetY(level, x, z);
							if (y != Integer.MIN_VALUE) {
								worldSpawnAt = new Vec3(x + 0.5, y, z + 0.5);
								break search;
							}
						}
					}
				}
			}
			at = worldSpawnAt;
		}
		if (at == null) {
			return;
		}
		FortLink.writeSpawnPoint((float) at.x, (float) at.y, (float) at.z);
		spawnAt = at;
		int bx = (int) Math.floor(at.x), by = (int) Math.floor(at.y), bz = (int) Math.floor(at.z);
		if (bx != lastSpawnX || by != lastSpawnY || bz != lastSpawnZ || !kind.equals(lastSpawnKind)) {
			lastSpawnX = bx;
			lastSpawnY = by;
			lastSpawnZ = bz;
			lastSpawnKind = kind;
			FortCraft.LOG.info("FortCraft: TF2 respawn point {} {} {} ({})", bx, by, bz, kind);
		}
	}

	/**
	 * Minecraft's own check of a bed, respawn anchor or /spawnpoint (ServerPlayer's private
	 * findRespawnAndUseSpawnBlock, without using up an anchor charge or telling the player their
	 * bed is gone). Null if it no longer works.
	 */
	private static Vec3 bedOrAnchor(ServerLevel level, ServerPlayer.RespawnConfig config) {
		try {
			if (findRespawn == null) {
				findRespawn = ServerPlayer.class.getDeclaredMethod("findRespawnAndUseSpawnBlock", ServerLevel.class, ServerPlayer.RespawnConfig.class, boolean.class);
				findRespawn.setAccessible(true);
			}
			java.util.Optional<?> found = (java.util.Optional<?>) findRespawn.invoke(null, level, config, false);
			if (found.isEmpty()) {
				return null;
			}
			Object posAngle = found.get();
			java.lang.reflect.Method position = posAngle.getClass().getDeclaredMethod("position");
			position.setAccessible(true);
			return (Vec3) position.invoke(posAngle);
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (!bedLookupFailedLogged) {
				bedLookupFailedLogged = true;
				FortCraft.LOG.warn("FortCraft: couldn't check the bed/anchor respawn point; using world spawn", e);
			}
			return null;
		}
	}

	/** Feet height of a safe standing spot in this column, or MIN_VALUE if there isn't one. */
	private static int safeFeetY(Level level, int x, int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		// Down from the top: the first spot with ground below and feet + head free and dry.
		for (int y = top + 1; y > level.getMinY() + 1; y--) {
			if (free(level, pos.set(x, y, z)) && free(level, pos.set(x, y + 1, z))
					&& !level.getBlockState(pos.set(x, y - 1, z)).getCollisionShape(level, pos).isEmpty()) {
				return y;
			}
		}
		return Integer.MIN_VALUE;
	}

	private static boolean free(Level level, BlockPos pos) {
		var state = level.getBlockState(pos);
		return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
	}

	private static volatile boolean spawnBusy;
	private static volatile Vec3 spawnAt;

	/** The last spawn point sent to TF2 (Minecraft feet coordinates, overworld), or null. */
	public static Vec3 spawnPoint() {
		return spawnAt;
	}

	private static java.lang.reflect.Method findRespawn;
	private static boolean bedLookupFailedLogged;
	private static long worldSpawnKey = Long.MIN_VALUE;
	private static int worldSpawnChecks;
	private static Vec3 worldSpawnAt;
	private static String lastSpawnKind = "";
	private static final int SPAWN_SEARCH = 8;
	private static int lastSpawnX, lastSpawnY = Integer.MIN_VALUE, lastSpawnZ;

	private static long spawnFrames;

	/** effects: the shot flags that matter after the hit (SENTRY, bleed seconds). */
	private record ShotGroup(Entity target, float scale, boolean backstab, int effects) {
	}

	private static void shoot(Minecraft minecraft, FortLink.Shot shot,
		Map<ShotGroup, Float> shotDamage) {
		Vec3 start = new Vec3(shot.x(), shot.y(), shot.z());
		Vec3 dir = new Vec3(shot.dx(), shot.dy(), shot.dz());
		float range = shot.range();
		boolean melee = (shot.flags() & CombatRules.MELEE) != 0;
		if (melee) {
			range = MELEE_REACH;
		}
		Vec3 end = start.add(dir.scale(Math.min(range, 200.0f)));
		HitResult block = minecraft.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
		double limit = block.getType() == HitResult.Type.MISS ? start.distanceTo(end) : start.distanceTo(block.getLocation());

		Entity best = null;
		Vec3 bestHit = null;
		double bestDist = limit;
		AABB area = new AABB(start, end).inflate(1.0);
		for (Entity e : combatTargets(minecraft, area)) {
			var hit = e.getBoundingBox().inflate(melee ? MELEE_BOX_GROW : 0.1).clip(start, end);
			if (hit.isPresent() && start.distanceTo(hit.get()) < bestDist) {
				bestDist = start.distanceTo(hit.get());
				best = e;
				bestHit = hit.get();
			}
		}
		if (best != null) {
			boolean head = (shot.flags() & CombatRules.HEADSHOT) != 0
				&& (shot.headshotRange() <= 0 || bestDist <= shot.headshotRange()) && headHit(best, bestHit);
			boolean backstab = melee && (shot.flags() & CombatRules.KNIFE) != 0 && behind(best, start, dir);
			float multiplier = CombatRules.multiplier(shot.flags(), head);
			int effects = shot.flags() & (CombatRules.SENTRY | (0xFF << CombatRules.BLEED_SHIFT));
			shotDamage.merge(new ShotGroup(best, melee ? MELEE_SCALE : BULLET_SCALE, backstab, effects), shot.damage() * multiplier, Float::sum);
			if (backstab || multiplier > 1) FortCraft.LOG.info("FortCraft: combat bonus={} weapon={} target={} multiplier={}",
				backstab ? "backstab" : head ? "headshot" : multiplier == 3 ? "crit" : "mini", shot.weapon(), best.getName().getString(), multiplier);
		} else if (melee && block.getType() == HitResult.Type.BLOCK) {
			breakMeleeBlock(minecraft, ((BlockHitResult) block).getBlockPos().immutable(), shot.tool());
		}
	}

	private static boolean headHit(Entity target, Vec3 hit) {
		if (target instanceof EnderDragonPart part) return part.name.equals("head");
		if (!(target instanceof LivingEntity living)) return false;
		// These mobs have no distinct head. Other mobs use an eye-centred approximation.
		var type = target.getType();
		if (type == EntityTypes.SLIME || type == EntityTypes.MAGMA_CUBE || type == EntityTypes.GHAST
			|| type == EntityTypes.SQUID || type == EntityTypes.GLOW_SQUID) return false;
		AABB box = target.getBoundingBox();
		double radius = Math.max(0.12, Math.min(0.35, box.getYsize() * 0.18));
		return Math.abs(hit.y - living.getEyeY()) <= radius;
	}

	private static boolean behind(Entity target, Vec3 start, Vec3 aim) {
		if (!(target instanceof LivingEntity living) || target instanceof EnderDragon) return false;
		Vec3 to = target.position().subtract(start).multiply(1, 0, 1).normalize();
		Vec3 forward = Vec3.directionFromRotation(0, living.yBodyRot);
		Vec3 facing = aim.multiply(1, 0, 1).normalize();
		return CombatRules.backstab(to.dot(forward), to.dot(facing), forward.dot(facing));
	}

	/** Dragon parts and crystals are not LivingEntity objects in Minecraft. */
	private static List<Entity> combatTargets(Minecraft minecraft, AABB area) {
		List<Entity> targets = new ArrayList<>(minecraft.level.getEntities(minecraft.player, area,
			e -> (e instanceof LivingEntity && e.isAlive() && !(e instanceof EnderDragon))
				|| (e instanceof EndCrystal && !e.isRemoved())));
		for (Entity e : minecraft.level.getEntities(minecraft.player, area.inflate(16.0),
			d -> d instanceof EnderDragon && d.isAlive())) {
			for (EnderDragonPart part : ((EnderDragon) e).getSubEntities()) {
				if (part.getBoundingBox().intersects(area)) {
					targets.add(part);
				}
			}
		}
		return targets;
	}

	private static void breakMeleeBlock(Minecraft minecraft, BlockPos pos, int tool) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level == null || minecraft.player == null) {
			return;
		}
		var dimension = minecraft.level.dimension();
		UUID playerId = minecraft.player.getUUID();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			ServerPlayer attacker = server.getPlayerList().getPlayer(playerId);
			if (level == null || attacker == null || !level.isLoaded(pos)) {
				return;
			}
			var state = level.getBlockState(pos);
			float hardness = state.getDestroySpeed(level, pos);
			if (state.isAir() || hardness < 0) {
				return;
			}
			if (attacker.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
				long now = System.nanoTime();
				if (miningPos == null || !miningPos.equals(pos) || !miningDimension.equals(dimension)
					|| miningBlock != state.getBlock() || miningTool != tool || now - miningLastHit > MINING_RESET_NANOS) {
					clearMiningCracks(server, attacker);
					miningPos = pos;
					miningDimension = dimension;
					miningBlock = state.getBlock();
					miningHits = 0;
					miningTool = tool;
				}
				miningLastHit = now;
				miningHits++;
				// Soft earth breaks in one swing; stone (hardness 1.5) takes three.
				int required = CombatRules.miningHits(hardness, tool, state.is(BlockTags.MINEABLE_WITH_SHOVEL),
					state.is(BlockTags.MINEABLE_WITH_PICKAXE), state.is(BlockTags.MINEABLE_WITH_AXE));
				FortCraft.LOG.info("FortCraft: survival melee {} at {} hit {}/{} tool={}", state.getBlock().getName().getString(),
					pos, miningHits, required, tool);
				if (miningHits < required) {
					// Minecraft omits the breaker from this packet. TF2 owns the swing, so use a
					// dedicated non-player ID to let the local player see the vanilla cracks.
					level.destroyBlockProgress(MINING_BREAKER_ID, pos, Math.min(9, miningHits * 10 / required));
					return;
				}
				clearMiningCracks(server, attacker);
			} else if (attacker.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
				return;
			} else {
				clearMiningCracks(server, attacker);
			}
			if (level.destroyBlock(pos, true, attacker)) {
				FortCraft.LOG.info("FortCraft: melee broke block {} at {}", state.getBlock().getName().getString(), pos);
			}
		});
	}

	private static final long MINING_RESET_NANOS = 3_000_000_000L;
	private static final int MINING_BREAKER_ID = -2;
	private static int miningCleanupFrames;
	private static BlockPos miningPos;
	private static net.minecraft.resources.ResourceKey<Level> miningDimension;
	private static net.minecraft.world.level.block.Block miningBlock;
	private static long miningLastHit;
	private static int miningHits;
	private static int miningTool;

	private static void clearMiningCracks(IntegratedServer server, ServerPlayer attacker) {
		if (miningPos != null) {
			ServerLevel oldLevel = server.getLevel(miningDimension);
			if (oldLevel != null) {
				oldLevel.destroyBlockProgress(MINING_BREAKER_ID, miningPos, -1);
			}
		}
		miningPos = null;
		miningBlock = null;
		miningHits = 0;
	}

	private static void blast(Minecraft minecraft, Vec3 centre, float reportedRadius, float baseDamage, int flags) {
		if (reportedRadius <= 0) {
			FortCraft.LOG.info("FortCraft: harmless blast at {} (jumper or fire weapon): no mob or block damage", centre);
			return;
		}
		float radius = Math.min(reportedRadius, 16.0f);
		AABB area = new AABB(centre, centre).inflate(radius);
		Set<Integer> hitDragons = new HashSet<>();
		for (Entity e : combatTargets(minecraft, area)) {
			double d = e.getBoundingBox().getCenter().distanceTo(centre);
			if (d <= radius) {
				if (e instanceof EnderDragonPart part && !hitDragons.add(part.parentMob.getId())) {
					continue; // One explosion should not multiply damage by the number of dragon parts.
				}
				// TF2's falloff: full damage at the centre, half at the edge.
				Vec3 target = e.getBoundingBox().getCenter();
				if (minecraft.level.clip(new ClipContext(centre, target, ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, minecraft.player)).getType() != HitResult.Type.MISS) continue;
				hurt(minecraft, e, baseDamage * CombatRules.multiplier(flags, false) * (float) (1.0 - 0.5 * d / radius), DAMAGE_SCALE, false);
			}
		}
		breakBlastTerrain(minecraft, centre, Math.min(reportedRadius, TERRAIN_BLAST_RADIUS));
	}

	private static void breakBlastTerrain(Minecraft minecraft, Vec3 centre, double radius) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level == null || minecraft.player == null || radius <= 0) {
			return;
		}
		var dimension = minecraft.level.dimension();
		UUID playerId = minecraft.player.getUUID();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			ServerPlayer attacker = server.getPlayerList().getPlayer(playerId);
			if (level == null || attacker == null) {
				return;
			}
			var candidates = new ArrayList<BlockPos>();
			int x0 = (int) Math.floor(centre.x - radius), x1 = (int) Math.floor(centre.x + radius);
			int y0 = (int) Math.floor(centre.y - radius), y1 = (int) Math.floor(centre.y + radius);
			int z0 = (int) Math.floor(centre.z - radius), z1 = (int) Math.floor(centre.z + radius);
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					for (int x = x0; x <= x1; x++) {
						double dx = x + 0.5 - centre.x, dy = y + 0.5 - centre.y, dz = z + 0.5 - centre.z;
						if (dx * dx + dy * dy + dz * dz <= radius * radius) {
							candidates.add(new BlockPos(x, y, z));
						}
					}
				}
			}
			candidates.sort(Comparator.comparingDouble(p -> {
				double dx = p.getX() + 0.5 - centre.x, dy = p.getY() + 0.5 - centre.y, dz = p.getZ() + 0.5 - centre.z;
				return dx * dx + dy * dy + dz * dz;
			}));
			int broken = 0;
			for (BlockPos pos : candidates) {
				if (broken >= MAX_BLAST_BLOCKS) {
					break;
				}
				if (!level.isLoaded(pos)) {
					continue;
				}
				var state = level.getBlockState(pos);
				if (!state.isAir() && state.getDestroySpeed(level, pos) >= 0 && level.destroyBlock(pos, true, attacker)) {
					broken++;
				}
			}
			FortCraft.LOG.info("FortCraft: terrain blast at {} radius {} broke {} blocks", centre,
				String.format("%.1f", radius), broken);
		});
	}

	private static void hurt(Minecraft minecraft, Entity clientTarget, float tf2Damage, float scale, boolean backstab) {
		hurt(minecraft, clientTarget, tf2Damage, scale, backstab, 0);
	}

	/** Also used by Bleed for its damage ticks. effects: SENTRY and bleed seconds from the shot. */
	static void hurt(Minecraft minecraft, Entity clientTarget, float tf2Damage, float scale, boolean backstab, int effects) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level == null || minecraft.player == null) {
			return;
		}
		var dimension = minecraft.level.dimension();
		UUID playerId = minecraft.player.getUUID();
		int entityId = clientTarget instanceof EnderDragonPart part ? part.parentMob.getId() : clientTarget.getId();
		String partName = clientTarget instanceof EnderDragonPart part ? part.name : null;
		float amount = tf2Damage / scale;
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			ServerPlayer attacker = server.getPlayerList().getPlayer(playerId);
			Entity target = level == null ? null : level.getEntity(entityId);
			if (target == null || attacker == null) {
				return;
			}
			Entity hit = target;
			if (partName != null) {
				if (!(target instanceof EnderDragon dragon) || !dragon.isAlive()) {
					return;
				}
				hit = null;
				for (EnderDragonPart part : dragon.getSubEntities()) {
					if (part.name.equals(partName)) {
						hit = part;
					break;
					}
				}
			}
			if (hit == null || (hit instanceof LivingEntity living && !living.isAlive())
				|| (hit instanceof EndCrystal && hit.isRemoved())) {
				return;
			}
			float applied = backstab && hit instanceof LivingEntity living ? Math.max(amount, living.getHealth() * 6.0f) : amount;
			boolean damaged = hit.hurtServer(level, level.damageSources().playerAttack(attacker), applied);
			if (damaged) {
				var box = hit.getBoundingBox();
				boolean killed = hit instanceof EndCrystal || (target instanceof LivingEntity living && !living.isAlive());
				FortLink.writeMobHit(box.getCenter().x, box.maxY, box.getCenter().z, applied * scale, killed,
					effects & CombatRules.SENTRY);
				int bleed = CombatRules.bleedSeconds(effects);
				if (bleed > 0 && !killed && target instanceof LivingEntity) {
					Bleed.start(target.getId(), bleed);
				}
			}
			FortCraft.LOG.info("FortCraft: TF2 hit {}{} for {} ({} TF2 damage), accepted={}",
				target.getName().getString(), partName == null ? "" : " part " + partName,
				String.format("%.1f", applied), String.format("%.0f", applied * scale), damaged);
		});
	}
}
