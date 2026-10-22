package dev.fortcraft.mvm;

import dev.fortcraft.FortCraft;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

/**
 * The Ghost Town from a ready-made Minecraft map of TF2's Coal Town (Alex, 2026-10-19: "map looks
 * horrible, try use this but at night"). The map is someone else's download, so it is never in
 * this repository: it is read from {@code maps/mvm_coaltown.zip} next to fabric/ (or
 * FORTCRAFT_GHOST_TOWN_MAP), and its region files are copied into the world's Ghost Town folder
 * before the world's dimensions load. It is a Minecraft 1.14.4 world; Minecraft upgrades its chunks
 * itself the first time they load.
 */
public final class CoalTownMap {
	/** RED's north spawn room (iron door at x -65): you arrive here, facing the upgrade station. */
	static final BlockPos ARRIVAL = new BlockPos(-71, 63, -20);
	static final float ARRIVAL_YAW = 90.0F;  // west, towards the enchanting table
	/** The upgrade station: an enchanting table in a full ring of bookshelves, 2 high (Alex: more). */
	static final BlockPos TABLE = new BlockPos(-75, 63, -21);
	static final int SHELF_X = -77, SHELF_Z0 = -23, SHELF_Z1 = -19;
	/** Waves: the floor button you press to start the next wave. */
	static final BlockPos READY_BUTTON = new BlockPos(-72, 63, -22);
	/** The barrier wall around the town (Alex's outline): outside these blocks you can't go. */
	static final int WALL_X0 = -88, WALL_X1 = 54, WALL_Z0 = -45, WALL_Z1 = 31;
	/** Robots come from the sand hill at the east end; the hatch is outside RED's spawn rooms. */
	static final int ROBOT_X = 46, ROBOT_Z = -5, HATCH_X = -58, HATCH_Z = -5;
	/** The way home: inside the room against its north wall, 2 wide, 3 tall. */
	static final BlockPos PORTAL = new BlockPos(-71, 63, -24);
	/** The town's buildings (the rest of the map is flat desert). */
	static final int TOWN_X0 = -90, TOWN_X1 = 56, TOWN_Z0 = -46, TOWN_Z1 = 32;

	/**
	 * The highest standing spot in this column: the street in the open, a roof under a roof (the
	 * map has mineshaft caves under the sand, so searching up from below finds those first).
	 */
	static BlockPos ground(net.minecraft.server.level.ServerLevel level, int x, int z) {
		for (int y = 100; y >= 56; y--) {
			BlockPos p = new BlockPos(x, y, z);
			if (GhostTownWaves.stand(level, p)) return p;
		}
		return new BlockPos(x, 64, z);
	}

	/**
	 * Robot lanes from Alex's drawing (2026-10-20), x/z pairs; the last one is the hatch. Both start
	 * at the top of the main street and split round the big middle building.
	 */
	static final int[][] LANE_NORTH = {{37, -5}, {34, -5}, {4, -5}, {1, -21}, {-15, -28}, {-36, -27}, {-40, -21}, {-51, -19}, {-57, -12}, {HATCH_X, HATCH_Z}};
	static final int[][] LANE_SOUTH = {{37, -5}, {34, -5}, {4, -5}, {-3, 18}, {-3, 22}, {-35, 22}, {-36, 16}, {-52, 14}, {-57, 8}, {HATCH_X, HATCH_Z}};
	/** Where robots come out: a line across the top of the main street. */
	static final int SPAWN_X = 37, SPAWN_Z0 = -18, SPAWN_Z1 = 8;

	/**
	 * The nearest open-sky street spot to this column (within 8 blocks), at street height: the
	 * drawn route crosses roofs and balconies, which the plain highest-spot search landed on.
	 */
	static BlockPos street(net.minecraft.server.level.ServerLevel level, int x, int z) {
		BlockPos best = null;
		int bestD = Integer.MAX_VALUE;
		for (int dx = -8; dx <= 8; dx++) {
			for (int dz = -8; dz <= 8; dz++) {
				int d = dx * dx + dz * dz;
				if (d >= bestD) continue;
				for (int y = 57; y <= 66; y++) {
					BlockPos p = new BlockPos(x + dx, y, z + dz);
					if (GhostTownWaves.stand(level, p) && level.canSeeSky(p)) {
						best = p;
						bestD = d;
						break;
					}
				}
			}
		}
		return best != null ? best : ground(level, x, z);
	}

	static BlockPos robotSpawn(net.minecraft.server.level.ServerLevel level) {
		return ground(level, ROBOT_X, ROBOT_Z);
	}

	static BlockPos hatch(net.minecraft.server.level.ServerLevel level) {
		return ground(level, HATCH_X, HATCH_Z);
	}

	private static final Pattern REGION = Pattern.compile("(?:.*/)?region/r\\.-?\\d+\\.-?\\d+\\.mca");

	private CoalTownMap() {
	}

	/** The Coal Town map zip, or null if the player hasn't put one there. */
	public static Path find() {
		String env = System.getenv("FORTCRAFT_GHOST_TOWN_MAP");
		if (env != null && !env.isBlank() && Files.isRegularFile(Path.of(env))) return Path.of(env);
		Path game = FabricLoader.getInstance().getGameDir();
		for (Path p : new Path[] {game.resolve("../../maps/mvm_coaltown.zip"), game.resolve("../maps/mvm_coaltown.zip"),
			game.resolve("maps/mvm_coaltown.zip")}) {
			Path n = p.toAbsolutePath().normalize();
			if (Files.isRegularFile(n)) return n;
		}
		return null;
	}

	/**
	 * Replace the dimension folder's chunks with the map's. Only called before the world's levels
	 * exist, so no chunk of this dimension is open. Returns how many region files were copied.
	 */
	static int install(Path zip, Path dimension) throws IOException {
		if (Files.exists(dimension)) {
			try (Stream<Path> walk = Files.walk(dimension)) {
				for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
			}
		}
		Path region = dimension.resolve("region");
		Files.createDirectories(region);
		int copied = 0;
		try (ZipFile z = new ZipFile(zip.toFile())) {
			for (ZipEntry e : z.stream().toList()) {
				String name = e.getName().replace('\\', '/');
				if (e.isDirectory() || !REGION.matcher(name).matches() || name.contains("DIM")) continue;
				Path out = region.resolve(name.substring(name.lastIndexOf('/') + 1));
				try (InputStream in = z.getInputStream(e)) {
					Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
				}
				copied++;
			}
		}
		FortCraft.LOG.info("FortCraft: copied {} Coal Town region files from {} into {}", copied, zip, region);
		return copied;
	}
}
