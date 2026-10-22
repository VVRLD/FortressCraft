package dev.fortcraft.mvm;

import dev.fortcraft.FortCraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * The Ghost Town dimension (fortcraft:ghost_town, defined in data/fortcraft/dimension). The first time
 * a portal is lit in a world, TF2's mvm_ghost_town.bsp is read from the player's own TF2 install
 * and turned into blocks (GhostTownMap); after that the world save keeps them. You arrive in RED's
 * spawn room facing north, with a white portal behind you that takes you back to where you came from.
 */
public final class GhostTown {
	public static final ResourceKey<Level> LEVEL = ResourceKey.create(Registries.DIMENSION,
		Identifier.fromNamespaceAndPath("fortcraft", "ghost_town"));
	/** Bump to rebuild Ghost Town in worlds built by an older converter. */
	private static final int VERSION = 1;
	/** The map's lowest block lands at this height. */
	private static final int Y_BASE = 8;
	private static final String FILE = "fortcraft_ghost_town.txt";

	private static MinecraftServer server;
	private static boolean built;
	private static BlockPos arrival;
	private static float arrivalYaw = 180.0F;
	/** "coaltown" once the Coal Town map's chunks are in this world, else the TF2-map conversion. */
	private static String source;
	private static CompletableFuture<Void> building;
	private static String problem;
	private static long lastNotice;
	/** Where each player stepped into a portal in another dimension, to go back there. */
	private static final Map<UUID, Return> returns = new HashMap<>();

	private record Return(ResourceKey<Level> level, Vec3 pos, float yaw) {
	}

	private GhostTown() {
	}

	/** True in the Ghost Town, where nothing can break blocks (Alex, 2026-10-19). */
	public static boolean isProtected(Level level) {
		return level != null && level.dimension() == LEVEL;
	}

	/** Protected, unless the block there is fire: fire can always be put out (Alex, 2026-10-19). */
	public static boolean isProtected(Level level, BlockPos pos) {
		return isProtected(level) && !(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock);
	}

	/**
	 * Which Coal Town layout this world has; a new name makes worlds copy the map again and redo
	 * FortCraft's additions. "coaltown2" (2026-10-19): barrier wall, wall torches, bookshelf ring,
	 * Ready button and hatch.
	 */
	private static final String COAL_TOWN = "coaltown2";

	/** True when this world's Ghost Town is the Coal Town map (waves need its layout). */
	public static synchronized boolean isCoalTown() {
		return COAL_TOWN.equals(source);
	}

	/**
	 * TF2 respawned the player while they were in the Ghost Town (Puppet): put them back in RED's
	 * spawn there instead of sending them to the overworld.
	 */
	public static void respawnInTown(ServerPlayer player) {
		MinecraftServer now = player.level().getServer();
		load(now);
		if (!built) prepare(now);  // the town was just re-copied and not set up yet
		ServerLevel town = now.getLevel(LEVEL);
		if (town == null || arrival == null) {
			// No spawn in the Ghost Town (2026-10-19: Alex kept "spawning in the void", at his
			// overworld bed's coordinates inside the Ghost Town): go to the normal respawn instead.
			player.teleport(TeleportTransition.createDefault(player, TeleportTransition.DO_NOTHING));
			FortCraft.LOG.warn("FortCraft: TF2 respawned but the Ghost Town has no spawn yet; back to the overworld respawn");
			return;
		}
		player.teleportTo(town, arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5, java.util.Set.of(), arrivalYaw, 0, true);
		FortCraft.LOG.info("FortCraft: TF2 respawned; Minecraft's player back to RED's spawn in the Ghost Town at {}", arrival);
	}

	/** True once this world's Ghost Town is built (or replaced by the fallback platform). */
	public static synchronized boolean ready() {
		return built;
	}

	/**
	 * Before a world's dimensions load (MinecraftServerMixin): if the Coal Town map is there and this
	 * world's Ghost Town isn't made from it yet, swap the dimension's chunks for the map's.
	 */
	public static synchronized void beforeLevels(MinecraftServer now) {
		load(now);
		if (COAL_TOWN.equals(source)) return;
		Path zip = CoalTownMap.find();
		if (zip == null) {
			FortCraft.LOG.info("FortCraft: no maps/mvm_coaltown.zip; the Ghost Town is built from TF2's map instead");
			return;
		}
		Path dimension = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(LEVEL, now.getWorldPath(LevelResource.ROOT));
		try {
			if (CoalTownMap.install(zip, dimension) == 0) return;
			source = COAL_TOWN;
			built = false;
			arrival = null;
			save(now);
		} catch (IOException e) {
			FortCraft.LOG.error("FortCraft: couldn't copy the Coal Town map into {}", dimension, e);
		}
	}

	/** Start building Ghost Town if this world doesn't have it yet (called when a portal is lit). */
	public static synchronized void prepare(MinecraftServer now) {
		if (now == null) return;
		load(now);
		if (built || building != null) return;
		ServerLevel level = now.getLevel(LEVEL);
		if (level == null) {
			problem = "the Ghost Town dimension didn't load (FortCraft's data pack is missing)";
			FortCraft.LOG.error("FortCraft: {}", problem);
			return;
		}
		if (COAL_TOWN.equals(source)) {
			decorateCoalTown(now, level);
			return;
		}
		Path bsp = Tf2Install.ghostTownMap();
		message(now, "Building the Ghost Town from TF2's map. This happens once per world.");
		building = CompletableFuture.supplyAsync(() -> {
			if (bsp == null) return null;
			try {
				long t0 = System.nanoTime();
				GhostTownMap map = GhostTownMap.load(bsp);
				FortCraft.LOG.info("FortCraft: read {} in {} ms: grid {}x{}x{}, stats {}", bsp, (System.nanoTime() - t0) / 1_000_000,
					map.sizeX, map.sizeY, map.sizeZ, map.stats);
				return map;
			} catch (IOException | RuntimeException e) {
				FortCraft.LOG.error("FortCraft: couldn't read {}", bsp, e);
				return null;
			}
		}).thenAcceptAsync(map -> place(now, level, map, bsp), now);
	}

	/**
	 * The Coal Town map's chunks are already there; add what FortCraft needs once: the upgrade
	 * station (enchanting table and bookshelves) and a white portal home in RED's spawn room, and
	 * torches along the streets so the night town isn't pitch black.
	 */
	private static void decorateCoalTown(MinecraftServer now, ServerLevel level) {
		long t0 = System.nanoTime();
		for (int cx = CoalTownMap.TOWN_X0 >> 4; cx <= CoalTownMap.TOWN_X1 >> 4; cx++) {
			for (int cz = CoalTownMap.TOWN_Z0 >> 4; cz <= CoalTownMap.TOWN_Z1 >> 4; cz++) level.getChunk(cx, cz);
		}
		BlockPos table = CoalTownMap.TABLE;
		level.setBlock(table, Blocks.ENCHANTING_TABLE.defaultBlockState(), 3);
		int shelves = 0;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				for (int y = 0; y < 2; y++) {
					BlockPos p = table.offset(dx, y, dz);
					if (ring && !(dx == 2 && dz == 0)) {  // the east middle stays open: the way in
						level.setBlock(p, Blocks.BOOKSHELF.defaultBlockState(), 3);
						shelves++;
					} else if (!p.equals(table)) {
						level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		}
		PortalFrame.build(level, CoalTownMap.PORTAL, Direction.Axis.X, 2, 3);
		level.setBlock(CoalTownMap.READY_BUTTON, Blocks.STONE_BUTTON.defaultBlockState()
			.setValue(net.minecraft.world.level.block.ButtonBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
			.setValue(net.minecraft.world.level.block.ButtonBlock.FACING, Direction.EAST), 3);
		BlockPos hatch = CoalTownMap.hatch(level);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlock(hatch.offset(dx, -1, dz), Blocks.IRON_TRAPDOOR.defaultBlockState()
					.setValue(net.minecraft.world.level.block.TrapDoorBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP), 3);
			}
		}
		int walls = placeBarrierWall(level);
		int torches = placeTorches(level);
		arrival = CoalTownMap.ARRIVAL;
		arrivalYaw = CoalTownMap.ARRIVAL_YAW;
		built = true;
		save(now);
		FortCraft.LOG.info("FortCraft: Coal Town Ghost Town ready in {} ms: upgrade station at {} with {} bookshelves, {} wall torches, {} barrier blocks, hatch {}, arrival {}",
			(System.nanoTime() - t0) / 1_000_000, CoalTownMap.TABLE, shelves, torches, walls, hatch, arrival);
		message(now, "The Ghost Town is ready. Step into the white portal.");
	}

	/** Invisible barrier walls round the town, from below the ground to well above the roofs. */
	private static int placeBarrierWall(ServerLevel level) {
		int placed = 0;
		BlockState barrier = Blocks.BARRIER.defaultBlockState();
		for (int x = CoalTownMap.WALL_X0; x <= CoalTownMap.WALL_X1; x++) {
			for (int z = CoalTownMap.WALL_Z0; z <= CoalTownMap.WALL_Z1; z++) {
				if (x != CoalTownMap.WALL_X0 && x != CoalTownMap.WALL_X1 && z != CoalTownMap.WALL_Z0 && z != CoalTownMap.WALL_Z1) continue;
				for (int y = 56; y <= 110; y++) {
					BlockPos p = new BlockPos(x, y, z);
					if (level.getBlockState(p).canBeReplaced()) {
						level.setBlock(p, barrier, 2);
						placed++;
					}
				}
			}
		}
		return placed;
	}

	/**
	 * Torches on the outside walls of buildings, at head height, facing the street, at least 7
	 * blocks apart (Alex: the first version lit the open desert like a floodlit field).
	 */
	private static int placeTorches(ServerLevel level) {
		List<BlockPos> placed = new ArrayList<>();
		for (int x = CoalTownMap.WALL_X0 + 1; x < CoalTownMap.WALL_X1; x++) {
			for (int z = CoalTownMap.WALL_Z0 + 1; z < CoalTownMap.WALL_Z1; z++) {
				BlockPos feet = CoalTownMap.ground(level, x, z);
				if (!level.canSeeSky(feet) || feet.getY() > 66) continue;  // streets only, not roofs
				BlockPos at = feet.above();
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos wall = at.relative(d);
					BlockState w = level.getBlockState(wall);
					if (w.is(Blocks.SAND) || w.is(Blocks.SANDSTONE) || w.is(Blocks.BARRIER) || !w.isFaceSturdy(level, wall, d.getOpposite())) continue;
					if (!level.getBlockState(at).isAir() || tooClose(placed, at) || nearLight(level, at)) break;
					level.setBlock(at, Blocks.WALL_TORCH.defaultBlockState()
						.setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, d.getOpposite()), 3);
					placed.add(at);
					break;
				}
			}
		}
		return placed.size();
	}

	private static boolean tooClose(List<BlockPos> placed, BlockPos p) {
		for (BlockPos q : placed) {
			if (Math.abs(q.getX() - p.getX()) < 7 && Math.abs(q.getZ() - p.getZ()) < 7 && Math.abs(q.getY() - p.getY()) < 4) return true;
		}
		return false;
	}

	private static boolean nearLight(ServerLevel level, BlockPos p) {
		for (BlockPos q : BlockPos.betweenClosed(p.offset(-3, -2, -3), p.offset(3, 2, 3))) {
			if (level.getBlockState(q).getLightEmission() >= 10) return true;
		}
		return false;
	}

	/** Runs on the server thread once the map is converted (or couldn't be). */
	private static void place(MinecraftServer now, ServerLevel level, GhostTownMap map, Path bsp) {
		long t0 = System.nanoTime();
		if (map == null) {
			problem = bsp == null
				? "mvm_ghost_town.bsp wasn't found in your Team Fortress 2 install"
				: "TF2's Ghost Town map couldn't be read (see latest.log)";
			arrival = new BlockPos(0, 65, 0);
			for (int x = -4; x <= 4; x++) {
				for (int z = -4; z <= 4; z++) level.setBlock(arrival.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
			}
			PortalFrame.build(level, arrival.offset(-1, 0, 2), Direction.Axis.X, 2, 3);
			message(now, "Couldn't build the Ghost Town: " + problem + ". You'll get a stone platform with a way home.");
		} else {
			int dy = Y_BASE - map.minY;
			List<BlockState> states = new ArrayList<>();
			for (String name : map.palette) {
				Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(name));
				states.add(block == null ? Blocks.STONE.defaultBlockState() : block.defaultBlockState());
			}
			int placed = 0;
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int y = 0; y < map.sizeY; y++) {
				for (int z = 0; z < map.sizeZ; z++) {
					for (int x = 0; x < map.sizeX; x++) {
						byte c = map.cells[(y * map.sizeZ + z) * map.sizeX + x];
						if (c == 0) continue;
						pos.set(x + map.minX, y + map.minY + dy, z + map.minZ);
						level.setBlock(pos, states.get(c & 0xFF), 2 | 16);
						placed++;
					}
				}
			}
			BlockState light = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 13);
			for (int[] p : map.lights) {
				pos.set(p[0], p[1] + dy, p[2]);
				if (level.getBlockState(pos).isAir()) level.setBlock(pos, light, 2 | 16);
			}
			int[] spawn = map.redSpawns.isEmpty() ? new int[] {0, map.sizeY / 2 + map.minY, 0} : map.redSpawns.get(0);
			arrival = new BlockPos(spawn[0], spawn[1] + dy, spawn[2]);
			// The way home: a white portal two blocks behind where you arrive, facing the robots' side
			// (north). Not around you: standing in it would send a creative player straight back.
			PortalFrame.build(level, arrival.offset(-1, 0, 2), Direction.Axis.X, 2, 3);
			level.setBlock(arrival, Blocks.AIR.defaultBlockState(), 2 | 16);
			level.setBlock(arrival.above(), Blocks.AIR.defaultBlockState(), 2 | 16);
			FortCraft.LOG.info("FortCraft: placed {} Ghost Town blocks in {} ms; arrival {}; hatch {}", placed,
				(System.nanoTime() - t0) / 1_000_000, arrival, map.hatch == null ? "none" : map.hatch[0] + "," + (map.hatch[1] + dy) + "," + map.hatch[2]);
			message(now, "The Ghost Town is ready. Step into the white portal.");
		}
		built = true;
		save(now);
		synchronized (GhostTown.class) {
			building = null;
		}
	}

	/** Where a portal takes this entity: into the Ghost Town, or back to where the player came from. */
	public static TeleportTransition destination(ServerLevel from, Entity entity, BlockPos portal) {
		if (!(entity instanceof ServerPlayer player)) return null;
		MinecraftServer now = from.getServer();
		load(now);
		if (from.dimension() == LEVEL) {
			Return back = returns.remove(player.getUUID());
			save(now);
			ServerLevel level = back == null ? null : now.getLevel(back.level());
			if (level == null) return TeleportTransition.createDefault(player, TeleportTransition.PLAY_PORTAL_SOUND);
			FortCraft.LOG.info("FortCraft: Ghost Town portal: back to {} {}", back.level().identifier(), back.pos());
			return new TeleportTransition(level, back.pos(), Vec3.ZERO, back.yaw(), 0, TeleportTransition.PLAY_PORTAL_SOUND);
		}
		ServerLevel town = now.getLevel(LEVEL);
		if (!built || town == null) {
			prepare(now);
			long t = System.currentTimeMillis();
			if (t - lastNotice > 4000) {
				lastNotice = t;
				player.sendSystemMessage(Component.literal(problem != null && town == null
					? "The Ghost Town can't open: " + problem : "The Ghost Town is still being built..."));
			}
			return null;
		}
		returns.put(player.getUUID(), new Return(from.dimension(), stepOut(from, portal, player.position()), player.getYRot()));
		save(now);
		FortCraft.LOG.info("FortCraft: Ghost Town portal: {} enters at {}", player.getName().getString(), arrival);
		return new TeleportTransition(town, Vec3.atBottomCenterOf(arrival), Vec3.ZERO, arrivalYaw, 0,
			TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET));
	}

	/** A spot just in front of or behind the portal, so coming home doesn't send you straight back. */
	private static Vec3 stepOut(ServerLevel level, BlockPos portal, Vec3 at) {
		BlockState state = level.getBlockState(portal);
		Direction.Axis axis = state.hasProperty(NetherPortalBlock.AXIS) ? state.getValue(NetherPortalBlock.AXIS) : Direction.Axis.X;
		Direction out = axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
		BlockPos feet = BlockPos.containing(at);
		for (Direction d : new Direction[] {out, out.getOpposite()}) {
			BlockPos p = feet.relative(d);
			if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()
				&& level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
				return new Vec3(p.getX() + 0.5, at.y, p.getZ() + 0.5);
			}
		}
		return at;
	}

	private static void message(MinecraftServer now, String text) {
		FortCraft.LOG.info("FortCraft: {}", text);
		now.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
	}

	// ---------------------------------------------------------------- saved with the world

	private static Path file(MinecraftServer now) {
		return now.getWorldPath(LevelResource.ROOT).resolve(FILE);
	}

	/** Read this world's Ghost Town state once per world. */
	private static synchronized void load(MinecraftServer now) {
		if (server == now) return;
		server = now;
		built = false;
		arrival = null;
		arrivalYaw = 180.0F;
		source = null;
		problem = null;
		returns.clear();
		Path f = file(now);
		if (!Files.exists(f)) return;
		try {
			for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
				String[] p = line.trim().split(" ");
				if (p[0].equals("version") && p.length == 2 && Integer.parseInt(p[1]) == VERSION) built = true;
				if (p[0].equals("source") && p.length == 2) source = p[1];
				if (p[0].equals("yaw") && p.length == 2) arrivalYaw = Float.parseFloat(p[1]);
				if (p[0].equals("arrival") && p.length == 4) arrival = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
				if (p[0].equals("return") && p.length == 7) {
					returns.put(UUID.fromString(p[1]), new Return(ResourceKey.create(Registries.DIMENSION, Identifier.parse(p[2])),
						new Vec3(Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5])), Float.parseFloat(p[6])));
				}
			}
			if (arrival == null) built = false;
			FortCraft.LOG.info("FortCraft: Ghost Town state: source={} built={} arrival={} returns={}", source, built, arrival, returns.size());
		} catch (IOException | RuntimeException e) {
			FortCraft.LOG.warn("FortCraft: couldn't read {}", f, e);
			built = false;
		}
	}

	private static void save(MinecraftServer now) {
		Path f = file(now);
		StringBuilder sb = new StringBuilder();
		if (source != null) sb.append("source ").append(source).append('\n');
		if (built && arrival != null) {
			sb.append("yaw ").append(arrivalYaw).append('\n');
			sb.append("version ").append(VERSION).append('\n');
			sb.append("arrival ").append(arrival.getX()).append(' ').append(arrival.getY()).append(' ').append(arrival.getZ()).append('\n');
		}
		returns.forEach((id, r) -> sb.append("return ").append(id).append(' ').append(r.level().identifier()).append(' ')
			.append(r.pos().x).append(' ').append(r.pos().y).append(' ').append(r.pos().z).append(' ').append(r.yaw()).append('\n'));
		try {
			Path temp = f.resolveSibling(FILE + ".tmp");
			Files.writeString(temp, sb.toString(), StandardCharsets.UTF_8);
			Files.move(temp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			FortCraft.LOG.warn("FortCraft: couldn't save {}", f, e);
		}
	}
}
