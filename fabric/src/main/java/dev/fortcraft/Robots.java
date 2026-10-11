package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Raiders as Mann vs. Machine robots (Alex, 2026-10-11): "native TF2 MvM robots, but instead of
 * navmesh they use Minecraft's movement". The mobs stay Minecraft's (their AI, pathfinding, raid
 * waves and damage); Minecraft stops drawing them and TF2 draws a robot in each one's place, with
 * TF2's own run, attack and flinch animations, robot voices and footsteps, and gibs on death.
 *
 * Pillager = Bowman (Sniper robot, Huntsman), vindicator = Demoknight, evoker = Medic robot,
 * vex = small Scout robot with a bat, ravager = Giant Heavy (minigun), witch = Jarate Sniper.
 *
 * Round 2 (Alex, 2026-10-11: "make them more like tf2"): some raiders become giants (scaled up,
 * more health, slower, like MvM's giants; more of them in later raid waves and every raid
 * captain), Bowmen shoot faster (Quick Charge III crossbows), and Demoknights shield-charge at
 * their target. Far robots are hidden behind blocks properly: the blocks in front of them go to
 * TF2's depth layer (RobotOccluders).
 */
public final class Robots {
	public static final int NONE = 0, BOWMAN = 1, DEMOKNIGHT = 2, MEDIC = 3, SCOUT = 4, GIANT_HEAVY = 5, JARATE = 6;

	// Flags for TF2 (protocol kRobot*).
	private static final int DEAD = 1, HURT = 2, GROUND = 4, SWING = 8, CHARGING = 16, CASTING = 32,
		CELEBRATING = 64, AGGRESSIVE = 128, RAM = 256, ROAR = 512, HIDDEN = 1024, CAPTAIN = 2048, DRINKING = 4096,
		GIANT = 8192;

	private static final int MAX = 64;
	private static final double RANGE = 64.0;
	private static final double DEPTH_LAYER_BLOCKS = 10.0;  // TF2's depth boxes hide nearer ones
	private static final int[] ids = new int[MAX];
	private static final byte[] kinds = new byte[MAX];
	private static final short[] flags = new short[MAX];
	private static final float[] values = new float[MAX * 8];
	private static final int MAX_OCCLUDERS = 512;
	private static final float[] occluders = new float[MAX_OCCLUDERS * 6];
	private static final Set<BlockPos> occluderBlocks = new HashSet<>();
	private static long frame;
	private static int occluderCount;
	private static final String INIT_TAG = "fortcraft_robot";
	private static final double GIANT_SCALE = 1.75;
	private static final Map<Integer, Long> chargeReadyAt = new HashMap<>();

	private Robots() {
	}

	/** Which robot TF2 draws for this mob, or NONE. */
	public static int kindOf(Entity e) {
		if (!(e instanceof Raider) && !(e instanceof Mob)) {
			return NONE;
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

	/** True while TF2 draws this mob as a robot (so Minecraft doesn't draw it). */
	public static boolean drawnByTf2(Entity e) {
		return Combat.linked && kindOf(e) != NONE;
	}

	/** Once per Minecraft frame while TF2 is linked: the robots near the player to TF2. */
	public static void tick(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null) {
			FortLink.writeRobots(ids, kinds, flags, values, 0);
			return;
		}
		float pt = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		boolean occlude = (++frame & 1) == 0;  // the occluder rays every other frame
		if (occlude) {
			occluderBlocks.clear();
			occluderCount = 0;
		}
		if (frame % 10 == 0) {
			serverTick(minecraft);
		}
		Vec3 eye = minecraft.gameRenderer.mainCamera().position();
		AABB area = minecraft.player.getBoundingBox().inflate(RANGE);
		int n = 0;
		for (Entity e : minecraft.level.getEntities(minecraft.player, area, e -> kindOf(e) != NONE && !e.isRemoved())) {
			if (n >= MAX) {
				break;
			}
			LivingEntity mob = (LivingEntity) e;
			Vec3 pos = mob.getPosition(pt);
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
			if (mob.isSwinging()) {
				f |= SWING;
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
			ids[n] = mob.getId();
			kinds[n] = (byte) kindOf(mob);
			flags[n] = (short) f;
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
		}
		FortLink.writeRobots(ids, kinds, flags, values, n);
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

	/**
	 * On Minecraft's built-in server, a few times a second: new raiders become MvM robots once
	 * (giants, faster Bowmen), and Demoknights shield-charge.
	 */
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
			for (Mob mob : level.getEntitiesOfClass(Mob.class, area, m -> kindOf(m) != NONE && m.isAlive())) {
				if (!mob.entityTags().contains(INIT_TAG)) {
					mob.addTag(INIT_TAG);
					becomeRobot(level, mob);
				}
				if (mob instanceof Vindicator) {
					shieldCharge(mob, now);
				}
			}
			chargeReadyAt.keySet().removeIf(id -> level.getEntity(id) == null);
		});
	}

	/** Once per raider: maybe a giant (like MvM's), and Bowmen get Quick Charge III crossbows. */
	private static void becomeRobot(ServerLevel level, Mob mob) {
		int kind = kindOf(mob);
		int wave = mob instanceof Raider r && r.getCurrentRaid() != null ? r.getCurrentRaid().getGroupsSpawned() : 0;
		double chance = switch (kind) {
			case DEMOKNIGHT -> 0.12 + 0.06 * wave;  // Giant Demoknights, more in later waves
			case BOWMAN -> 0.05 + 0.03 * wave;      // Giant Bowmen
			case MEDIC -> 0.25;                     // Giant Medics
			default -> 0.0;                         // the ravager is always a Giant Heavy
		};
		boolean giant = mob.getRandom().nextDouble() < chance || (mob instanceof Raider r && r.isCaptain());
		if (giant && kind != SCOUT && kind != JARATE && kind != GIANT_HEAVY) {
			setBase(mob.getAttribute(Attributes.SCALE), GIANT_SCALE);
			AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
			if (health != null) {
				health.setBaseValue(health.getBaseValue() * 4.0);
				mob.setHealth(mob.getMaxHealth());
			}
			AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
			if (speed != null) {
				speed.setBaseValue(speed.getBaseValue() * 0.7);
			}
			setBase(mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE), 1.0);
			FortCraft.LOG.info("FortCraft: raider {} ({}) is a giant robot (wave {})", mob.getId(), kind, wave);
		}
		if (kind == BOWMAN && mob.getMainHandItem().is(Items.CROSSBOW)) {
			// The Huntsman fires much faster than a crossbow reloads.
			mob.getMainHandItem().enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.QUICK_CHARGE), 3);
		}
	}

	private static void setBase(AttributeInstance attribute, double value) {
		if (attribute != null) {
			attribute.setBaseValue(value);
		}
	}

	/**
	 * Demoknights shield-charge like TF2's: with a target 4-16 blocks away in sight, a burst of
	 * speed straight at it for 1.5 s, then a 6-10 s cooldown. TF2 sees the speed and plays the
	 * charge sound.
	 */
	private static void shieldCharge(Mob mob, long now) {
		LivingEntity target = mob.getTarget();
		if (target == null || now < chargeReadyAt.getOrDefault(mob.getId(), 0L)) {
			return;
		}
		double distance = mob.distanceTo(target);
		if (distance < 4.0 || distance > 16.0 || !mob.hasLineOfSight(target)) {
			return;
		}
		chargeReadyAt.put(mob.getId(), now + 120 + mob.getRandom().nextInt(80));
		mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 5, false, false));
		mob.lookAt(target, 360.0f, 360.0f);
	}
}
