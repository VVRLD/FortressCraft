package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Raiders as Mann vs. Machine robots (Alex, 2026-10-11): "native TF2 MvM robots, but instead of
 * navmesh they use Minecraft's movement". The mobs stay Minecraft's (their AI, pathfinding, raid
 * waves and damage); Minecraft stops drawing them and TF2 draws a robot in each one's place, with
 * TF2's own run, attack and flinch animations, robot voices and footsteps, and gibs on death.
 *
 * Round 1: pillager = Bowman (Sniper robot, Huntsman), vindicator = Demoknight, evoker = Medic
 * robot, vex = small Scout robot with a bat, ravager = Giant Heavy (minigun), witch = Jarate
 * Sniper.
 *
 * Round 2: some raiders become giants (scaled up, more health, slower, like MvM's giants; more of
 * them in later raid waves and every raid captain), Bowmen shoot faster (Quick Charge III), and
 * Demoknights shield-charge. Far robots are hidden behind blocks properly: the blocks in front of
 * them go to TF2's depth layer (RobotOccluders).
 *
 * Round 3: Demoknights charge only every 12-18 s and TF2 hears the real charge; every melee hit
 * and shot is told to TF2 as an attack, so robots swing; Bowmen fire extra arrows; the Giant
 * Heavy fires its minigun; witches throw Jarate.
 *
 * Round 4 (Alex, 2026-10-11: more enemy types, keep them weakish, more aggressive):
 * - pillagers are Bowmen, Soldiers (rockets), Demomen (grenades) or Snipers (rifle shots);
 *   vindicators are Demoknights, Spies (cloaked until close) or full-size Scouts (scattergun);
 * - Medics heal hurt robots near them and sometimes Ubercharge (both immune for 6 s);
 * - the last wave's captain is a boss: a huge Giant Soldier;
 * - every robot goes for a player it can see within 32 blocks, giants and Spies hit even when
 *   Minecraft's own attack doesn't reach;
 * - Bowman arrows are drawn as TF2 arrows, rockets and pipes as TF2's, with explosions.
 * Robots do 60% damage (ServerPlayerMixin), and half again during raids.
 *
 * Round 5 (Alex, 2026-10-11): more Medics (some pillagers are Medics too), more Soldier and
 * Demoman giants and fewer giant Demoknights, the last wave's boss is a Sergeant Crits-style
 * Giant Soldier or a Major Bomber-style Giant Demoman, and the last wave brings a Tank (a
 * ravager TF2 draws as MvM's tank). Earlier waves get more extra robots. Robot rockets and pipes
 * can crit (decided when fired, drawn with crit trails). TF2 is told when a robot burns or wears
 * Jarate / Mad Milk, and shows it on the robot. Wave counts go to TF2 by kind, giant and boss.
 */
public final class Robots {
	public static final int NONE = 0, BOWMAN = 1, DEMOKNIGHT = 2, MEDIC = 3, SCOUT = 4, GIANT_HEAVY = 5, JARATE = 6, JAR = 7,
		ARROW = 8, ROCKET = 9, PIPE = 10, SOLDIER = 11, DEMOMAN = 12, SNIPER = 13, SPY = 14, SCOUT_FULL = 15, TANK = 16,
		/** Not a robot: the MvM bomb in the Ghost Town, dropped on the ground or on a carrier's back. */
		BOMB = 17;

	// Flags for TF2 (protocol kRobot*).
	private static final int DEAD = 1, HURT = 2, GROUND = 4, SWING = 8, CHARGING = 16, CASTING = 32,
		CELEBRATING = 64, AGGRESSIVE = 128, RAM = 256, ROAR = 512, HIDDEN = 1024, CAPTAIN = 2048, DRINKING = 4096,
		GIANT = 8192, CHARGE = 16384, FIRING = 32768;
	// Extra flags (protocol kRobotEx*).
	// EX_BOSS on a rocket or pipe: a crit shot. v69: burning, Jarate, Mad Milk.
	private static final int EX_UBER = 1, EX_CLOAKED = 2, EX_BOSS = 4, EX_HEALING = 8, EX_BURNING = 16, EX_JARATED = 32, EX_MILKED = 64;

	private static final int MAX = 64;
	private static final double RANGE = 64.0;
	private static final double DEPTH_LAYER_BLOCKS = 10.0;  // TF2's depth boxes hide nearer ones
	private static final int[] ids = new int[MAX];
	private static final byte[] kinds = new byte[MAX];
	private static final byte[] extras = new byte[MAX];
	private static final short[] flags = new short[MAX];
	private static final int[] targets = new int[MAX];
	private static final float[] values = new float[MAX * 8];
	private static final int MAX_OCCLUDERS = 512;
	private static final float[] occluders = new float[MAX_OCCLUDERS * 6];
	private static final Set<BlockPos> occluderBlocks = new HashSet<>();
	private static long frame;
	private static int occluderCount;
	private static final String INIT_TAG = "fortcraft_robot";
	private static final String KIND_TAG = "fortcraft_kind_";
	/** Set on a new mob to choose its robot class (Ghost Town wave squads). */
	public static final String FORCE_KIND_TAG = "fortcraft_force_kind_";
	private static final String BOSS_TAG = "fortcraft_boss";
	private static final double GIANT_SCALE = 1.75, BOSS_SCALE = 2.2;

	// Server thread only.
	private static final Map<Integer, Long> chargeReadyAt = new HashMap<>();
	private static final Map<Integer, Long> nextShotAt = new HashMap<>();
	private static final Map<Integer, Long> nextMinigunAt = new HashMap<>();
	private static final Map<Integer, Long> nextMoveAt = new HashMap<>();
	private static final Map<Integer, Long> nextMeleeAt = new HashMap<>();
	private static final Map<Integer, Long> nextHealAt = new HashMap<>();
	private static final Map<Integer, Long> uberReadyAt = new HashMap<>();

	// Shared by Minecraft's server thread (writes) and the render thread (reads): same game, same
	// entity ids. Times are System.nanoTime().
	private static final Map<Integer, Long> attackedAt = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> chargeUntil = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> firingUntil = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> uberUntil = new ConcurrentHashMap<>();
	private static final Map<Integer, Integer> healing = new ConcurrentHashMap<>();  // medic -> patient
	private static final Map<Integer, Integer> variant = new ConcurrentHashMap<>();  // robot kind chosen for a mob
	private static final Set<Integer> cloaked = ConcurrentHashMap.newKeySet();
	private static final Set<Integer> bosses = ConcurrentHashMap.newKeySet();
	/** Snowballs that are witches' Jarate jars (TF2 draws them; their hit covers the player). */
	public static final Set<Integer> JARS = ConcurrentHashMap.newKeySet();
	/** Snowballs that are Soldier rockets and Demoman pipes (TF2 draws them; they explode). */
	public static final Set<Integer> ROCKETS = ConcurrentHashMap.newKeySet();
	public static final Set<Integer> PIPES = ConcurrentHashMap.newKeySet();
	/** Rockets and pipes that crit (decided when fired, so TF2 can draw the crit trail). */
	private static final Set<Integer> CRIT_SHOTS = ConcurrentHashMap.newKeySet();

	/**
	 * Set on Minecraft's server thread while a robot's hit that is a critical one is being dealt
	 * (ServerPlayerMixin doubles it and tells TF2, so crit resistance applies).
	 */
	public static volatile boolean critPending;

	/** Robots crit 10% of the time; the boss (Sergeant Crits-style) always. */
	public static boolean rollCrit(Entity attacker) {
		if (!isRobot(attacker)) {
			return false;
		}
		return bosses.contains(attacker.getId()) || attacker.getRandom().nextFloat() < 0.1f;
	}

	/** A robot's hit on a target, maybe a crit. */
	private static void robotHit(ServerLevel level, LivingEntity target, net.minecraft.world.damagesource.DamageSource source, Entity attacker, float damage) {
		critPending = rollCrit(attacker);
		try {
			target.hurtServer(level, source, damage);
		} finally {
			critPending = false;
		}
	}

	/** A melee hit, a shot or a throw by this mob (server): TF2 plays its attack gesture. */
	public static void noteAttack(int id) {
		attackedAt.put(id, System.nanoTime());
	}

	private Robots() {
	}

	/** Which robot (or robot projectile) TF2 draws for this entity, or NONE. */
	public static int kindOf(Entity e) {
		int id = e.getId();
		if (JARS.contains(id)) {
			return JAR;
		}
		if (ROCKETS.contains(id)) {
			return ROCKET;
		}
		if (PIPES.contains(id)) {
			return PIPE;
		}
		if (e instanceof AbstractArrow a) {
			return a.getOwner() instanceof Pillager ? ARROW : NONE;  // Bowman arrows: TF2's Huntsman arrows
		}
		if (e instanceof net.minecraft.world.entity.item.ItemEntity item && dev.fortcraft.mvm.GhostTownWaves.isBombItem(item.getItem())) {
			return BOMB;  // the dropped bomb: TF2 draws MvM's bomb, Minecraft hides the TNT
		}
		if (!(e instanceof Mob)) {
			return NONE;
		}
		Integer chosen = variant.get(id);
		if (chosen != null) {
			return chosen;
		}
		return switch (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()) {
			case "pillager" -> BOWMAN;
			case "vindicator" -> DEMOKNIGHT;
			case "evoker" -> MEDIC;
			case "vex" -> SCOUT;
			case "ravager" -> GIANT_HEAVY;
			case "witch" -> JARATE;
			default -> NONE;
		};
	}

	/** True while TF2 draws this entity (so Minecraft doesn't draw it). */
	public static boolean drawnByTf2(Entity e) {
		return Combat.linked && kindOf(e) != NONE;
	}

	/** The kill feed's name for a robot ("Soldier", "Boss Demoman"...), or null if it isn't one. */
	public static String killFeedName(Entity e) {
		String name = switch (kindOf(e)) {
			case BOWMAN -> "Bowman";
			case DEMOKNIGHT -> "Demoknight";
			case MEDIC -> "Medic";
			case SCOUT, SCOUT_FULL -> "Scout";
			case GIANT_HEAVY -> "Giant Heavy";
			case JARATE -> "Jarate Sniper";
			case SOLDIER -> "Soldier";
			case DEMOMAN -> "Demoman";
			case SNIPER -> "Sniper";
			case SPY -> "Spy";
			case TANK -> "Tank";
			default -> null;
		};
		return name != null && bosses.contains(e.getId()) ? "Boss " + name : name;
	}

	/** True for robots (not their projectiles): their hits on the player are softened. */
	public static boolean isRobot(Entity e) {
		int k = e == null ? NONE : kindOf(e);
		return k != NONE && k != JAR && k != ARROW && k != ROCKET && k != PIPE && k != BOMB;
	}

	/** Once per Minecraft frame while TF2 is linked: the robots near the player to TF2. */
	public static void tick(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null) {
			FortLink.writeRobots(ids, kinds, extras, flags, targets, values, 0);
			return;
		}
		float pt = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		boolean occlude = (++frame & 1) == 0;  // the occluder rays every other frame
		if (occlude) {
			occluderBlocks.clear();
			occluderCount = 0;
		}
		if (frame % 3 == 0) {
			serverTick(minecraft);
		}
		long nanos = System.nanoTime();
		Vec3 eye = minecraft.gameRenderer.mainCamera().position();
		AABB area = minecraft.player.getBoundingBox().inflate(RANGE);
		int n = 0;
		for (Entity e : minecraft.level.getEntities(minecraft.player, area, e -> kindOf(e) != NONE && !e.isRemoved())) {
			if (n >= MAX) {
				break;
			}
			if (!(e instanceof LivingEntity mob)) {
				// A projectile in flight: where it is (TF2 turns it along its path).
				Vec3 at = e.getPosition(pt);
				ids[n] = e.getId();
				kinds[n] = (byte) kindOf(e);
				extras[n] = (byte) (CRIT_SHOTS.contains(e.getId()) ? EX_BOSS : 0);
				flags[n] = 0;
				targets[n] = 0;
				int o = n * 8;
				values[o] = (float) at.x;
				values[o + 1] = (float) at.y;
				values[o + 2] = (float) at.z;
				values[o + 3] = e.getYRot();
				values[o + 4] = e.getYRot();
				values[o + 5] = e.getXRot();
				values[o + 6] = e.getBbHeight();
				values[o + 7] = e.getBbWidth();
				n++;
				continue;
			}
			Vec3 pos = mob.getPosition(pt);
			int id = mob.getId();
			int f = 0;
			if (mob.isDeadOrDying()) {
				f |= DEAD;
			}
			if (mob.hurtTime > 0) {
				f |= HURT;
			}
			if (mob.onGround() || mob.isPassenger()) {
				f |= GROUND;
			}
			Long attacked = attackedAt.get(id);
			if (mob.isSwinging() || (attacked != null && nanos - attacked < 250_000_000L)) {
				f |= SWING;
			}
			Long charge = chargeUntil.get(id);
			if (charge != null && nanos < charge) {
				f |= CHARGE;
			}
			Long firing = firingUntil.get(id);
			if (firing != null && nanos < firing) {
				f |= FIRING;
			}
			if (mob instanceof Pillager p && p.isChargingCrossbow()) {
				f |= CHARGING;
			}
			if (mob instanceof SpellcasterIllager s && s.isCastingSpell()) {
				f |= CASTING;
			}
			if (mob instanceof Raider r) {
				if (r.isCelebrating()) {
					f |= CELEBRATING;
				}
				if (r.isCaptain()) {
					f |= CAPTAIN;
				}
			}
			if (mob instanceof Mob m && m.isAggressive()) {
				f |= AGGRESSIVE;
			}
			if (mob instanceof Ravager r) {
				if (r.getAttackTick() > 0) {
					f |= RAM;
				}
				if (r.getRoarTick() > 0) {
					f |= ROAR;
				}
			}
			if (mob instanceof Witch w && w.isDrinkingPotion()) {
				f |= DRINKING;
			}
			if (mob.getScale() > 1.3f) {
				f |= GIANT;
			}
			int x = 0;
			Long uber = uberUntil.get(id);
			if (uber != null && nanos < uber) {
				x |= EX_UBER;
			}
			if (cloaked.contains(id)) {
				x |= EX_CLOAKED;
			}
			if (bosses.contains(id)) {
				x |= EX_BOSS;
			}
			Integer patient = healing.get(id);
			if (patient != null) {
				x |= EX_HEALING;
			}
			if (mob.isOnFire()) {
				x |= EX_BURNING;
			}
			if (Combat.isJarated(mob)) {
				x |= EX_JARATED;
			}
			if (Combat.isMilked(mob)) {
				x |= EX_MILKED;
			}
			// Far robots behind Minecraft's blocks: TF2 has no depth for them there.
			boolean hidden = false;
			Vec3 middle = pos.add(0, mob.getBbHeight() * 0.5, 0);
			if (middle.distanceTo(eye) > DEPTH_LAYER_BLOCKS) {
				Vec3 top = pos.add(0, mob.getBbHeight() * 0.9, 0);
				hidden = !FarOcclusion.visible(minecraft, eye, middle, 0.3) && !FarOcclusion.visible(minecraft, eye, top, 0.3)
					&& !FarOcclusion.visible(minecraft, eye, pos.add(0, 0.2, 0), 0.3);
			}
			if (hidden) {
				f |= HIDDEN;
			} else if (occlude && middle.distanceTo(eye) > DEPTH_LAYER_BLOCKS - 2) {
				addOccluders(minecraft, eye, pos, mob.getBbWidth(), mob.getBbHeight());
			}
			ids[n] = id;
			kinds[n] = (byte) kindOf(mob);
			if (dev.fortcraft.mvm.GhostTownWaves.isDeploying(id)) x |= 128;  // kRobotExDeploying: TF2 plays the bomb deploy
			extras[n] = (byte) x;
			flags[n] = (short) f;
			targets[n] = patient != null ? patient : 0;
			int o = n * 8;
			values[o] = (float) pos.x;
			values[o + 1] = (float) pos.y;
			values[o + 2] = (float) pos.z;
			values[o + 3] = mob.getPreciseBodyRotation(pt);
			values[o + 4] = mob.getViewYRot(pt);
			values[o + 5] = mob.getViewXRot(pt);
			values[o + 6] = mob.getBbHeight();
			values[o + 7] = mob.getBbWidth();
			n++;
			if (n < MAX && mob.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(net.minecraft.world.item.Items.TNT)
				&& minecraft.level.dimension() == dev.fortcraft.mvm.GhostTown.LEVEL) {
				// The Ghost Town's bomb carrier: the bomb on its back (TF2 draws it; the TNT "hat" is
				// only how the server marks the carrier).
				float yaw = mob.getPreciseBodyRotation(pt);
				double r = Math.toRadians(yaw);
				double back = 0.35 * mob.getScale();
				ids[n] = id | 0x40000000;
				kinds[n] = (byte) BOMB;
				extras[n] = 0;
				flags[n] = 0;
				targets[n] = 0;
				int b = n * 8;
				values[b] = (float) (pos.x + Math.sin(r) * back);
				values[b + 1] = (float) (pos.y + mob.getBbHeight() * 0.45);
				values[b + 2] = (float) (pos.z - Math.cos(r) * back);
				values[b + 3] = yaw;
				values[b + 4] = yaw;
				values[b + 5] = 0;
				values[b + 6] = 0.6f;
				values[b + 7] = 0.6f;
				n++;
			}
		}
		FortLink.writeRobots(ids, kinds, extras, flags, targets, values, n);
		if (occlude) {
			FortLink.writeRobotOccluders(occluders, occluderCount);
		}
	}

	/**
	 * The blocks between the camera and a far robot: rays to 15 points over it (3 across, 5 up),
	 * each first block hit goes to TF2's depth layer, so the robot is cut off where a wall covers
	 * it instead of drawing through it.
	 */
	private static void addOccluders(Minecraft minecraft, Vec3 eye, Vec3 feet, double width, double height) {
		Vec3 toMob = feet.subtract(eye);
		Vec3 side = new Vec3(-toMob.z, 0, toMob.x);
		side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
		for (int col = -1; col <= 1; col++) {
			for (int row = 0; row < 5; row++) {
				if (occluderCount >= MAX_OCCLUDERS) {
					return;
				}
				Vec3 p = feet.add(side.scale(col * width * 0.5)).add(0, height * (0.05 + row * 0.225), 0);
				HitResult hit = minecraft.level.clip(new ClipContext(eye, p, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
				if (hit.getType() != HitResult.Type.BLOCK || hit.getLocation().distanceTo(p) < 0.05) {
					continue;
				}
				BlockPos b = ((BlockHitResult) hit).getBlockPos();
				if (!occluderBlocks.add(b.immutable())) {
					continue;
				}
				BlockState state = minecraft.level.getBlockState(b);
				VoxelShape shape = state.getVisualShape(minecraft.level, b, CollisionContext.empty());
				AABB box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
				int o = occluderCount * 6;
				occluders[o] = (float) (b.getX() + box.minX);
				occluders[o + 1] = (float) (b.getY() + box.minY);
				occluders[o + 2] = (float) (b.getZ() + box.minZ);
				occluders[o + 3] = (float) (b.getX() + box.maxX);
				occluders[o + 4] = (float) (b.getY() + box.maxY);
				occluders[o + 5] = (float) (b.getZ() + box.maxZ);
				occluderCount++;
			}
		}
	}

	/** On Minecraft's built-in server, about 20 times a second: what the robots do. */
	private static void serverTick(Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level == null || minecraft.player == null) {
			return;
		}
		var dimension = minecraft.level.dimension();
		Vec3 at = minecraft.player.position();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) {
				return;
			}
			AABB area = new AABB(at, at).inflate(96.0);
			long now = level.getGameTime();
			for (Mob mob : level.getEntitiesOfClass(Mob.class, area, m -> m.isAlive() && isRobot(m))) {
				if (!mob.entityTags().contains(INIT_TAG)) {
					mob.addTag(INIT_TAG);
					becomeRobot(level, mob);
				} else if (!variant.containsKey(mob.getId())) {
					restoreVariant(mob);  // a world loaded again: its kind is in its tags
				}
				hunt(level, mob);
				switch (kindOf(mob)) {
					case DEMOKNIGHT -> {
						shieldCharge(mob, now);
						giantMelee(level, mob, now);
					}
					case BOWMAN -> extraArrow(level, mob, now);
					case GIANT_HEAVY -> minigun(level, mob, now);
					case SOLDIER -> {
						keepRange(mob, now, 14.0);
						launch(level, mob, now, ROCKET);
					}
					case DEMOMAN -> {
						keepRange(mob, now, 12.0);
						launch(level, mob, now, PIPE);
					}
					case SNIPER -> {
						keepRange(mob, now, 24.0);
						hitscan(level, mob, now, 40.0, 3.0f, 60, 80);
					}
					case SCOUT_FULL -> hitscan(level, mob, now, 8.0, 2.0f, 25, 15);
					case SPY -> spy(level, mob, now);
					case MEDIC -> medic(level, mob, now);
					default -> {
					}
				}
			}
			if ((now & 63) == 0) {
				for (Map<Integer, ?> map : java.util.List.of(chargeReadyAt, nextShotAt, nextMinigunAt, nextMoveAt, nextMeleeAt, nextHealAt,
					uberReadyAt, attackedAt, chargeUntil, firingUntil, uberUntil, healing, variant)) {
					map.keySet().removeIf(id -> level.getEntity(id) == null);
				}
				for (Set<Integer> set : java.util.List.of(cloaked, bosses, JARS, ROCKETS, PIPES, CRIT_SHOTS)) {
					set.removeIf(id -> level.getEntity(id) == null);
				}
			}
		});
	}

	/**
	 * Once per raider: which robot it is, maybe a giant (like MvM's), and the last wave's captain
	 * is the boss. The kind is kept in the mob's tags, so it survives saving the world.
	 */
	private static void becomeRobot(ServerLevel level, Mob mob) {
		var raid = mob instanceof Raider r ? r.getCurrentRaid() : null;
		int wave = raid != null ? raid.getGroupsSpawned() : dev.fortcraft.mvm.GhostTownWaves.waveOf(mob);
		boolean lastWave = raid != null ? wave >= raid.getNumGroups(level.getDifficulty()) : dev.fortcraft.mvm.GhostTownWaves.lastWave(mob);
		boolean captain = mob instanceof Raider r && r.isCaptain();
		double roll = mob.getRandom().nextDouble();
		int kind = kindOf(mob);
		if (mob instanceof Pillager) {
			kind = roll < 0.3 ? BOWMAN : roll < 0.52 ? SOLDIER : roll < 0.72 ? DEMOMAN : roll < 0.88 ? SNIPER : MEDIC;
		} else if (mob instanceof Vindicator) {
			kind = roll < 0.55 ? DEMOKNIGHT : roll < 0.75 ? SPY : SCOUT_FULL;
		}
		// A Ghost Town wave asks for a class (its squads: "20 Scouts, then Snipers"...).
		for (String tag : mob.entityTags()) {
			if (tag.startsWith(FORCE_KIND_TAG)) {
				try {
					kind = Integer.parseInt(tag.substring(FORCE_KIND_TAG.length()));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		boolean boss = captain && lastWave;
		if (boss) {
			// The last wave's boss: Sergeant Crits (a huge Giant Soldier) or Major Bomber (a huge
			// Giant Demoman), both always critting.
			kind = mob.getRandom().nextBoolean() ? SOLDIER : DEMOMAN;
		}
		if (kind == TANK) {
			// MvM's Tank: huge, tough and slow; it rams whatever is in its way.
			setBase(mob.getAttribute(Attributes.SCALE), 2.0);
			scale(mob.getAttribute(Attributes.MAX_HEALTH), 5.0);
			mob.setHealth(mob.getMaxHealth());
			scale(mob.getAttribute(Attributes.MOVEMENT_SPEED), 0.6);
			setBase(mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE), 1.0);
			mob.addTag(KIND_TAG + kind);
			FortCraft.LOG.info("FortCraft: raider {} is the Tank (wave {})", mob.getId(), wave);
			return;
		}
		variant.put(mob.getId(), kind);
		mob.addTag(KIND_TAG + kind);

		// Robots that don't use the crossbow or axe put it away (TF2 draws their own weapon).
		if (kind == SOLDIER || kind == DEMOMAN || kind == SNIPER || kind == SPY || kind == SCOUT_FULL || (kind == MEDIC && mob instanceof Pillager)) {
			mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
		setBase(mob.getAttribute(Attributes.FOLLOW_RANGE), 48.0);
		if (kind == SCOUT_FULL || kind == SPY) {
			scale(mob.getAttribute(Attributes.MOVEMENT_SPEED), kind == SCOUT_FULL ? 1.4 : 1.15);
			scale(mob.getAttribute(Attributes.MAX_HEALTH), 0.6);
			mob.setHealth(mob.getMaxHealth());
		}

		// Giants (Alex: more Soldier and Demoman giants, fewer giant Demoknights), more each wave.
		double giantRoll = mob.getRandom().nextDouble();
		double chance = switch (kind) {
			case SOLDIER, DEMOMAN -> 0.10 + 0.06 * wave;
			case DEMOKNIGHT -> 0.03 + 0.02 * wave;
			case BOWMAN -> 0.04 + 0.02 * wave;
			case MEDIC -> 0.15;
			default -> 0.0;
		};
		boolean giant = boss || giantRoll < chance || (captain && (kind == BOWMAN || kind == SOLDIER || kind == DEMOMAN));
		if (giant && kind != SCOUT && kind != JARATE && kind != GIANT_HEAVY && kind != SPY && kind != SNIPER) {
			setBase(mob.getAttribute(Attributes.SCALE), boss ? BOSS_SCALE : GIANT_SCALE);
			scale(mob.getAttribute(Attributes.MAX_HEALTH), boss ? 10.0 : 4.0);
			mob.setHealth(mob.getMaxHealth());
			scale(mob.getAttribute(Attributes.MOVEMENT_SPEED), 0.7);
			setBase(mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE), 1.0);
			if (boss) {
				bosses.add(mob.getId());
				mob.addTag(BOSS_TAG);
			}
			FortCraft.LOG.info("FortCraft: raider {} is a {} robot {} (wave {})", mob.getId(), boss ? "boss" : "giant", kind, wave);
		}
		if (kind == BOWMAN && mob.getMainHandItem().is(Items.CROSSBOW)) {
			// The Huntsman fires much faster than a crossbow reloads.
			mob.getMainHandItem().enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.QUICK_CHARGE), 3);
		}
	}

	private static void restoreVariant(Mob mob) {
		for (String tag : mob.entityTags()) {
			if (tag.startsWith(KIND_TAG)) {
				try {
					variant.put(mob.getId(), Integer.parseInt(tag.substring(KIND_TAG.length())));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		if (mob.entityTags().contains(BOSS_TAG)) {
			bosses.add(mob.getId());
		}
	}

	private static void setBase(AttributeInstance attribute, double value) {
		if (attribute != null) {
			attribute.setBaseValue(value);
		}
	}

	private static void scale(AttributeInstance attribute, double factor) {
		if (attribute != null) {
			attribute.setBaseValue(attribute.getBaseValue() * factor);
		}
	}

	/** More aggressive (Alex): a robot with no player to fight goes for one it sees within 32 blocks. */
	private static void hunt(ServerLevel level, Mob mob) {
		if (mob.getTarget() instanceof Player p && p.isAlive()) {
			return;
		}
		// In the Ghost Town robots go for creative players too (TF2 decides the damage there), so
		// MvM waves can be played in a creative world (2026-10-20). Snipers look further, as in MvM.
		boolean town = level.dimension() == dev.fortcraft.mvm.GhostTown.LEVEL;
		Player player = level.getNearestPlayer(mob, town && kindOf(mob) == SNIPER ? 48.0 : 32.0);
		if (player != null && (!player.isCreative() || town) && !player.isSpectator() && mob.hasLineOfSight(player)) {
			mob.setTarget(player);
		}
	}

	/** Ranged robots without a Minecraft attack walk to within range of their target. */
	private static void keepRange(Mob mob, long now, double range) {
		LivingEntity target = mob.getTarget();
		if (target == null || now < nextMoveAt.getOrDefault(mob.getId(), 0L)) {
			return;
		}
		nextMoveAt.put(mob.getId(), now + 20);
		double d = mob.distanceTo(target);
		if (d > range * 0.8 || !mob.hasLineOfSight(target)) {
			mob.getNavigation().moveTo(target, 1.0);
		} else if (d < range * 0.4) {
			mob.getNavigation().stop();
		}
		mob.getLookControl().setLookAt(target);
	}

	/**
	 * Giant Demoknights didn't attack (Alex): Minecraft's melee reach is worked out for a normal
	 * size. A giant (or Spy) right next to its target hits it once a second.
	 */
	private static void giantMelee(ServerLevel level, Mob mob, long now) {
		LivingEntity target = mob.getTarget();
		if (target == null || (mob.getScale() < 1.3f && kindOf(mob) != SPY) || now < nextMeleeAt.getOrDefault(mob.getId(), 0L)) {
			return;
		}
		double reach = mob.getBbWidth() * 0.5 + target.getBbWidth() * 0.5 + 1.2;
		if (mob.distanceTo(target) <= reach) {
			nextMeleeAt.put(mob.getId(), now + 20);
			mob.doHurtTarget(level, target);
		}
	}

	/**
	 * Demoknights shield-charge like TF2's: with a target 6-16 blocks away in sight, a burst of
	 * speed straight at it for 1.5 s, then a 12-18 s cooldown (Alex: they spammed it). TF2 is told
	 * the charge itself (CHARGE), not guessing from speed, and plays the charge sound once.
	 */
	private static void shieldCharge(Mob mob, long now) {
		LivingEntity target = mob.getTarget();
		if (target == null || now < chargeReadyAt.getOrDefault(mob.getId(), 0L)) {
			return;
		}
		double distance = mob.distanceTo(target);
		if (distance < 6.0 || distance > 16.0 || !mob.hasLineOfSight(target)) {
			return;
		}
		chargeReadyAt.put(mob.getId(), now + 240 + mob.getRandom().nextInt(120));
		chargeUntil.put(mob.getId(), System.nanoTime() + 1_500_000_000L);
		mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 3, false, false));
		mob.lookAt(target, 360.0f, 360.0f);
	}

	/**
	 * Bowmen fire more often (Alex): besides their own crossbow shots, an arrow every 1.5-2.5 s
	 * at a target in sight within 24 blocks.
	 */
	private static void extraArrow(ServerLevel level, Mob mob, long now) {
		LivingEntity target = mob.getTarget();
		if (target == null || !target.isAlive()) {
			return;
		}
		Long next = nextShotAt.get(mob.getId());
		if (next == null) {
			nextShotAt.put(mob.getId(), now + 20);
			return;
		}
		if (now < next || mob.distanceTo(target) > 24.0 || !mob.hasLineOfSight(target)) {
			return;
		}
		nextShotAt.put(mob.getId(), now + 30 + mob.getRandom().nextInt(20));
		Arrow arrow = new Arrow(level, mob, new ItemStack(Items.ARROW), mob.getMainHandItem().copy());
		arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
		double dx = target.getX() - mob.getX(), dz = target.getZ() - mob.getZ();
		double dy = target.getY(0.3333) - arrow.getY();
		arrow.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.2, dz, 1.6f, 6.0f);
		level.addFreshEntity(arrow);
		mob.lookAt(target, 360.0f, 360.0f);
		noteAttack(mob.getId());
	}

	/**
	 * The Giant Heavy's minigun (Alex: "the massive robots dont shoot"): while it sees its target
	 * within 16 blocks it fires, half a heart every half second, and TF2 shows the gun firing.
	 */
	private static void minigun(ServerLevel level, Mob mob, long now) {
		LivingEntity target = mob.getTarget();
		if (target == null || !target.isAlive() || mob.distanceTo(target) > 16.0 || !mob.hasLineOfSight(target)) {
			return;
		}
		firingUntil.put(mob.getId(), System.nanoTime() + 400_000_000L);
		if (now < nextMinigunAt.getOrDefault(mob.getId(), 0L)) {
			return;
		}
		nextMinigunAt.put(mob.getId(), now + 10);
		robotHit(level, target, level.damageSources().mobProjectile(mob, mob), mob, 1.0f);
	}

	/**
	 * Soldier rockets and Demoman pipes: a projectile Minecraft doesn't draw (TF2 draws the rocket
	 * or pipe), every 2.5-3.5 s at a target in sight within 24 blocks; it explodes where it hits
	 * (SnowballMixin, {@link #blast}). The boss fires every 1.5-2 s.
	 */
	private static void launch(ServerLevel level, Mob mob, long now, int what) {
		LivingEntity target = mob.getTarget();
		if (target == null || !target.isAlive()) {
			return;
		}
		Long next = nextShotAt.get(mob.getId());
		if (next == null) {
			nextShotAt.put(mob.getId(), now + 30);
			return;
		}
		if (now < next || mob.distanceTo(target) > 24.0 || !mob.hasLineOfSight(target)) {
			return;
		}
		boolean boss = bosses.contains(mob.getId());
		nextShotAt.put(mob.getId(), now + (boss ? 30 + mob.getRandom().nextInt(10) : 50 + mob.getRandom().nextInt(20)));
		Snowball shot = new Snowball(level, mob, new ItemStack(Items.SNOWBALL));
		shot.setPos(mob.getX(), mob.getEyeY() - 0.2, mob.getZ());
		double dx = target.getX() - mob.getX(), dz = target.getZ() - mob.getZ();
		double dy = target.getY(0.5) - shot.getY();
		if (rollCrit(mob)) {
			CRIT_SHOTS.add(shot.getId());
		}
		if (what == ROCKET) {
			shot.setNoGravity(true);
			shot.shoot(dx, dy, dz, 1.2f, 2.0f);
			ROCKETS.add(shot.getId());
		} else {
			shot.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.2, dz, 1.0f, 3.0f);
			PIPES.add(shot.getId());
		}
		level.addFreshEntity(shot);
		mob.lookAt(target, 360.0f, 360.0f);
		noteAttack(mob.getId());
	}

	/**
	 * A rocket or pipe going off: everything living within 2.5 blocks but robots takes up to 4
	 * damage (6 from the boss), less further out. Kept weak on purpose (Alex: "not an insta kill").
	 */
	public static void blast(ServerLevel level, Entity projectile, Vec3 at) {
		Entity owner = projectile instanceof Snowball s ? s.getOwner() : null;
		boolean boss = owner != null && bosses.contains(owner.getId());
		double radius = boss ? 3.5 : 2.5;
		float damage = boss ? 6.0f : 4.0f;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(radius), x -> x.isAlive() && !isRobot(x))) {
			double d = e.position().add(0, e.getBbHeight() * 0.5, 0).distanceTo(at);
			if (d > radius) {
				continue;
			}
			float dealt = (float) (damage * (1.0 - 0.5 * d / radius));
			critPending = CRIT_SHOTS.contains(projectile.getId());
			try {
				e.hurtServer(level, level.damageSources().explosion(projectile, owner), dealt);
			} finally {
				critPending = false;
			}
		}
	}

	/** Sniper rifle and Scout scattergun: hitscan at a target in sight within range. */
	private static void hitscan(ServerLevel level, Mob mob, long now, double range, float damage, int every, int jitter) {
		LivingEntity target = mob.getTarget();
		if (target == null || !target.isAlive()) {
			return;
		}
		Long next = nextShotAt.get(mob.getId());
		if (next == null) {
			nextShotAt.put(mob.getId(), now + every / 2);
			return;
		}
		if (now < next || mob.distanceTo(target) > range || !mob.hasLineOfSight(target)) {
			return;
		}
		nextShotAt.put(mob.getId(), now + every + mob.getRandom().nextInt(jitter));
		mob.lookAt(target, 360.0f, 360.0f);
		robotHit(level, target, level.damageSources().mobProjectile(mob, mob), mob, damage);
		noteAttack(mob.getId());
	}

	/** Spies stay cloaked until 5 blocks from their target, then stab (melee, giants' rule too). */
	private static void spy(ServerLevel level, Mob mob, long now) {
		LivingEntity target = mob.getTarget();
		boolean cloak = target == null || mob.distanceTo(target) > 5.0;
		if (cloak) {
			cloaked.add(mob.getId());
		} else {
			cloaked.remove(mob.getId());
		}
		// Backstab (Alex, 2026-10-11): right behind a player who's looking away, a Spy robot's
		// stab is a critical one for a full Minecraft health bar, which kills any class.
		if (target instanceof Player player && now >= nextMeleeAt.getOrDefault(mob.getId(), 0L)) {
			double reach = mob.getBbWidth() * 0.5 + player.getBbWidth() * 0.5 + 1.2;
			Vec3 look = player.getLookAngle().multiply(1, 0, 1);
			Vec3 toSpy = mob.position().subtract(player.position()).multiply(1, 0, 1);
			if (mob.distanceTo(player) <= reach && look.lengthSqr() > 1e-4 && toSpy.lengthSqr() > 1e-4
				&& look.normalize().dot(toSpy.normalize()) < -0.3) {
				nextMeleeAt.put(mob.getId(), now + 40);
				noteAttack(mob.getId());
				critPending = true;
				try {
					player.hurtServer(level, level.damageSources().mobAttack(mob), 20.0f);
				} finally {
					critPending = false;
				}
				FortCraft.LOG.info("FortCraft: Spy robot {} backstabbed the player", mob.getId());
				return;
			}
		}
		giantMelee(level, mob, now);
	}

	/**
	 * Medics heal: the most hurt robot within 10 blocks they can see, a heart a second, walking
	 * after it. When it or the Medic drops under 40% health, they Ubercharge: both immune for
	 * 6 s, then 30 s before the next one.
	 */
	private static void medic(ServerLevel level, Mob mob, long now) {
		Mob patient = null;
		float worst = 1.0f;
		for (Mob other : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(10.0), m -> m != mob && m.isAlive() && isRobot(m))) {
			float part = other.getHealth() / other.getMaxHealth();
			if (part < worst && mob.hasLineOfSight(other)) {
				worst = part;
				patient = other;
			}
		}
		if (patient == null) {
			healing.remove(mob.getId());
			return;
		}
		healing.put(mob.getId(), patient.getId());
		if (now >= nextHealAt.getOrDefault(mob.getId(), 0L)) {
			nextHealAt.put(mob.getId(), now + 10);
			patient.heal(1.0f);
			if (mob.distanceTo(patient) > 5.0) {
				mob.getNavigation().moveTo(patient, 1.0);
			}
		}
		boolean low = worst < 0.4f || mob.getHealth() / mob.getMaxHealth() < 0.4f;
		if (low && now >= uberReadyAt.getOrDefault(mob.getId(), 0L)) {
			uberReadyAt.put(mob.getId(), now + 600);
			long until = System.nanoTime() + 6_000_000_000L;
			for (Mob m : new Mob[] { mob, patient }) {
				m.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 120, 4, false, false));
				uberUntil.put(m.getId(), until);
			}
			FortCraft.LOG.info("FortCraft: Medic robot {} Ubercharged {}", mob.getId(), patient.getId());
		}
	}

	/**
	 * A few more robots each raid wave (Alex: "maybe more mobs"): 1 + wave / 2 (at most 4)
	 * pillagers or vindicators join the wave where its raiders are, and become robots like the
	 * rest (so some are Soldiers, Spies, Scouts...).
	 */
	public static void reinforce(ServerLevel level, net.minecraft.world.entity.raid.Raid raid, int wave) {
		BlockPos at = raid.getCenter();
		for (Raider r : raid.getAllRaiders()) {
			if (r.isAlive()) {
				at = r.blockPosition();
				break;
			}
		}
		boolean lastWave = wave >= raid.getNumGroups(level.getDifficulty());
		// Busier waves before the last (Alex: "more chaotic"): 2 + wave extra robots (at most 6);
		// the last wave gets 2 and the Tank.
		int extra = lastWave ? 2 : Math.min(2 + wave, 6);
		if (lastWave) {
			Raider tank = net.minecraft.world.entity.EntityTypes.RAVAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
			if (tank != null) {
				variant.put(tank.getId(), TANK);
				raid.joinRaid(level, wave, tank, at, false);
			}
		}
		for (int i = 0; i < extra; i++) {
			Raider raider = level.getRandom().nextBoolean()
				? net.minecraft.world.entity.EntityTypes.PILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT)
				: net.minecraft.world.entity.EntityTypes.VINDICATOR.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
			if (raider == null) {
				continue;
			}
			raid.joinRaid(level, wave, raider, at.offset(level.getRandom().nextInt(5) - 2, 0, level.getRandom().nextInt(5) - 2), false);
		}
		FortCraft.LOG.info("FortCraft: raid wave {}: {} extra robots", wave, extra);
	}

	/**
	 * Raider counts for TF2's MvM wave panel, from a raid's living raiders: slot = kind (normal),
	 * 16 + kind (giant; 16 itself is the Tank), 32 + kind (boss).
	 */
	public static byte[] countKinds(Iterable<? extends Entity> raiders) {
		byte[] counts = new byte[48];
		for (Entity e : raiders) {
			if (!e.isAlive()) {
				continue;
			}
			int k = kindOf(e);
			if (k <= 0 || k > 16) {
				continue;
			}
			int slot = k == TANK ? 16 : bosses.contains(e.getId()) ? 32 + k
				: e instanceof LivingEntity l && l.getScale() > 1.3f ? 16 + k : k;
			if (slot < 48 && counts[slot] < 127) {
				counts[slot]++;
			}
		}
		return counts;
	}
}
