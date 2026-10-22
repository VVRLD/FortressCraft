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
	// Minecraft damage = TF2 damage / 5 for everything (bullets, melee, blasts), with TF2's own
	// range falloff, crits and headshots. A zombie (20 health) is then a 100-health TF2 target:
	// about 5 close pistol shots, 2 shotgun blasts, 2 shovel hits, 1 crit (Alex, 2026-10-10:
	// melee and pistols one-shot zombies at the old /3 and /1.5).
	public static final float DAMAGE_SCALE = 5.0f;
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

	/** True while the Spy is cloaked; read by PlayerMixin on the server thread (mobs ignore you). */
	public static volatile boolean cloaked;

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
		MvmSave.tick(minecraft);
		jarParticles(minecraft);
		dispenserIron(minecraft);
		raidState(minecraft);
		healFriendlyMob(minecraft, buttons);
		dripDragonRain(minecraft);
		FortLink.Tf2Camera cam = FortLink.readCamera();
		if (cam != null) {
			FortLink.writeMobBackstab(!cam.thirdPerson() && !cam.uiOpen() && backstabReady(minecraft, cam));
			updateCloak(minecraft, cam.cloaked());
			clearEffectsOnDeath(minecraft, cam.dead());
		}
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
		sentryEyes.clear();
		List<FortLink.Tf2Building> buildings = FortLink.readBuildings();
		if (buildings != null) {
			for (FortLink.Tf2Building b : buildings) {
				if (b.sentry()) {
					sentryEyes.add(new Vec3((b.minX() + b.maxX()) / 2, b.maxY() - 0.25, (b.minZ() + b.maxZ()) / 2));
				}
			}
		}
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

	/**
	 * True if one of the player's sentries has a clear line to the mob's middle or eyes through
	 * Minecraft's world. TF2 only knows the blocks near the player, so sentries shot mobs behind
	 * houses further away (Alex, 2026-10-10).
	 */
	private static boolean sentrySees(Minecraft minecraft, Entity e) {
		Vec3 centre = e.getBoundingBox().getCenter();
		Vec3 eyes = e.getEyePosition();
		for (Vec3 eye : sentryEyes) {
			if (eye.distanceToSqr(centre) > SENTRY_RANGE * SENTRY_RANGE) {
				continue;
			}
			for (Vec3 to : new Vec3[] { centre, eyes }) {
				HitResult hit = minecraft.level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
				if (hit.getType() == HitResult.Type.MISS) {
					return true;
				}
			}
		}
		return false;
	}

	private static final List<Vec3> sentryEyes = new ArrayList<>();
	private static final double SENTRY_RANGE = 1100.0 / 48.0 + 1.0;  // TF2's 1100-unit sentry range

	private static int addMobBox(Entity e, int n, boolean hostile) {
		AABB b = e.getBoundingBox();
		int o = n * 6;
		mobBoxes[o] = (float) b.minX;
		mobBoxes[o + 1] = (float) b.minY;
		mobBoxes[o + 2] = (float) b.minZ;
		mobBoxes[o + 3] = (float) b.maxX;
		mobBoxes[o + 4] = (float) b.maxY;
		mobBoxes[o + 5] = (float) b.maxZ;
		mobHostile[n] = (byte) ((hostile ? 1 : 0) | (hostile && sentrySees(Minecraft.getInstance(), e) ? 2 : 0)
			| (Robots.kindOf(e) != Robots.NONE ? 4 : 0));  // drawn by TF2 as a robot
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
			AABB box = e.getBoundingBox().inflate(melee ? MELEE_BOX_GROW : 0.1);
			// Right up against a mob the shot starts inside its box, where clip() finds nothing:
			// the shot went on to the block behind (melee mined it). Count it as a hit at once.
			if (box.contains(start)) {
				// Where the aim passes the mob's middle, for the headshot check.
				double along = Math.max(0, box.getCenter().subtract(start).dot(dir));
				bestDist = 0;
				best = e;
				bestHit = start.add(dir.scale(along));
				continue;
			}
			var hit = box.clip(start, end);
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
			// Jarate on the mob: mini-crits, as on a jarated TF2 player.
			int shotFlags = shot.flags() | (isJarated(best) && (shot.flags() & CombatRules.CRIT) == 0 ? CombatRules.MINI : 0);
			float multiplier = CombatRules.multiplier(shotFlags, head);
			float falloff = CombatRules.rangeModifier(bestDist, shotFlags, head);
			int effects = shot.flags() & (CombatRules.SENTRY | (0xFF << CombatRules.BLEED_SHIFT));
			// For TF2's crit sound and text over the mob.
			if (backstab || multiplier == 3.0f) {
				effects |= CombatRules.CRIT;
			} else if (multiplier > 1.0f) {
				effects |= CombatRules.MINI;
			}
			shotDamage.merge(new ShotGroup(best, DAMAGE_SCALE, backstab, effects), shot.damage() * multiplier * falloff, Float::sum);
			if (backstab || multiplier > 1) FortCraft.LOG.info("FortCraft: combat bonus={} weapon={} target={} multiplier={}",
				backstab ? "backstab" : head ? "headshot" : multiplier == 3 ? "crit" : "mini", shot.weapon(), best.getName().getString(), multiplier);
			int explosive = CombatRules.explosiveHeadshotLevel(shot.flags());
			if (head && explosive > 0) {
				explosiveHeadshot(minecraft, best, explosive);
			}
		} else if (melee) {
			// Blocks you can walk through (cobwebs, grass, flowers) have no collision box, so the
			// swing went straight through them (Alex, 2026-10-10: couldn't break cobwebs). Check
			// their outline too, and hit the nearer of the two.
			HitResult outline = minecraft.level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, minecraft.player));
			HitResult target = block;
			if (outline.getType() == HitResult.Type.BLOCK && (block.getType() != HitResult.Type.BLOCK
				|| start.distanceTo(outline.getLocation()) < start.distanceTo(block.getLocation()))) {
				target = outline;
			}
			if (target.getType() == HitResult.Type.BLOCK) {
				breakMeleeBlock(minecraft, ((BlockHitResult) target).getBlockPos().immutable(), shot.tool());
			}
		} else if ((shot.flags() & CombatRules.SENTRY) == 0 && shot.weapon() != 0 && block.getType() == HitResult.Type.BLOCK) {
			// A bullet, pellet or rifle shot that hit a block (not a mob): it wears the block down.
			// Each bullet or pellet takes its share of a block. Stray shots in a fight broke too many
			// blocks (Alex, 2026-10-11), so it takes 10-20 shots: shotguns and the sniper rifle 10,
			// SMGs, pistols and the rest 15, miniguns 20 (4 bullets a shot). Explosives are separate
			// and still break blocks at full strength.
			int w = shot.weapon();
			boolean shotgun = w >= TF_WEAPON_SHOTGUN_PRIMARY && w <= TF_WEAPON_SCATTERGUN;
			int shots = shotgun || w == TF_WEAPON_SNIPERRIFLE ? 10 : w == TF_WEAPON_MINIGUN ? 20 : 15;
			int bulletsPerShot = shotgun ? 10 : w == TF_WEAPON_MINIGUN ? 4 : 1;
			shootBlock(minecraft, ((BlockHitResult) block).getBlockPos().immutable(), 1.0f / (shots * bulletsPerShot));
		}
	}

	/**
	 * Gunfire breaks blocks (Alex, 2026-10-11: "a smg takes like 6 shots to break, a shotgun takes 2
	 * shots, a sniper takes 1 shot"). Each bullet, pellet or rifle shot that hits a block adds its
	 * TF2 damage (after range falloff) to that block. The block breaks at 48 for stone, scaled by
	 * its hardness (dirt, sand and glass sooner, wood and ores later, at most double). Cracks show
	 * how close it is, and 30 s without a hit leaves it whole again. Melee keeps its own mining;
	 * sentries, flames and explosions don't count. Survival drops the block; creative too.
	 */
	private static void shootBlock(Minecraft minecraft, BlockPos pos, float damage) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level == null || minecraft.player == null || damage <= 0) {
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
			GameType mode = attacker.gameMode.getGameModeForPlayer();
			if (mode != GameType.SURVIVAL && mode != GameType.CREATIVE) {
				return;
			}
			var state = level.getBlockState(pos);
			float hardness = state.getDestroySpeed(level, pos);
			if (state.isAir() || hardness < 0) {
				return;  // bedrock and the like
			}
			long now = System.nanoTime();
			gunfireDamage.entrySet().removeIf(e -> {
				if (now - e.getValue()[1] > GUNFIRE_RESET_NANOS) {
					level.destroyBlockProgress(gunfireBreaker(e.getKey()), e.getKey(), -1);
					return true;
				}
				return false;
			});
			// A stone-like block is 1.0; harder ones (ores, obsidian) take up to twice as long.
			float needed = Math.max(1.0f, Math.min(hardness / 1.5f, 2.0f));
			long[] entry = gunfireDamage.computeIfAbsent(pos, k -> new long[] { 0, now });
			float total = Float.intBitsToFloat((int) entry[0]) + damage;
			entry[0] = Float.floatToIntBits(total);
			entry[1] = now;
			if (total < needed) {
				level.destroyBlockProgress(gunfireBreaker(pos), pos, Math.min(9, (int) (total * 10 / needed)));
				return;
			}
			gunfireDamage.remove(pos);
			level.destroyBlockProgress(gunfireBreaker(pos), pos, -1);
			if (level.destroyBlock(pos, mode == GameType.SURVIVAL, attacker)) {
				FortCraft.LOG.info("FortCraft: gunfire broke {} at {}", state.getBlock().getName().getString(), pos);
			}
		});
	}

	// TF2's ETFWeaponType: 12-16 are the shotguns and scatterguns.
	private static final int TF_WEAPON_SHOTGUN_PRIMARY = 12, TF_WEAPON_SCATTERGUN = 16, TF_WEAPON_SNIPERRIFLE = 17, TF_WEAPON_MINIGUN = 18;

	/** Server thread: block -> {damage so far (float bits), last hit (nanoTime)}. */
	private static final Map<BlockPos, long[]> gunfireDamage = new java.util.HashMap<>();
	private static final long GUNFIRE_RESET_NANOS = 30_000_000_000L;  // 30 s without a hit: whole again

	/** A crack-drawing id per block (Minecraft draws one crack per breaker id). */
	private static int gunfireBreaker(BlockPos pos) {
		return -1000 - (pos.hashCode() & 0x3FFF);
	}

	/** The mob TF2's knife would hit from the camera now, if its back is turned (raised knife). */
	private static boolean backstabReady(Minecraft minecraft, FortLink.Tf2Camera cam) {
		Vec3 start = new Vec3(cam.x(), cam.y(), cam.z());
		Vec3 dir = Vec3.directionFromRotation(cam.pitch(), cam.yaw());
		Vec3 end = start.add(dir.scale(MELEE_REACH));
		HitResult block = minecraft.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
		double bestDist = block.getType() == HitResult.Type.MISS ? MELEE_REACH : start.distanceTo(block.getLocation());
		Entity best = null;
		for (Entity e : combatTargets(minecraft, new AABB(start, end).inflate(1.0))) {
			AABB box = e.getBoundingBox().inflate(MELEE_BOX_GROW);
			double d = box.contains(start) ? 0 : box.clip(start, end).map(start::distanceTo).orElse(Double.MAX_VALUE);
			if (d < bestDist) {
				bestDist = d;
				best = e;
			}
		}
		return best != null && behind(best, start, dir);
	}

	/**
	 * Cloaked Spy: mobs drop you as a target and can't pick you again until you show (PlayerMixin).
	 */
	private static void updateCloak(Minecraft minecraft, boolean now) {
		if (now == cloaked) {
			return;
		}
		cloaked = now;
		FortCraft.LOG.info("FortCraft: Spy {}", now ? "cloaked: mobs ignore you" : "visible again");
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (!now || server == null || minecraft.player == null) {
			return;
		}
		var dimension = minecraft.level.dimension();
		UUID playerId = minecraft.player.getUUID();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (level == null || player == null) {
				return;
			}
			for (net.minecraft.world.entity.Mob mob : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
				player.getBoundingBox().inflate(64.0), m -> m.getTarget() == player)) {
				mob.setTarget(null);
			}
		});
	}

	private static boolean headHit(Entity target, Vec3 hit) {
		if (target instanceof EnderDragonPart part) return part.name.equals("head");
		if (!(target instanceof LivingEntity living)) return false;
		AABB box = target.getBoundingBox();
		// Mobs that are all head (slimes, ghasts, squid): the top third counts.
		var type = target.getType();
		if (type == EntityTypes.SLIME || type == EntityTypes.MAGMA_CUBE || type == EntityTypes.GHAST
			|| type == EntityTypes.SQUID || type == EntityTypes.GLOW_SQUID) {
			return hit.y >= box.maxY - box.getYsize() / 3.0;
		}
		double radius = Math.max(0.12, Math.min(0.35, box.getYsize() * 0.18));
		if (Math.abs(hit.y - living.getEyeY()) > radius) return false;
		// Long, low mobs (cows, spiders, wolves...) carry the head at the front: the back half
		// at eye height is not the head.
		if (box.getXsize() > box.getYsize() * 0.9) {
			Vec3 forward = Vec3.directionFromRotation(0, living.yBodyRot);
			Vec3 off = hit.subtract(box.getCenter()).multiply(1, 0, 1);
			return off.dot(forward) > 0;
		}
		return true;
	}

	/** Every mob: the dragon by where its head is, the rest by the way their body faces. */
	private static boolean behind(Entity target, Vec3 start, Vec3 aim) {
		Vec3 forward;
		if (target instanceof EnderDragonPart part) {
			EnderDragon dragon = part.parentMob;
			EnderDragonPart headPart = null;
			for (EnderDragonPart p : dragon.getSubEntities()) {
				if (p.name.equals("head")) {
					headPart = p;
				}
			}
			if (headPart == null) return false;
			forward = headPart.getBoundingBox().getCenter().subtract(dragon.getBoundingBox().getCenter());
			target = dragon;
		} else if (target instanceof LivingEntity living) {
			forward = Vec3.directionFromRotation(0, living.yBodyRot);
		} else {
			return false;  // end crystals have no back
		}
		forward = forward.multiply(1, 0, 1).normalize();
		Vec3 to = target.getBoundingBox().getCenter().subtract(start).multiply(1, 0, 1).normalize();
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

	// Jarate / Mad Milk on mobs: entity id -> when it wears off (nanoTime). Read on both threads.
	private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> jarated = new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> milked = new java.util.concurrent.ConcurrentHashMap<>();
	private static final int JARATE = 1 << 6, MILK = 1 << 7, GAS = 1 << 8;  // protocol kExplosion*
	private static final long JAR_NANOS = 10_000_000_000L;  // TF2's 10 s

	static boolean isJarated(Entity e) {
		Long until = jarated.get(e.getId());
		return until != null && until > System.nanoTime();
	}

	static boolean isMilked(Entity e) {
		Long until = milked.get(e.getId());
		return until != null && until > System.nanoTime();
	}

	/**
	 * A thrown jar's splash on Minecraft's mobs (Alex, 2026-10-10): Jarate makes your hits on them
	 * mini-crits for 10 s, Mad Milk heals you 60% of the damage you deal them for 10 s, Gas
	 * Passer sets them on fire. Only mobs the splash can reach (no block in between).
	 */
	private static void splash(Minecraft minecraft, Vec3 centre, float radius, int flags, float explodeDamage) {
		long until = System.nanoTime() + JAR_NANOS;
		int n = 0;
		for (Entity e : combatTargets(minecraft, new AABB(centre, centre).inflate(radius))) {
			if (!(e instanceof LivingEntity) || e.getBoundingBox().getCenter().distanceTo(centre) > radius
				|| minecraft.level.clip(new ClipContext(centre, e.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, minecraft.player)).getType() != HitResult.Type.MISS) {
				continue;
			}
			if ((flags & JARATE) != 0) {
				jarated.put(e.getId(), until);
			}
			if ((flags & MILK) != 0) {
				milked.put(e.getId(), until);
			}
			n++;
			if ((flags & GAS) != 0) {
				int id = e.getId();
				IntegratedServer server = minecraft.getSingleplayerServer();
				var dimension = minecraft.level.dimension();
				if (server != null) {
					server.execute(() -> {
						ServerLevel level = server.getLevel(dimension);
						Entity mob = level == null ? null : level.getEntity(id);
						if (mob != null) {
							mob.igniteForSeconds(6.0f);
						}
					});
				}
				// MvM "explode on ignite" upgrade (Alex, 2026-10-12): the gas catches fire at once
				// on mobs, so they explode at once, for TF2's 350 fire damage each.
				if (explodeDamage > 0) {
					hurt(minecraft, e, explodeDamage, DAMAGE_SCALE, false, CombatRules.GAS_EXPLOSION);
				}
			}
		}
		FortCraft.LOG.info("FortCraft: {} splashed {} mobs", (flags & JARATE) != 0 ? "Jarate" : (flags & MILK) != 0 ? "Mad Milk" : "Gas Passer", n);
	}

	/**
	 * MvM Explosive Headshot (Alex, 2026-10-12), as TF2's CTFSniperRifle::ExplosiveHeadShot: a
	 * headshot stuns (slows) the mob and blasts every other mob within 125 + 25 x level units that
	 * it can see, for 130 + 20 x level damage, slowing them too.
	 */
	private static void explosiveHeadshot(Minecraft minecraft, Entity victim, int level) {
		Vec3 centre = victim.getBoundingBox().getCenter();
		float radius = (125.0f + 25.0f * level) / 48.0f;  // TF2 units per block
		float damage = 130.0f + 20.0f * level;
		int stunTicks = Math.round((1.0f + (level - 1) * 0.5f) * 20.0f);
		float stun = Math.min(0.8f, 0.5f + (level - 1) * 0.15f);
		slow(minecraft, victim, stunTicks, stun);
		int n = 0;
		for (Entity e : combatTargets(minecraft, new AABB(centre, centre).inflate(radius))) {
			if (e == victim || !(e instanceof LivingEntity) || e.getBoundingBox().getCenter().distanceTo(centre) > radius
				|| minecraft.level.clip(new ClipContext(centre, e.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, minecraft.player)).getType() != HitResult.Type.MISS) {
				continue;
			}
			slow(minecraft, e, stunTicks, stun);
			hurt(minecraft, e, damage, DAMAGE_SCALE, false, CombatRules.EXPLOSIVE_HIT);
			n++;
		}
		FortCraft.LOG.info("FortCraft: explosive headshot level {} on {}: {} mobs blasted for {}", level,
			victim.getName().getString(), n, damage);
	}

	/** TF2's movement stun on a mob: Slowness for that long (amount 0.5 = 50% slower). */
	private static void slow(Minecraft minecraft, Entity clientMob, int ticks, float amount) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level == null) {
			return;
		}
		int id = clientMob.getId();
		var dimension = minecraft.level.dimension();
		int amplifier = Math.max(0, Math.round(amount / 0.15f) - 1);  // Slowness: 15% a level
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			if (level != null && level.getEntity(id) instanceof LivingEntity living) {
				living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, amplifier, false, false));
			}
		});
	}

	/** Jarate and Mad Milk drip from the mobs wearing them (every half second). */
	private static void jarParticles(Minecraft minecraft) {
		if (++jarParticleFrames % 15 != 0 || (jarated.isEmpty() && milked.isEmpty())) {
			return;
		}
		long now = System.nanoTime();
		jarated.values().removeIf(t -> t <= now);
		milked.values().removeIf(t -> t <= now);
		for (var entry : jarated.entrySet()) {
			Entity e = minecraft.level.getEntity(entry.getKey());
			if (e != null) {
				AABB b = e.getBoundingBox();
				minecraft.level.addParticle(ParticleTypes.FALLING_HONEY, b.getCenter().x, b.maxY, b.getCenter().z, 0, 0, 0);
			}
		}
		for (var entry : milked.entrySet()) {
			Entity e = minecraft.level.getEntity(entry.getKey());
			if (e != null) {
				AABB b = e.getBoundingBox();
				minecraft.level.addParticle(ParticleTypes.SNOWFLAKE, b.getCenter().x, b.maxY, b.getCenter().z, 0, -0.05, 0);
			}
		}
	}

	private static int jarParticleFrames;

	/**
	 * Standing at your own working dispenser tops your Minecraft iron up to one stack, an ingot a
	 * second (Alex, 2026-10-10). TF2's own metal and ammo refill as usual.
	 */
	private static void dispenserIron(Minecraft minecraft) {
		if (++dispenserFrames % 60 != 0 || minecraft.player == null) {
			return;
		}
		List<FortLink.Tf2Building> buildings = FortLink.readBuildings();
		if (buildings == null) {
			return;
		}
		Vec3 me = minecraft.player.position();
		boolean near = false;
		for (FortLink.Tf2Building b : buildings) {
			if (b.dispenser() && new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(1.5).contains(me)) {
				near = true;
			}
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (!near || server == null) {
			return;
		}
		UUID id = minecraft.player.getUUID();
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player == null || player.isCreative()) {
				return;
			}
			var inv = player.getInventory();
			int iron = 0;
			for (int slot = 0; slot < 36; slot++) {
				if (inv.getItem(slot).is(net.minecraft.world.item.Items.IRON_INGOT)) {
					iron += inv.getItem(slot).getCount();
				}
			}
			if (iron < 64) {
				inv.placeItemBackInInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT),
					net.minecraft.util.Prediction.SERVER_ONLY);
				inv.setChanged();
			}
		});
	}

	private static int dispenserFrames;

	/**
	 * Your TF2 merc dying clears Minecraft's potion effects (and fire) on the player, as dying in
	 * Minecraft would (Alex, 2026-10-11). TF2 owns your health, so Minecraft's player never dies.
	 */
	private static void clearEffectsOnDeath(Minecraft minecraft, boolean dead) {
		if (dead == tf2Dead) {
			return;
		}
		tf2Dead = dead;
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (!dead || server == null || minecraft.player == null) {
			return;
		}
		UUID id = minecraft.player.getUUID();
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) {
				player.removeAllEffects();
				player.clearFire();
				FortCraft.LOG.info("FortCraft: TF2 merc died; Minecraft effects cleared");
			}
		});
	}

	private static boolean tf2Dead;

	/**
	 * A village raid near the player (96 blocks), told to TF2 once a second, which plays MvM's
	 * wave music and announcer for it (Alex, 2026-10-11).
	 */
	private static void raidState(Minecraft minecraft) {
		if (++raidFrames % 60 != 0 || minecraft.player == null) {
			return;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		var dimension = minecraft.level.dimension();
		BlockPos pos = minecraft.player.blockPosition();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			if (level != null && dimension == dev.fortcraft.mvm.GhostTown.LEVEL) {
				// MvM waves in the Ghost Town use TF2's wave panel, music and announcer too.
				dev.fortcraft.mvm.GhostTownWaves.writePanel(level);
				return;
			}
			var raid = level == null ? null : level.getRaids().getNearbyRaid(pos, 96 * 96);
			int state = raid == null ? 0 : raid.isVictory() ? 2 : raid.isLoss() ? 3 : raid.isStarted() && !raid.isStopped() ? 1 : 0;
			int wave = raid == null ? 0 : raid.getGroupsSpawned();
			int total = raid == null ? 0 : raid.getNumGroups(level.getDifficulty());
			int alive = raid == null ? 0 : raid.getTotalRaidersAlive();
			if (state == 2 && lastRaidState == 1) {
				// Raid defended: $500 at the player's feet, picked up by the cash magnet (Alex, 2026-10-11).
				FortLink.writeMobHit(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0.0f, true, (1 << 7) | (500 << 16));
				FortCraft.LOG.info("FortCraft: village raid defended, $500 reward");
			}
			if (state == 1 && wave > lastRaidWave && wave > 0) {
				Robots.reinforce(level, raid, wave);  // a few more robots each wave (Alex: "more mobs")
			}
			if (state != lastRaidState || wave != lastRaidWave) {
				lastRaidState = state;
				lastRaidWave = wave;
				FortCraft.LOG.info("FortCraft: village raid state {} wave {}/{} ({} raiders)", state, wave, total, alive);
			}
			FortLink.writeRaidState(state, wave, total, alive, raid == null ? new byte[48] : Robots.countKinds(raid.getAllRaiders()));
		});
	}

	private static int raidFrames;
	private static volatile int lastRaidState, lastRaidWave;

	/** True while a village raid near the player is under way (damage to the player is halved). */
	public static boolean raidUnderWay() {
		return lastRaidState == 1;
	}

	private static void blast(Minecraft minecraft, Vec3 centre, float reportedRadius, float baseDamage, int flags) {
		if ((flags & (JARATE | MILK | GAS)) != 0) {
			splash(minecraft, centre, reportedRadius, flags, baseDamage);
			return;
		}
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
				hurt(minecraft, e, baseDamage * CombatRules.multiplier(flags, false) * (float) (1.0 - 0.5 * d / radius), DAMAGE_SCALE, false,
					(flags & CombatRules.CRIT) != 0 ? CombatRules.CRIT : 0);
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

	/**
	 * MvM money a kill earns, by how hard the mob is, like Minecraft's XP (Alex, 2026-10-10):
	 * a pig $5-10, a zombie $10-20, a skeleton $20-30, up to the dragon's $20,000 (dropped as many
	 * bags). Aim: an average player reaching the End can max out about their merc and three
	 * weapons, not everything; ten minutes of zombies doesn't max a weapon. Unlisted hostile
	 * mobs pay about their max health (slimes by size), unlisted animals $5-10.
	 */
	private static final Map<String, int[]> MONEY = Map.ofEntries(
		// passive and neutral
		Map.entry("wolf", new int[] { 10, 20 }), Map.entry("bee", new int[] { 10, 20 }),
		Map.entry("llama", new int[] { 10, 20 }), Map.entry("trader_llama", new int[] { 10, 20 }),
		Map.entry("polar_bear", new int[] { 20, 30 }), Map.entry("goat", new int[] { 10, 20 }),
		Map.entry("panda", new int[] { 15, 25 }), Map.entry("dolphin", new int[] { 10, 20 }),
		Map.entry("iron_golem", new int[] { 100, 150 }),
		// overworld hostiles
		Map.entry("zombie", new int[] { 10, 20 }), Map.entry("husk", new int[] { 10, 20 }),
		Map.entry("drowned", new int[] { 15, 25 }), Map.entry("zombie_villager", new int[] { 10, 20 }),
		Map.entry("silverfish", new int[] { 5, 10 }), Map.entry("endermite", new int[] { 5, 10 }),
		Map.entry("skeleton", new int[] { 20, 30 }), Map.entry("stray", new int[] { 20, 30 }),
		Map.entry("bogged", new int[] { 20, 30 }), Map.entry("spider", new int[] { 15, 25 }),
		Map.entry("cave_spider", new int[] { 15, 25 }), Map.entry("creeper", new int[] { 25, 35 }),
		Map.entry("witch", new int[] { 40, 60 }), Map.entry("phantom", new int[] { 30, 45 }),
		Map.entry("pillager", new int[] { 30, 45 }), Map.entry("vindicator", new int[] { 40, 60 }),
		Map.entry("vex", new int[] { 10, 20 }), Map.entry("evoker", new int[] { 150, 200 }),
		Map.entry("ravager", new int[] { 150, 250 }), Map.entry("guardian", new int[] { 40, 60 }),
		Map.entry("elder_guardian", new int[] { 600, 800 }), Map.entry("breeze", new int[] { 60, 90 }),
		Map.entry("creaking", new int[] { 40, 60 }),
		// nether
		Map.entry("zombified_piglin", new int[] { 20, 30 }), Map.entry("piglin", new int[] { 25, 35 }),
		Map.entry("piglin_brute", new int[] { 80, 120 }), Map.entry("hoglin", new int[] { 40, 60 }),
		Map.entry("zoglin", new int[] { 40, 60 }), Map.entry("blaze", new int[] { 50, 75 }),
		Map.entry("ghast", new int[] { 50, 75 }), Map.entry("wither_skeleton", new int[] { 60, 90 }),
		// end
		Map.entry("enderman", new int[] { 40, 60 }), Map.entry("shulker", new int[] { 50, 75 }),
		// bosses
		Map.entry("warden", new int[] { 1000, 1500 }), Map.entry("wither", new int[] { 2500, 3000 }),
		Map.entry("ender_dragon", new int[] { 20000, 20000 }));

	/**
	 * The ender dragon's money (Alex, 2026-10-10): 300 bags drizzled down over 60 seconds like real
	 * rain, scattered 6-20 blocks around the exit portal, each onto real ground (never into the
	 * portal or the void). They last 5 minutes. Server thread starts it; the client frame drips it.
	 */
	private static final int RAIN_BAGS = 300;
	private static final long RAIN_NANOS = 60_000_000_000L;
	private static volatile int rainMoney;
	private static int rainBagsLeft, rainPerBag, rainExtra;
	private static long rainStart;

	private static void startDragonRain(int money) {
		rainMoney = money;
		FortCraft.LOG.info("FortCraft: the dragon's ${} starts raining down as {} bags", money, RAIN_BAGS);
	}

	private static void dripDragonRain(Minecraft minecraft) {
		int money = rainMoney;
		if (money > 0) {
			rainMoney = 0;
			rainBagsLeft = RAIN_BAGS;
			rainPerBag = money / RAIN_BAGS;
			rainExtra = money % RAIN_BAGS;
			rainStart = System.nanoTime();
		}
		if (rainBagsLeft <= 0 || minecraft.level == null) {
			return;
		}
		// How many should have fallen by now, spread evenly over the 30 s.
		long elapsed = System.nanoTime() - rainStart;
		int due = (int) Math.min(RAIN_BAGS, (elapsed * RAIN_BAGS) / RAIN_NANOS + 1);
		var random = minecraft.level.getRandom();
		while (RAIN_BAGS - rainBagsLeft < due) {
			rainBagsLeft--;
			int amount = rainPerBag + rainExtra;
			rainExtra = 0;
			for (int attempt = 0; attempt < 12; attempt++) {
				double angle = random.nextDouble() * Math.PI * 2;
				double distance = 6 + random.nextDouble() * 14;
				int x = (int) Math.floor(Math.cos(angle) * distance), z = (int) Math.floor(Math.sin(angle) * distance);
				int top = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				if (top <= minecraft.level.getMinY() + 1) {
					continue;  // void (or not loaded): try another spot
				}
				FortLink.writeMobHit(x + 0.5, top, z + 0.5, 0.0f, true, (1 << 7) | (1 << 8) | (amount << 16));  // money only, land
				break;
			}
		}
	}

	/**
	 * Some mobs sometimes drop a TF2 small ammo pack (Alex, 2026-10-10): skeletons (and strays,
	 * bogged) 30%, wither skeletons 30%, endermen in the End 15%.
	 */
	private static boolean dropsAmmo(LivingEntity mob, ServerLevel level) {
		var type = mob.getType();
		float chance = type == EntityTypes.SKELETON || type == EntityTypes.STRAY || type == EntityTypes.BOGGED ? 0.30f
			: type == EntityTypes.WITHER_SKELETON ? 0.30f
			: type == EntityTypes.ENDERMAN && level.dimension() == Level.END ? 0.15f
			: 0.0f;
		return chance > 0 && mob.getRandom().nextFloat() < chance;
	}

	/** Zombies (and husks, drowned, zombie villagers) drop a TF2 small health kit 20% of the time. */
	private static boolean dropsHealth(LivingEntity mob) {
		var type = mob.getType();
		boolean zombie = type == EntityTypes.ZOMBIE || type == EntityTypes.HUSK || type == EntityTypes.DROWNED
			|| type == EntityTypes.ZOMBIE_VILLAGER;
		return zombie && mob.getRandom().nextFloat() < 0.20f;
	}

	private static boolean dragonDying(Entity entity) {
		return entity instanceof EnderDragon dragon && dragon.getPhaseManager().getCurrentPhase().getPhase()
			== net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.DYING;
	}

	static int mvmMoney(LivingEntity mob) {
		String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
		int[] range = MONEY.get(id);
		if (range == null) {
			if (mob instanceof Enemy) {
				int health = Math.max(1, Math.round(mob.getMaxHealth()));
				range = new int[] { health, health * 3 / 2 };
			} else {
				range = new int[] { 5, 10 };
			}
		}
		int money = range[0] + mob.getRandom().nextInt(range[1] - range[0] + 1);
		return Math.min(0xFFFF, money);
	}

	/** The top of the first solid block at or below the middle of this entity's feet (16 blocks at most). */
	static double floorUnder(ServerLevel level, Entity e) {
		BlockPos.MutableBlockPos p = BlockPos.containing(e.getX(), e.getY() + 0.25, e.getZ()).mutable();
		for (int i = 0; i < 16; i++, p.move(0, -1, 0)) {
			var shape = level.getBlockState(p).getCollisionShape(level, p);
			if (!shape.isEmpty()) {
				double top = p.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
				if (top <= e.getY() + 0.5) return top;
			}
		}
		return e.getY();
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
			float applied = amount;
			if (backstab && target instanceof LivingEntity living) {
				boolean boss = target instanceof EnderDragon || target instanceof net.minecraft.world.entity.boss.wither.WitherBoss
					|| target instanceof net.minecraft.world.entity.monster.warden.Warden
					|| target.getType() == EntityTypes.ELDER_GUARDIAN;
				applied = CombatRules.backstabDamage(amount, living.getHealth(), living.getMaxHealth(), boss);
			}
			// Every TF2 hit counts. Minecraft ignores hits during the half second after one
			// (unless bigger), which swallowed SMG, pistol, minigun and flame hits. Minecraft 26
			// keeps that in LivingEntity.damageCooldownTime (setInvulnerableTime alone missed it).
			if (target instanceof LivingEntity living) {  // for a dragon part: its dragon
				living.setInvulnerableTime(0);
				living.damageCooldownTime = 0;
			}
			if (hit instanceof LivingEntity living) {
				living.setInvulnerableTime(0);
				living.damageCooldownTime = 0;
			}
			boolean dragonWasDying = dragonDying(target);
			boolean damaged = hit.hurtServer(level, level.damageSources().playerAttack(attacker), applied);
			if (damaged) {
				var box = hit.getBoundingBox();
				// The dragon never "dies" on the hit: Minecraft keeps it at 1 health and starts its
				// death animation, so its killing hit is the one that starts that (Alex, 2026-10-10:
				// it dropped no money).
				boolean killed = hit instanceof EndCrystal || (target instanceof LivingEntity living && !living.isAlive())
					|| (!dragonWasDying && dragonDying(target));
				int money = killed && target instanceof LivingEntity dead ? mvmMoney(dead) : 0;
				if (killed && target instanceof EnderDragon) {
					startDragonRain(money);  // its money comes down as rain instead
					money = 0;
				}
				int ammo = killed && target instanceof LivingEntity dropper && dropsAmmo(dropper, level) ? 1 << 9 : 0;  // kMobHitDropAmmo
				int health = killed && target instanceof LivingEntity eater && dropsHealth(eater) ? 1 << 10 : 0;  // kMobHitDropHealth
				int milk = isMilked(target) ? 1 << 11 : 0;  // protocol kMobHitMilk
				int special = ((effects & CombatRules.EXPLOSIVE_HIT) != 0 ? 1 << 12 : 0)  // kMobHitExplosiveHeadshot
					| ((effects & CombatRules.GAS_EXPLOSION) != 0 ? 1 << 13 : 0)  // kMobHitGasExplosion
					| (backstab ? 1 << 14 : 0);  // kMobHitBackstab
				FortLink.writeMobHit(box.getCenter().x, box.maxY, box.getCenter().z, applied * scale, killed,
					(effects & (CombatRules.SENTRY | CombatRules.CRIT | CombatRules.MINI)) | ammo | health | milk | special);
				if (money > 0) {
					// The money on its own, onto the floor under the mob (Alex, 2026-10-20: tossed by
					// TF2's physics it landed inside Minecraft's walls). kMobHitMoneyOnly | MoneyGround.
					FortLink.writeMobHit(box.getCenter().x, floorUnder(level, hit), box.getCenter().z, 0, true,
						(1 << 7) | (1 << 15) | (money << 16));
				}
				if (killed && !(target instanceof EndCrystal)) {
					// TF2's kill feed: you - weapon icon - the mob (Alex, 2026-10-12).
					String robot = Robots.killFeedName(target);
					FortLink.writeMobKill(robot != null ? robot : target.getName().getString(),
						((effects & CombatRules.SENTRY) != 0 ? 1 : 0) | ((effects & CombatRules.CRIT) != 0 ? 2 : 0)
						| (backstab ? 4 : 0) | (robot != null ? 8 : 0));  // protocol kMobKill*
				}
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
