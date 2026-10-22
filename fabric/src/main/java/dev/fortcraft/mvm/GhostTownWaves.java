package dev.fortcraft.mvm;

import dev.fortcraft.FortCraft;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mann vs. Machine waves in the Ghost Town (Alex, 2026-10-19: "start on waves"). Press the Ready
 * button in RED's spawn; robots (Minecraft raiders, which Robots.java already draws as TF2 robots)
 * come from the hill at the east end. One of them carries the bomb along the street to the hatch
 * outside RED's spawn. Kill them all to win the wave; if the bomb reaches the hatch the wave is
 * lost and can be tried again. Six waves.
 */
public final class GhostTownWaves {
	public static final int WAVES = 6;
	private static final String ROBOT_TAG = "fortcraft_mvm_robot";
	private static final String BOMB_TAG = "fortcraft_mvm_bomb";

	private static MinecraftServer server;
	private static int wave = 1;
	private static boolean active;
	private static final Set<UUID> robots = new LinkedHashSet<>();
	private static UUID carrier;
	private static List<BlockPos> path = List.of();
	private static final List<List<BlockPos>> lanes = new ArrayList<>();
	private static final java.util.Map<UUID, Integer> laneOf = new java.util.HashMap<>();
	private static UUID bombItem;
	private static UUID fetcher;
	private static long droppedAt;
	private static net.minecraft.world.phys.Vec3 lastCarrierPos;
	/** When the bomb at the hatch goes off (0: not deploying). */
	private static long deployEnds;
	/** Entity ids deploying the bomb, read by Robots.java on the client thread (TF2's deploy animation). */
	private static final Set<Integer> DEPLOYING = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static int ticks;
	private static boolean buttonWasDown;
	/** For TF2's MvM wave panel: 2 defended or 3 lost, shown for a few seconds after a wave ends. */
	private static int result;
	private static int resultWave;
	private static long resultUntil;
	private static boolean chunksForced;
	private static int lastPanel = -1;

	private GhostTownWaves() {
	}

	/** Every server tick (MinecraftServerMixin). Cheap unless someone is in the Ghost Town. */
	public static void tick(MinecraftServer now) {
		if (now != server) {
			server = now;
			reset();
		}
		if (++ticks % 10 != 0) return;
		ServerLevel level = now.getLevel(GhostTown.LEVEL);
		if (level == null || !GhostTown.isCoalTown()) return;
		if (!GhostTown.ready()) {
			// Set the town up as soon as the world is open, not at the first portal trip: a player
			// who logs in or respawns there needs the spawn room.
			GhostTown.prepare(now);
			return;
		}
		List<ServerPlayer> players = level.players();
		if (players.isEmpty()) {
			idleTicks = 0;
			if (active) end(level, null);
			return;
		}
		for (ServerPlayer p : players) {
			// Outside the town's walls or falling into the void (e.g. a save from before the town
			// was set up): straight back to RED's spawn.
			BlockPos at = p.blockPosition();
			if (at.getY() < 40 || at.getX() < CoalTownMap.WALL_X0 - 8 || at.getX() > CoalTownMap.WALL_X1 + 8
				|| at.getZ() < CoalTownMap.WALL_Z0 - 8 || at.getZ() > CoalTownMap.WALL_Z1 + 8) {
				FortCraft.LOG.info("FortCraft: {} was outside the Ghost Town at {}", p.getName().getString(), at);
				GhostTown.respawnInTown(p);
			}
		}
		BlockState button = level.getBlockState(CoalTownMap.READY_BUTTON);
		boolean down = button.getBlock() instanceof ButtonBlock && button.getValue(ButtonBlock.POWERED);
		if (down && !buttonWasDown && !active) start(level);
		buttonWasDown = down;
		clearMonsters(level);
		if (active) {
			update(level, players);
			idleTicks = 0;
		} else {
			// Alex went in and "the waves didn't start": he never found the button. The next wave
			// now also starts by itself after 30 s, with a countdown on screen.
			idleTicks += 10;
			int left = (AUTO_START_TICKS - idleTicks) / 20;
			if (idleTicks >= AUTO_START_TICKS) {
				idleTicks = 0;
				start(level);
			} else if (idleTicks % 20 == 0) {
				Component hint = Component.literal("Wave " + wave + " starts in " + left
					+ " s  -  or press the button by the bookshelves in RED's spawn");
				for (ServerPlayer p : players) p.sendSystemMessage(hint, true);
			}
		}
	}

	private static final int AUTO_START_TICKS = 30 * 20;
	private static int idleTicks;

	/**
	 * The map is desert, so Minecraft spawns zombies, creepers and spiders there at night (Alex met
	 * a creeper). Only the wave's robots belong in the Ghost Town.
	 */
	private static void clearMonsters(ServerLevel level) {
		var box = new net.minecraft.world.phys.AABB(CoalTownMap.WALL_X0, 0, CoalTownMap.WALL_Z0, CoalTownMap.WALL_X1 + 1, 256, CoalTownMap.WALL_Z1 + 1);
		for (var m : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, box, e -> !isWaveRobot(e))) {
			m.discard();
		}
	}

	private static void reset() {
		wave = 1;
		active = false;
		robots.clear();
		carrier = null;
		path = List.of();
		lanes.clear();
		laneOf.clear();
		bombItem = null;
	}

	// ---------------------------------------------------------------- a wave

	private static void start(ServerLevel level) {
		if (level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
			// Peaceful removes every hostile mob at once, robots included.
			level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
			broadcast(level, "Difficulty set to Normal so the robots can come.");
		}
		if (lanes.isEmpty()) buildLanes(level);
		robots.clear();
		laneOf.clear();
		squads = WAVE_PLAN.get(Math.min(wave, WAVE_PLAN.size()) - 1);
		squad = 0;
		spawnedInSquad = 0;
		spawnedInWave = 0;
		squadStarted = System.currentTimeMillis();
		nextSpawnAt = 0;
		int n = 0;
		for (Squad q : squads) n += q.count();
		waveSize = n;
		active = true;
		carrier = null;
		result = 0;
		removeBombItem(level);
		forceTownChunks(level, true);
		spawnSquads(level);
		pickCarrier(level);
		broadcast(level, "Wave " + wave + " of " + WAVES + ": " + n + " robots in " + squads.size() + " groups are coming down from the hill. Stop the bomb!");
		FortCraft.LOG.info("FortCraft: MvM wave {} started: {} robots in squads {}, lanes {}", wave, n, squads, lanes.stream().map(List::size).toList());
	}

	private static void update(ServerLevel level, List<ServerPlayer> players) {
		Mob before = carrier == null ? null : level.getEntity(carrier) instanceof Mob m ? m : null;
		if (before != null && before.isAlive()) lastCarrierPos = before.position();
		robots.removeIf(id -> {
			Entity e = level.getEntity(id);
			return e == null || !e.isAlive();
		});
		spawnSquads(level);
		if (robots.isEmpty() && squad >= squads.size()) {
			broadcast(level, wave >= WAVES ? "You defended the Ghost Town! All " + WAVES + " waves beaten."
				: "Wave " + wave + " complete! Upgrade at the enchanting table, then press Ready.");
			FortCraft.LOG.info("FortCraft: MvM wave {} won", wave);
			showResult(2);
			wave = wave >= WAVES ? 1 : wave + 1;
			active = false;
			carrier = null;
			removeBombItem(level);
			forceTownChunks(level, false);
			return;
		}
		Mob bomb = carrier == null ? null : level.getEntity(carrier) instanceof Mob m && m.isAlive() ? m : null;
		if (bomb == null && carrier != null) {
			// The carrier died: the bomb drops where it fell, like TF2's.
			carrier = null;
			dropBomb(level, lastCarrierPos);
		}
		ItemEntity dropped = bombItem == null ? null : level.getEntity(bombItem) instanceof ItemEntity ie && ie.isAlive() ? ie : null;
		if (bomb == null && dropped == null) bomb = pickCarrier(level);  // no bomb anywhere: give one out
		if (dropped != null) {
			// The nearest robot goes for it and picks it up when it gets there; after 60 s on the
			// ground it goes back to the robots' spawn (MvM's bomb reset).
			Mob nearest = null;
			double best = Double.MAX_VALUE;
			for (UUID id : robots) {
				if (level.getEntity(id) instanceof Mob m && m.isAlive() && canCarry(m)) {
					double d = m.distanceToSqr(dropped);
					if (d < best) {
						best = d;
						nearest = m;
					}
				}
			}
			if (nearest != null && best <= 1.6 * 1.6) {
				dropped.discard();
				bombItem = null;
				bomb = makeCarrier(nearest);
				broadcast(level, "A robot picked up the bomb!");
			} else if (System.currentTimeMillis() - droppedAt > 60_000) {
				BlockPos home = lanes.isEmpty() ? CoalTownMap.robotSpawn(level) : lanes.get(0).get(0);
				dropped.teleportTo(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5);
				droppedAt = System.currentTimeMillis();
				broadcast(level, "The bomb went back to the robots' spawn.");
			} else if (nearest != null) {
				nearest.setTarget(null);
				nearest.getNavigation().moveTo(dropped.getX(), dropped.getY(), dropped.getZ(), 1.1);
				fetcher = nearest.getUUID();
			}
		}
		BlockPos hatch = CoalTownMap.hatch(level);
		if (bomb == null && deployEnds != 0) {
			deployEnds = 0;  // the carrier died while deploying: the bomb dropped (above)
			DEPLOYING.clear();
			broadcast(level, "The bomb carrier was stopped at the hatch!");
		}
		if (bomb != null) {
			if (deployEnds == 0 && bomb.blockPosition().distSqr(hatch) <= 2.5 * 2.5) {
				// At the hatch the carrier stops and deploys the bomb, as in MvM, which takes 3 s
				// for an ordinary robot, 4 s for a giant and 5 s for the Tank (Alex, 2026-10-20).
				// Kill it before that and the bomb drops instead.
				int kind = dev.fortcraft.Robots.kindOf(bomb);
				int seconds = kind == dev.fortcraft.Robots.TANK ? 5 : bomb.getScale() > 1.3F ? 4 : 3;
				deployEnds = System.currentTimeMillis() + seconds * 1000L;
				bomb.getNavigation().stop();
				bomb.setNoAi(true);
				bomb.teleportTo(hatch.getX() + 0.5, hatch.getY(), hatch.getZ() + 0.5);
				DEPLOYING.add(bomb.getId());
				broadcast(level, "The bomb is being deployed! " + seconds + " seconds to stop it!");
				FortCraft.LOG.info("FortCraft: MvM bomb carrier {} deploying ({} s)", bomb.getId(), seconds);
			}
			if (deployEnds != 0) {
				if (System.currentTimeMillis() >= deployEnds) {
					broadcast(level, "The bomb was deployed! Wave " + wave + " failed. Press Ready to try again.");
					FortCraft.LOG.info("FortCraft: MvM wave {} lost (bomb deployed)", wave);
					showResult(3);
					end(level, null);
					return;
				}
			} else {
				bomb.setTarget(null);  // the carrier only walks
				walkLane(bomb, laneOf.getOrDefault(bomb.getUUID(), 0), hatch, 1.0);
			}
		}
		// Everyone else comes down the lanes too, each in its MvM role (Alex, 2026-10-20: "move and
		// position like the TF2 ones"): Snipers stop where they can see you, from far, and aim
		// (Robots.keepRange keeps them about 24 blocks off); Medics stay with the nearest robot of
		// their wave; the rest walk on and fight whoever they meet close by.
		for (UUID id : robots) {
			if (id.equals(carrier) || dropped != null && id.equals(fetcher)) continue;
			if (!(level.getEntity(id) instanceof Mob m) || !m.isAlive()) continue;
			int kind = dev.fortcraft.Robots.kindOf(m);
			boolean fighting = m.getTarget() instanceof ServerPlayer t && t.isAlive();
			if (kind == dev.fortcraft.Robots.SNIPER) {
				if (fighting && m.distanceToSqr(m.getTarget()) < 48 * 48) continue;
			} else if (kind == dev.fortcraft.Robots.MEDIC) {
				Mob buddy = nearestTeammate(level, m);
				if (buddy != null) {
					if (m.distanceToSqr(buddy) > 3.5 * 3.5) m.getNavigation().moveTo(buddy, 1.15);
					else m.getNavigation().stop();
					continue;
				}
			} else if (fighting && m.distanceToSqr(m.getTarget()) < 20 * 20) {
				continue;
			}
			walkLane(m, laneOf.getOrDefault(id, 0), hatch, 1.0);
		}
		Component status = Component.literal("Wave " + wave + "/" + WAVES + "  -  robots left: " + (waveSize - spawnedInWave + robots.size()));
		for (ServerPlayer p : players) p.sendSystemMessage(status, true);
	}

	/** Remove this wave's robots (wave lost, or everyone left the Ghost Town). */
	private static void end(ServerLevel level, String message) {
		for (UUID id : robots) {
			Entity e = level.getEntity(id);
			if (e != null) e.discard();
		}
		robots.clear();
		carrier = null;
		active = false;
		deployEnds = 0;
		DEPLOYING.clear();
		removeBombItem(level);
		forceTownChunks(level, false);
		if (message != null) broadcast(level, message);
	}

	private static void showResult(int state) {
		result = state;
		resultWave = wave;
		resultUntil = System.currentTimeMillis() + 6000;
	}

	/**
	 * Keep the whole town loaded during a wave: robots far from the player would otherwise be
	 * unloaded, stop walking, and (worse) look dead, ending the wave early.
	 */
	private static void forceTownChunks(ServerLevel level, boolean on) {
		if (on == chunksForced) return;
		chunksForced = on;
		for (int cx = CoalTownMap.WALL_X0 >> 4; cx <= CoalTownMap.WALL_X1 >> 4; cx++) {
			for (int cz = CoalTownMap.WALL_Z0 >> 4; cz <= CoalTownMap.WALL_Z1 >> 4; cz++) level.setChunkForced(cx, cz, on);
		}
	}

	/** True while this robot (entity id) deploys the bomb at the hatch. */
	public static boolean isDeploying(int entityId) {
		return DEPLOYING.contains(entityId);
	}

	/** True for a robot of the current Ghost Town wave. */
	public static boolean isWaveRobot(Entity e) {
		return e.entityTags().contains(ROBOT_TAG);
	}

	/** The wave number a Ghost Town robot belongs to (Robots.java: more giants each wave), else 0. */
	public static int waveOf(Entity e) {
		return isWaveRobot(e) ? wave : 0;
	}

	public static boolean lastWave(Entity e) {
		return isWaveRobot(e) && wave >= WAVES;
	}

	/**
	 * TF2's MvM wave panel (RaidState, the same one village raids use): state 1 under way,
	 * 2 defended, 3 lost, 0 nothing; wave, total, robots alive and the robots by kind.
	 */
	public static void writePanel(ServerLevel level) {
		List<Entity> alive = new ArrayList<>();
		for (UUID id : robots) {
			Entity e = level.getEntity(id);
			if (e != null && e.isAlive()) alive.add(e);
		}
		int state = active ? 1 : System.currentTimeMillis() < resultUntil ? result : 0;
		int shown = active ? wave : state != 0 ? resultWave : 0;
		if (state != lastPanel) {
			lastPanel = state;
			FortCraft.LOG.info("FortCraft: TF2 wave panel: state {} wave {}/{} robots {}", state, shown, WAVES, alive.size());
		}
		dev.fortcraft.link.FortLink.writeRaidState(state, shown, WAVES, alive.size(), dev.fortcraft.Robots.countKinds(alive));
	}

	/** One group of a wave: this many robots of one class, which comes as a pillager, vindicator... */
	private record Squad(String name, int kind, int count) {
		@Override
		public String toString() {
			return count + " " + name;
		}
	}

	private static Squad squadOf(String name, int kind, int count) {
		return new Squad(name, kind, count);
	}

	/**
	 * The six waves, each a list of groups that come one after another, like a real MvM mission
	 * (Alex, 2026-10-20: "20 scouts spawn first, then snipers, then a mix of demomen and snipers").
	 * A group starts when the one before has all come out and few robots are left alive, or after
	 * 25 s. Classes are Robots.java's kinds.
	 */
	private static final List<List<Squad>> WAVE_PLAN = List.of(
		List.of(squadOf("Scouts", 15, 10), squadOf("Soldiers", 11, 6), squadOf("Demoknights", 2, 4)),
		List.of(squadOf("Scouts", 15, 12), squadOf("Snipers", 13, 5), squadOf("Demomen", 12, 6), squadOf("Snipers", 13, 3)),
		List.of(squadOf("Soldiers", 11, 8), squadOf("Medics", 3, 2), squadOf("Scouts", 15, 12), squadOf("Demoknights", 2, 6),
			squadOf("Bowmen", 1, 4)),
		List.of(squadOf("Scouts", 15, 20), squadOf("Snipers", 13, 8), squadOf("Demomen", 12, 6), squadOf("Snipers", 13, 6),
			squadOf("Giant Heavies", 5, 2), squadOf("Medics", 3, 2)),
		List.of(squadOf("Scouts", 15, 15), squadOf("Soldiers", 11, 10), squadOf("Medics", 3, 3), squadOf("Spies", 14, 4),
			squadOf("Demomen", 12, 8), squadOf("Giant Heavies", 5, 2), squadOf("Jarate Snipers", 6, 2)),
		List.of(squadOf("Scouts", 15, 20), squadOf("Snipers", 13, 10), squadOf("Demomen", 12, 8), squadOf("Snipers", 13, 8),
			squadOf("Giant Heavies", 5, 3), squadOf("Medics", 3, 3), squadOf("Soldiers", 11, 10)));

	/** At most this many of a wave's robots are out at once (MvM keeps about 22; Minecraft copes with fewer). */
	private static final int MAX_ALIVE = 16;
	private static List<Squad> squads = List.of();
	private static int squad, spawnedInSquad, spawnedInWave, waveSize;
	private static long squadStarted, nextSpawnAt;

	/** Bring the wave's robots out, two a second, group by group. */
	private static void spawnSquads(ServerLevel level) {
		long now = System.currentTimeMillis();
		if (squad >= squads.size() || now < nextSpawnAt) return;
		Squad q = squads.get(squad);
		if (spawnedInSquad >= q.count()) {
			// The group is all out: the next comes when this one is mostly dealt with, or after 25 s.
			if (robots.size() > 4 && now - squadStarted < 25_000) return;
			squad++;
			spawnedInSquad = 0;
			squadStarted = now;
			if (squad >= squads.size()) return;
			q = squads.get(squad);
			broadcast(level, "Wave " + wave + ": " + q.count() + " " + q.name() + " incoming!");
		}
		for (int i = 0; i < 2 && spawnedInSquad < q.count() && robots.size() < MAX_ALIVE; i++) {
			EntityType<? extends Mob> type = switch (q.kind()) {
				case 2, 14, 15 -> EntityTypes.VINDICATOR;  // Demoknight, Spy, Scout
				case 3 -> EntityTypes.EVOKER;              // Medic
				case 5 -> EntityTypes.RAVAGER;             // Giant Heavy
				case 6 -> EntityTypes.WITCH;               // Jarate Sniper
				default -> EntityTypes.PILLAGER;           // Bowman, Soldier, Demoman, Sniper
			};
			int z = CoalTownMap.SPAWN_Z0 + level.getRandom().nextInt(CoalTownMap.SPAWN_Z1 - CoalTownMap.SPAWN_Z0 + 1);
			BlockPos at = CoalTownMap.street(level, CoalTownMap.SPAWN_X + level.getRandom().nextInt(4), z);
			Mob mob = type.spawn(level, at, EntitySpawnReason.EVENT);
			spawnedInSquad++;
			spawnedInWave++;
			if (mob == null) continue;
			mob.setPersistenceRequired();
			mob.addTag(ROBOT_TAG);
			mob.addTag(dev.fortcraft.Robots.FORCE_KIND_TAG + q.kind());
			robots.add(mob.getUUID());
			laneOf.put(mob.getUUID(), spawnedInWave % Math.max(1, lanes.size()));
		}
		nextSpawnAt = now + 1000;
	}

	/** The nearest living robot of this wave that isn't a Medic (the one a Medic sticks with). */
	private static Mob nearestTeammate(ServerLevel level, Mob medic) {
		Mob best = null;
		double bestD = 32 * 32;
		for (UUID id : robots) {
			if (!(level.getEntity(id) instanceof Mob m) || m == medic || !m.isAlive()) continue;
			if (dev.fortcraft.Robots.kindOf(m) == dev.fortcraft.Robots.MEDIC) continue;
			double d = m.distanceToSqr(medic);
			if (d < bestD) {
				bestD = d;
				best = m;
			}
		}
		return best;
	}

	/** Pillagers and vindicators can carry the bomb (not witches, evokers or giant Heavies). */
	private static boolean canCarry(Mob m) {
		return m.getType() == EntityTypes.VINDICATOR || m.getType() == EntityTypes.PILLAGER;
	}

	/** Give the bomb to a robot: a vindicator (Demoknight) first, since it can open doors. */
	private static Mob pickCarrier(ServerLevel level) {
		for (int pass = 0; pass < 2; pass++) for (UUID id : robots) {
			if (level.getEntity(id) instanceof Mob m && m.isAlive()
				&& (m.getType() == EntityTypes.VINDICATOR || pass == 1 && m.getType() == EntityTypes.PILLAGER)) {
				return makeCarrier(m);
			}
		}
		return null;
	}

	/**
	 * The carrier glows, wears TNT on its head (that's how TF2 knows to draw the bomb on its back,
	 * Robots.java) and walks slower than the others (Alex: "make the bomb bot slower than the rest").
	 */
	private static Mob makeCarrier(Mob m) {
		carrier = m.getUUID();
		m.addTag(BOMB_TAG);
		m.setGlowingTag(true);
		m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TNT));
		m.setDropChance(EquipmentSlot.HEAD, 0.0F);
		m.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20 * 60 * 10, 1, false, false));
		FortCraft.LOG.info("FortCraft: MvM robot {} carries the bomb", m.getId());
		return m;
	}

	/** The bomb on the ground: a TNT item nobody can pick up, which falls, and TF2 draws as MvM's bomb. */
	private static void dropBomb(ServerLevel level, net.minecraft.world.phys.Vec3 at) {
		if (at == null) return;
		removeBombItem(level);
		ItemEntity item = new ItemEntity(level, at.x, at.y + 0.5, at.z, bombStack(), 0, 0.2, 0);
		item.setNeverPickUp();
		item.setUnlimitedLifetime();
		item.addTag(BOMB_TAG);
		level.addFreshEntity(item);
		bombItem = item.getUUID();
		droppedAt = System.currentTimeMillis();
		broadcast(level, "The bomb carrier is down: the bomb is on the ground!");
		FortCraft.LOG.info("FortCraft: MvM bomb dropped at {}", at);
	}

	private static void removeBombItem(ServerLevel level) {
		if (bombItem != null && level.getEntity(bombItem) instanceof ItemEntity ie) ie.discard();
		bombItem = null;
	}

	private static final String BOMB_NAME = "MvM Bomb";

	private static ItemStack bombStack() {
		ItemStack stack = new ItemStack(Items.TNT);
		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(BOMB_NAME));
		return stack;
	}

	/** The dropped bomb's item, on the client too (Robots.java hands it to TF2 to draw). */
	public static boolean isBombItem(ItemStack stack) {
		if (!stack.is(Items.TNT)) return false;
		Component name = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
		return name != null && BOMB_NAME.equals(name.getString());
	}

	// ---------------------------------------------------------------- the robots' roads

	/**
	 * Alex's drawn route (2026-10-20): out of the robots' spawn at the top of the main street, down
	 * it, then round the big middle building on either side, both lanes meeting again at the hatch.
	 * Each lane is the walking route through its waypoints (findPath between each pair).
	 */
	private static void buildLanes(ServerLevel level) {
		lanes.clear();
		for (int[][] waypoints : new int[][][] {CoalTownMap.LANE_NORTH, CoalTownMap.LANE_SOUTH}) {
			List<BlockPos> lane = new ArrayList<>();
			BlockPos from = CoalTownMap.street(level, waypoints[0][0], waypoints[0][1]);
			lane.add(from);
			for (int i = 1; i < waypoints.length; i++) {
				BlockPos to = i == waypoints.length - 1 ? CoalTownMap.hatch(level) : CoalTownMap.street(level, waypoints[i][0], waypoints[i][1]);
				List<BlockPos> leg = findPath(level, from, to);
				if (leg.isEmpty()) {
					lane.add(to);  // no walking route found: straight there
				} else {
					lane.addAll(leg.subList(1, leg.size()));
				}
				from = lane.get(lane.size() - 1);
			}
			lanes.add(lane);
		}
		path = lanes.get(0);
	}

	/** Walk a robot a little further along its lane (to about 8 steps past the nearest spot on it). */
	private static void walkLane(Mob mob, int laneIndex, BlockPos hatch, double speed) {
		List<BlockPos> lane = lanes.isEmpty() ? List.of() : lanes.get(Math.min(laneIndex, lanes.size() - 1));
		BlockPos goal = hatch;
		if (!lane.isEmpty()) {
			int nearest = 0;
			double best = Double.MAX_VALUE;
			for (int i = 0; i < lane.size(); i++) {
				double d = lane.get(i).distSqr(mob.blockPosition());
				if (d < best) {
					best = d;
					nearest = i;
				}
			}
			goal = lane.get(Math.min(lane.size() - 1, nearest + 8));
		}
		mob.getNavigation().moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, speed);
	}

	private static void broadcast(ServerLevel level, String text) {
		FortCraft.LOG.info("FortCraft: {}", text);
		for (ServerPlayer p : level.players()) p.sendSystemMessage(Component.literal(text));
	}

	// ---------------------------------------------------------------- the bomb's road

	/**
	 * A walking route from the robots' hill to the hatch: standing spots (2 blocks of air on a solid
	 * top), one block up or up to 3 down per step, inside the town's barrier wall.
	 */
	static List<BlockPos> findPath(ServerLevel level, BlockPos from, BlockPos to) {
		Map<BlockPos, BlockPos> parent = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		parent.put(from, from);
		queue.add(from);
		BlockPos end = null;
		while (!queue.isEmpty() && parent.size() < 200_000) {
			BlockPos p = queue.poll();
			if (p.distManhattan(to) <= 2) {
				end = p;
				break;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				for (int dy = 1; dy >= -3; dy--) {
					BlockPos q = p.relative(d).above(dy);
					if (q.getX() <= CoalTownMap.WALL_X0 || q.getX() >= CoalTownMap.WALL_X1
						|| q.getZ() <= CoalTownMap.WALL_Z0 || q.getZ() >= CoalTownMap.WALL_Z1) break;
					if (!stand(level, q) || dy == 1 && !open(level, p.above(2))) continue;
					if (parent.putIfAbsent(q, p) == null) queue.add(q);
					break;
				}
			}
		}
		if (end == null) {
			FortCraft.LOG.warn("FortCraft: no walking route from {} to the hatch {} ({} spots searched)", from, to, parent.size());
			return List.of();
		}
		List<BlockPos> route = new ArrayList<>();
		for (BlockPos p = end; !p.equals(from); p = parent.get(p)) route.add(p);
		route.add(from);
		java.util.Collections.reverse(route);
		FortCraft.LOG.info("FortCraft: bomb route {} steps from {} to {}", route.size(), from, to);
		return route;
	}

	/** Air, plants, carpets... or a wooden door, which the bomb-carrying Demoknight opens. */
	private static boolean open(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		return s.getCollisionShape(level, p).isEmpty()
			|| s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && !s.is(net.minecraft.world.level.block.Blocks.IRON_DOOR);
	}

	/** Two open blocks on top of anything you can stand on (this map has stairs and slabs everywhere). */
	static boolean stand(ServerLevel level, BlockPos p) {
		BlockPos below = p.below();
		var shape = level.getBlockState(below).getCollisionShape(level, below);
		return open(level, p) && open(level, p.above()) && !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 0.5
			&& !(level.getBlockState(below).getBlock() instanceof net.minecraft.world.level.block.DoorBlock);
	}

	/** For the self-test. */
	static String state() {
		return "wave " + wave + (active ? " running, robots " + robots.size() + ", carrier " + carrier : " waiting") + ", route " + path.size();
	}

	static String carrierPos(ServerLevel level) {
		Entity e = carrier == null ? null : level.getEntity(carrier);
		return e == null ? "none" : e.blockPosition().toShortString() + " (" + e.getType().toShortString() + ")";
	}

	static void pressReadyForTest(ServerLevel level) {
		start(level);
	}

	static void carrierToHatchForTest(ServerLevel level) {
		Entity e = carrier == null ? null : level.getEntity(carrier);
		BlockPos h = CoalTownMap.hatch(level);
		if (e != null) e.teleportTo(h.getX() + 0.5, h.getY(), h.getZ() + 0.5);
	}

	static void killCarrierForTest(ServerLevel level) {
		Entity e = carrier == null ? null : level.getEntity(carrier);
		if (e != null) e.kill(level);
	}

	static String bombForTest(ServerLevel level) {
		Entity item = bombItem == null ? null : level.getEntity(bombItem);
		Entity c = carrier == null ? null : level.getEntity(carrier);
		return "bomb item " + (item == null ? "none" : item.blockPosition().toShortString() + (item.onGround() ? " on the ground" : " falling"))
			+ ", carrier " + (c == null ? "none" : c.getType().toShortString() + " at " + c.blockPosition().toShortString());
	}

	static String robotSpreadForTest(ServerLevel level) {
		StringBuilder sb = new StringBuilder();
		for (UUID id : robots) {
			Entity e = level.getEntity(id);
			if (e != null) sb.append(e.getType().toShortString()).append('@').append(e.blockPosition().toShortString())
				.append(" lane ").append(laneOf.get(id)).append("; ");
		}
		return sb.toString();
	}

	static void killAllForTest(ServerLevel level) {
		for (UUID id : robots) {
			Entity e = level.getEntity(id);
			if (e != null) e.kill(level);
		}
	}
}
