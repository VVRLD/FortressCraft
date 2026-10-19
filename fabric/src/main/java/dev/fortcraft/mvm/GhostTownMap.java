package dev.fortcraft.mvm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns TF2's mvm_ghost_town.bsp, read from the player's own TF2 install at runtime, into a grid of
 * Minecraft block names (1 block = 48 TF2 units, the project scale). Nothing from the map is stored
 * in this repository; the result only goes into the player's world save.
 *
 * <p>Pure Java (no Minecraft classes), so it can be run on its own:
 * {@code java GhostTownMap.java <path to bsp> [out.png]} prints counts and draws a top-down picture.
 *
 * <p>What it converts: the world's solid brushes (walls, floors, roofs, bridges), its displacements
 * (the hilly ground), player-clip brushes (as invisible barrier blocks), and light entities (as
 * Minecraft light blocks). Not converted yet: props (trees, rocks, gravestones, fences, most cliff
 * faces), because their shapes live in model files.
 */
public final class GhostTownMap {
	public static final double UNITS = 48.0;

	/** Block names used in the grid; index 0 is air. */
	public final List<String> palette = new ArrayList<>();
	private final Map<String, Integer> paletteIndex = new HashMap<>();
	/** Grid of palette indices, x fastest, then z, then y. */
	public byte[] cells;
	public int minX, minY, minZ, sizeX, sizeY, sizeZ;
	/** Map spots in Minecraft block coordinates (before the world's offset). */
	public final List<int[]> redSpawns = new ArrayList<>();
	public final List<int[]> botSpawns = new ArrayList<>();
	public final List<int[]> bossPath = new ArrayList<>();
	public final List<int[]> lights = new ArrayList<>();
	public int[] hatch;
	public final Map<String, Integer> stats = new LinkedHashMap<>();

	private ByteBuffer buf;
	private final int[][] lumps = new int[64][2];
	private float[][] planes;   // nx, ny, nz, dist
	private int[][] brushes;    // firstside, numsides, contents
	private int[][] sides;      // plane, texinfo, dispinfo, bevel
	private int[] texinfoTexdata;
	private String[] texdataName;
	private float[][] verts;
	private int[][] edges;
	private int[] surfedges;
	private String entities;

	private GhostTownMap() {
		palette.add("minecraft:air");
		paletteIndex.put("minecraft:air", 0);
	}

	public static GhostTownMap load(Path bsp) throws IOException {
		GhostTownMap map = new GhostTownMap();
		map.read(Files.readAllBytes(bsp));
		map.convert();
		return map;
	}

	// ---------------------------------------------------------------- reading the BSP

	private void read(byte[] data) throws IOException {
		buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		if (buf.getInt(0) != 0x50534256) throw new IOException("not a Source map (VBSP)");
		for (int i = 0; i < 64; i++) {
			lumps[i][0] = buf.getInt(8 + 16 * i);
			lumps[i][1] = buf.getInt(12 + 16 * i);
			if (buf.getInt(20 + 16 * i) != 0) throw new IOException("compressed lump " + i + " is not supported");
		}
		planes = new float[count(1, 20)][];
		for (int i = 0; i < planes.length; i++) {
			int o = lumps[1][0] + i * 20;
			planes[i] = new float[] {buf.getFloat(o), buf.getFloat(o + 4), buf.getFloat(o + 8), buf.getFloat(o + 12)};
		}
		brushes = new int[count(18, 12)][];
		for (int i = 0; i < brushes.length; i++) {
			int o = lumps[18][0] + i * 12;
			brushes[i] = new int[] {buf.getInt(o), buf.getInt(o + 4), buf.getInt(o + 8)};
		}
		sides = new int[count(19, 8)][];
		for (int i = 0; i < sides.length; i++) {
			int o = lumps[19][0] + i * 8;
			sides[i] = new int[] {buf.getShort(o) & 0xFFFF, buf.getShort(o + 2), buf.getShort(o + 4), buf.get(o + 6)};
		}
		texinfoTexdata = new int[count(6, 72)];
		for (int i = 0; i < texinfoTexdata.length; i++) {
			texinfoTexdata[i] = buf.getInt(lumps[6][0] + i * 72 + 68);
		}
		int stringTable = lumps[44][0];
		texdataName = new String[count(2, 32)];
		for (int i = 0; i < texdataName.length; i++) {
			int id = buf.getInt(lumps[2][0] + i * 32 + 12);
			int start = lumps[43][0] + buf.getInt(stringTable + id * 4);
			int end = start;
			while (buf.get(end) != 0) end++;
			texdataName[i] = new String(data, start, end - start, StandardCharsets.US_ASCII).toUpperCase();
		}
		verts = new float[count(3, 12)][];
		for (int i = 0; i < verts.length; i++) {
			int o = lumps[3][0] + i * 12;
			verts[i] = new float[] {buf.getFloat(o), buf.getFloat(o + 4), buf.getFloat(o + 8)};
		}
		edges = new int[count(12, 4)][];
		for (int i = 0; i < edges.length; i++) {
			int o = lumps[12][0] + i * 4;
			edges[i] = new int[] {buf.getShort(o) & 0xFFFF, buf.getShort(o + 2) & 0xFFFF};
		}
		surfedges = new int[count(13, 4)];
		for (int i = 0; i < surfedges.length; i++) surfedges[i] = buf.getInt(lumps[13][0] + i * 4);
		entities = new String(data, lumps[0][0], lumps[0][1], StandardCharsets.ISO_8859_1);
	}

	private int count(int lump, int size) {
		return lumps[lump][1] / size;
	}

	private String material(int texinfo) {
		if (texinfo < 0 || texinfo >= texinfoTexdata.length) return "";
		int td = texinfoTexdata[texinfo];
		return td < 0 || td >= texdataName.length ? "" : texdataName[td];
	}

	/** Brushes of the world itself (model 0), not of brush entities such as triggers and doors. */
	private boolean[] worldBrushes() {
		boolean[] result = new boolean[brushes.length];
		int head = buf.getInt(lumps[14][0] + 36);
		ArrayList<Integer> stack = new ArrayList<>();
		stack.add(head);
		while (!stack.isEmpty()) {
			int node = stack.remove(stack.size() - 1);
			if (node >= 0) {
				int o = lumps[5][0] + node * 32;
				stack.add(buf.getInt(o + 4));
				stack.add(buf.getInt(o + 8));
			} else {
				int o = lumps[10][0] + (-1 - node) * 32;
				int first = buf.getShort(o + 24) & 0xFFFF;
				int n = buf.getShort(o + 26) & 0xFFFF;
				for (int k = 0; k < n; k++) {
					int b = buf.getShort(lumps[17][0] + (first + k) * 2) & 0xFFFF;
					if (b < result.length) result[b] = true;
				}
			}
		}
		return result;
	}

	// ---------------------------------------------------------------- converting

	private static final int SOLID = 0x1, WINDOW = 0x2, GRATE = 0x8, PLAYERCLIP = 0x10000;

	private record Brush(int index, double[] lo, double[] hi, double[][] planes, double[] tolerance, String block) {
	}

	private record Surface(double[][] tri, String block, boolean ground) {
	}

	private void convert() {
		boolean[] world = worldBrushes();
		List<Brush> solids = new ArrayList<>();
		List<double[][]> skyBoxes = new ArrayList<>();
		for (int b = 0; b < brushes.length; b++) {
			if (!world[b]) continue;
			int contents = brushes[b][2];
			String block = blockFor(b, contents);
			boolean sky = false;
			for (int s = brushes[b][0]; s < brushes[b][0] + brushes[b][1]; s++) {
				if (material(sides[s][1]).contains("TOOLSSKYBOX")) sky = true;
			}
			Brush brush = shape(b, block);
			if (brush == null) continue;
			if (sky) {
				skyBoxes.add(new double[][] {brush.lo(), brush.hi()});
				bump("sky brushes skipped");
				continue;
			}
			if (block == null) {
				bump("non-solid brushes skipped");
				continue;
			}
			solids.add(brush);
		}
		double[][] skyRoom = skyRoom(skyBoxes);
		List<Surface> ground = displacements();

		// Grid bounds from everything that is kept.
		double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
		List<Brush> kept = new ArrayList<>();
		for (Brush brush : solids) {
			if (inside(skyRoom, brush.lo()) && inside(skyRoom, brush.hi())) {
				bump("3D skybox brushes skipped");
				continue;
			}
			kept.add(brush);
			grow(lo, hi, brush.lo());
			grow(lo, hi, brush.hi());
		}
		List<Surface> keptGround = new ArrayList<>();
		for (Surface surface : ground) {
			if (inside(skyRoom, surface.tri()[0])) continue;
			keptGround.add(surface);
			for (double[] p : surface.tri()) grow(lo, hi, p);
		}
		minX = cell(lo[0]) - 1;
		minZ = cell(-hi[1]) - 1;
		minY = cell(lo[2]) - 1;
		sizeX = cell(hi[0]) + 2 - minX;
		sizeZ = cell(-lo[1]) + 2 - minZ;
		sizeY = cell(hi[2]) + 2 - minY;
		cells = new byte[sizeX * sizeY * sizeZ];
		stats.put("grid", sizeX * 1000000 + sizeZ * 1000 + sizeY);

		for (Brush brush : kept) fill(brush);
		boolean[] groundTop = new boolean[cells.length];
		for (Surface surface : keptGround) paint(surface, groundTop);
		fillBelowGround(groundTop);
		readEntities(skyRoom);
	}

	/** The Minecraft block for one brush, or null if it isn't solid to players. */
	private String blockFor(int b, int contents) {
		if ((contents & SOLID) == 0) {
			if ((contents & WINDOW) != 0) return "minecraft:glass";
			if ((contents & GRATE) != 0) return "minecraft:iron_bars";
			if ((contents & PLAYERCLIP) != 0) return "minecraft:barrier";
			return null;
		}
		Map<String, Integer> counts = new HashMap<>();
		for (int s = brushes[b][0]; s < brushes[b][0] + brushes[b][1]; s++) {
			if (sides[s][3] != 0) continue;  // bevel planes have no texture of their own
			String m = material(sides[s][1]);
			if (m.startsWith("TOOLS/") || m.isEmpty()) continue;
			counts.merge(m, 1, Integer::sum);
		}
		String best = null;
		int most = 0;
		for (var e : counts.entrySet()) {
			if (e.getValue() > most) {
				most = e.getValue();
				best = e.getKey();
			}
		}
		if (best == null) {
			for (int s = brushes[b][0]; s < brushes[b][0] + brushes[b][1]; s++) {
				if (material(sides[s][1]).contains("TOOLSBLACK")) return "minecraft:black_concrete";
			}
			return "minecraft:stone";  // nodraw-only: hidden but solid in TF2
		}
		return blockForMaterial(best);
	}

	/** TF2 material name (upper case) to a Minecraft block that looks roughly like it. */
	static String blockForMaterial(String m) {
		if (m.contains("BURNT")) return "minecraft:stripped_dark_oak_log";
		if (m.contains("TRIM") || m.contains("BEAM") && m.contains("WOOD")) return "minecraft:spruce_log";
		if (m.contains("GRAYWORN") || m.contains("GREYWORN")) return "minecraft:pale_oak_planks";
		if (m.contains("YELLOWWORN")) return "minecraft:oak_planks";
		if (m.contains("FLOOR") && m.contains("WOOD") || m.contains("BRIDGE") && m.contains("WOOD")) return "minecraft:spruce_planks";
		if (m.contains("WOOD")) return "minecraft:dark_oak_planks";
		if (m.contains("IBEAM") || m.contains("BRIDGEBEAM") || m.contains("RED_BEAM")) return "minecraft:polished_deepslate";
		if (m.contains("FLOORPLATE")) return "minecraft:polished_andesite";
		if (m.contains("METAL")) return "minecraft:gray_concrete";
		if (m.contains("BRICK")) return "minecraft:bricks";
		if (m.contains("STONEWALL")) return "minecraft:stone_bricks";
		if (m.contains("CONCRETE")) return "minecraft:smooth_stone";
		if (m.contains("ROCK") || m.contains("CLIFF")) return "minecraft:stone";
		if (m.contains("GRASS")) return "minecraft:grass_block";
		if (m.contains("DIRT") || m.contains("GROUND") || m.contains("MUD")) return "minecraft:coarse_dirt";
		if (m.contains("SNOW")) return "minecraft:snow_block";
		if (m.contains("GLASS") || m.contains("WINDOW")) return "minecraft:glass";
		return "minecraft:cobblestone";
	}

	/** A brush's bounding box, planes, corners and how much each plane is pushed out (thin brushes). */
	private Brush shape(int b, String block) {
		int first = brushes[b][0], n = brushes[b][1];
		double[][] p = new double[n][];
		for (int k = 0; k < n; k++) {
			float[] pl = planes[sides[first + k][0]];
			p[k] = new double[] {pl[0], pl[1], pl[2], pl[3]};
		}
		List<double[]> corners = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				for (int k = j + 1; k < n; k++) {
					double[] v = intersect(p[i], p[j], p[k]);
					if (v == null) continue;
					boolean in = true;
					for (double[] q : p) {
						if (q[0] * v[0] + q[1] * v[1] + q[2] * v[2] - q[3] > 0.05) {
							in = false;
							break;
						}
					}
					if (in) corners.add(v);
				}
			}
		}
		if (corners.size() < 4) return null;
		double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
		for (double[] v : corners) grow(lo, hi, v);
		double[] tol = new double[n];
		for (int k = 0; k < n; k++) {
			double min = 1e9;
			for (double[] v : corners) min = Math.min(min, p[k][0] * v[0] + p[k][1] * v[1] + p[k][2] * v[2]);
			double thickness = p[k][3] - min;
			double need = UNITS * Math.max(Math.abs(p[k][0]), Math.max(Math.abs(p[k][1]), Math.abs(p[k][2])));
			// Thin brushes are widened to one full block so walls and floors don't vanish. Not
			// player clips: those are often thin smoothing strips beside doorways, and widened they
			// close the doorway (RED's spawn exits did).
			tol[k] = block != null && block.equals("minecraft:barrier") ? 0 : Math.max(0, (need - thickness) / 2 + 0.5);
		}
		return new Brush(b, lo, hi, p, tol, block);
	}

	private static double[] intersect(double[] a, double[] b, double[] c) {
		double[] bc = cross(b, c);
		double det = a[0] * bc[0] + a[1] * bc[1] + a[2] * bc[2];
		if (Math.abs(det) < 1e-6) return null;
		double[] ca = cross(c, a), ab = cross(a, b);
		double[] v = new double[3];
		for (int i = 0; i < 3; i++) v[i] = (a[3] * bc[i] + b[3] * ca[i] + c[3] * ab[i]) / det;
		return v;
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	/**
	 * Fill one brush. Its box sides are snapped to the nearest block edge (a floor whose top is at 536
	 * units stands at 528, the block edge at y=11), so floors, door frames and thresholds keep their
	 * heights and doorways their width. A side thinner than half a block still gets one block: a
	 * floor keeps its top, a wall its middle. Slanted planes (roofs, ramps) use the block's centre.
	 */
	private void fill(Brush brush) {
		byte id = id(brush.block());
		int[] lo = new int[3], hi = new int[3];
		for (int a = 0; a < 3; a++) {
			lo[a] = (int) Math.round(brush.lo()[a] / UNITS);
			hi[a] = (int) Math.round(brush.hi()[a] / UNITS);
			if (hi[a] <= lo[a]) {
				if (a == 2) {
					lo[a] = hi[a] - 1;
				} else {
					lo[a] = (int) Math.floor((brush.lo()[a] + brush.hi()[a]) / 2 / UNITS);
					hi[a] = lo[a] + 1;
				}
			}
		}
		int count = 0;
		// TF2 cell (i, j, k) is Minecraft block (i, k, -j - 1).
		for (int k = lo[2]; k < hi[2]; k++) {
			for (int j = lo[1]; j < hi[1]; j++) {
				for (int i = lo[0]; i < hi[0]; i++) {
					double cx = (i + 0.5) * UNITS, cy = (j + 0.5) * UNITS, cz = (k + 0.5) * UNITS;
					boolean in = true;
					for (int n = 0; n < brush.planes().length; n++) {
						double[] q = brush.planes()[n];
						if (Math.abs(q[0]) > 0.999 || Math.abs(q[1]) > 0.999 || Math.abs(q[2]) > 0.999) continue;
						if (q[0] * cx + q[1] * cy + q[2] * cz - q[3] > brush.tolerance()[n]) {
							in = false;
							break;
						}
					}
					if (!in) continue;
					int idx = index(i, k, -j - 1);
					if (idx < 0) continue;
					// Real blocks win over barriers and glass where they overlap.
					if (cells[idx] != 0 && !replaceable(cells[idx])) continue;
					cells[idx] = id;
					count++;
				}
			}
		}
		bump(brush.block());
		stats.merge("cells " + brush.block(), count, Integer::sum);
	}

	private boolean replaceable(byte id) {
		String name = palette.get(id);
		return name.equals("minecraft:barrier") || name.equals("minecraft:glass") || name.equals("minecraft:iron_bars");
	}

	// ---------------------------------------------------------------- displacements (hilly ground)

	private List<Surface> displacements() {
		List<Surface> out = new ArrayList<>();
		int n = count(26, 176);
		for (int d = 0; d < n; d++) {
			int o = lumps[26][0] + d * 176;
			double[] start = {buf.getFloat(o), buf.getFloat(o + 4), buf.getFloat(o + 8)};
			int vertStart = buf.getInt(o + 12);
			int power = buf.getInt(o + 20);
			int face = buf.getShort(o + 36) & 0xFFFF;
			int fo = lumps[7][0] + face * 56;
			int planeNum = buf.getShort(fo) & 0xFFFF;
			int firstEdge = buf.getInt(fo + 4);
			int numEdges = buf.getShort(fo + 8);
			int texinfo = buf.getShort(fo + 10);
			if (numEdges != 4) continue;
			double[][] corner = new double[4][];
			for (int k = 0; k < 4; k++) {
				int se = surfedges[firstEdge + k];
				int v = se >= 0 ? edges[se][0] : edges[-se][1];
				corner[k] = new double[] {verts[v][0], verts[v][1], verts[v][2]};
			}
			int nearest = 0;
			double best = 1e18;
			for (int k = 0; k < 4; k++) {
				double dd = dist2(corner[k], start);
				if (dd < best) {
					best = dd;
					nearest = k;
				}
			}
			double[][] c = new double[4][];
			for (int k = 0; k < 4; k++) c[k] = corner[(nearest + k) % 4];
			int size = (1 << power) + 1;
			double[][] pos = new double[size * size][];
			for (int y = 0; y < size; y++) {
				double t = y / (double) (size - 1);
				double[] left = lerp(c[0], c[1], t), right = lerp(c[3], c[2], t);
				for (int x = 0; x < size; x++) {
					double[] p = lerp(left, right, x / (double) (size - 1));
					int vo = lumps[33][0] + (vertStart + y * size + x) * 20;
					double distance = buf.getFloat(vo + 12);
					p[0] += buf.getFloat(vo) * distance;
					p[1] += buf.getFloat(vo + 4) * distance;
					p[2] += buf.getFloat(vo + 8) * distance;
					pos[y * size + x] = p;
				}
			}
			String block = groundBlock(material(texinfo));
			boolean isGround = planes[planeNum][2] > 0.3f;
			for (int y = 0; y < size - 1; y++) {
				for (int x = 0; x < size - 1; x++) {
					double[] a = pos[y * size + x], b = pos[y * size + x + 1];
					double[] e = pos[(y + 1) * size + x], f = pos[(y + 1) * size + x + 1];
					out.add(new Surface(new double[][] {a, b, f}, block, isGround));
					out.add(new Surface(new double[][] {a, f, e}, block, isGround));
				}
			}
			bump("displacements");
		}
		return out;
	}

	private static String groundBlock(String m) {
		if (m.contains("ROCK") || m.contains("CLIFF") || m.contains("STONE")) return "minecraft:stone";
		if (m.contains("SNOW")) return "minecraft:snow_block";
		if (m.contains("GRASS")) return "minecraft:grass_block";
		if (m.contains("DIRT") || m.contains("MUD") || m.contains("GROUND")) return "minecraft:coarse_dirt";
		return "minecraft:grass_block";
	}

	private void paint(Surface s, boolean[] groundTop) {
		double[][] t = s.tri();
		double longest = Math.max(Math.sqrt(dist2(t[0], t[1])), Math.max(Math.sqrt(dist2(t[1], t[2])), Math.sqrt(dist2(t[0], t[2]))));
		int steps = Math.max(1, (int) Math.ceil(longest / 12.0));
		byte id = id(s.block());
		for (int i = 0; i <= steps; i++) {
			for (int j = 0; j <= steps - i; j++) {
				double u = i / (double) steps, v = j / (double) steps, w = 1 - u - v;
				double px = t[0][0] * w + t[1][0] * u + t[2][0] * v;
				double py = t[0][1] * w + t[1][1] * u + t[2][1] * v;
				double pz = t[0][2] * w + t[1][2] * u + t[2][2] * v;
				int idx = index(cell(px), cell(pz), cell(-py));
				if (idx < 0 || cells[idx] != 0 && !replaceable(cells[idx])) continue;
				cells[idx] = id;
				if (s.ground()) groundTop[idx] = true;
			}
		}
	}

	/**
	 * Ground displacements are only a skin in TF2. Give them two blocks of dirt underneath, stopping
	 * at anything solid. Not deeper: ground drawn over a roof (RED's spawn) filled the room below.
	 */
	private void fillBelowGround(boolean[] groundTop) {
		byte dirt = id("minecraft:dirt");
		int filled = 0;
		for (int z = 0; z < sizeZ; z++) {
			for (int x = 0; x < sizeX; x++) {
				for (int y = sizeY - 1; y > 0; y--) {
					if (!groundTop[(y * sizeZ + z) * sizeX + x]) continue;
					for (int yy = y - 1; yy >= Math.max(0, y - 2); yy--) {
						int i = (yy * sizeZ + z) * sizeX + x;
						if (cells[i] != 0 && !replaceable(cells[i]) || groundTop[i]) break;
						cells[i] = dirt;
						filled++;
					}
				}
			}
		}
		stats.put("cells under ground", filled);
	}

	// ---------------------------------------------------------------- entities

	private void readEntities(double[][] skyRoom) {
		Matcher block = Pattern.compile("\\{([^{}]*)\\}").matcher(entities);
		Pattern kv = Pattern.compile("\"([^\"]+)\"\\s+\"([^\"]*)\"");
		while (block.find()) {
			Map<String, String> e = new HashMap<>();
			Matcher m = kv.matcher(block.group(1));
			while (m.find()) e.put(m.group(1), m.group(2));
			String cls = e.getOrDefault("classname", "");
			double[] origin = parse(e.get("origin"));
			if (origin != null && inside(skyRoom, origin)) continue;
			switch (cls) {
				case "info_player_teamspawn" -> {
					if (origin == null) break;
					if ("2".equals(e.get("TeamNum"))) redSpawns.add(toBlock(origin));
					else if ("3".equals(e.get("TeamNum"))) botSpawns.add(toBlock(origin));
				}
				case "path_track" -> {
					if (origin != null && e.getOrDefault("targetname", "").startsWith("boss_path")) bossPath.add(toBlock(origin));
				}
				case "light", "light_spot" -> {
					if (origin != null) lights.add(toBlock(origin));
				}
				case "func_capturezone" -> {
					String model = e.getOrDefault("model", "");
					if (model.startsWith("*")) {
						int mo = lumps[14][0] + Integer.parseInt(model.substring(1)) * 48;
						double[] mid = {
							(buf.getFloat(mo) + buf.getFloat(mo + 12)) / 2,
							(buf.getFloat(mo + 4) + buf.getFloat(mo + 16)) / 2,
							buf.getFloat(mo + 8)};
						hatch = toBlock(mid);
					}
				}
				default -> {
				}
			}
		}
		stats.put("red spawns", redSpawns.size());
		stats.put("bot spawns", botSpawns.size());
		stats.put("lights", lights.size());
	}

	private static double[] parse(String s) {
		if (s == null) return null;
		String[] p = s.trim().split("\\s+");
		if (p.length != 3) return null;
		try {
			return new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	/** TF2 point to Minecraft block coordinates: x stays x, TF2's up (z) is y, TF2's y is -z. */
	public static int[] toBlock(double[] p) {
		return new int[] {cell(p[0]), cell(p[2]), cell(-p[1])};
	}

	// ---------------------------------------------------------------- the 3D skybox

	/**
	 * TF2 maps hold a small model of the far scenery (the "3D skybox", 1/16 size) in a sealed room
	 * around the sky_camera entity. Find that room from the sky brushes around the camera.
	 */
	private double[][] skyRoom(List<double[][]> skyBoxes) {
		Matcher m = Pattern.compile("\\{[^{}]*\"classname\"\\s+\"sky_camera\"[^{}]*\\}").matcher(entities);
		if (!m.find()) return null;
		Matcher o = Pattern.compile("\"origin\"\\s+\"([^\"]*)\"").matcher(m.group());
		if (!o.find()) return null;
		double[] cam = parse(o.group(1));
		if (cam == null) return null;
		// The walls nearest the camera along each axis (a sky floor is often missing); the room is
		// the box around all of them.
		double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
		int walls = 0;
		for (int axis = 0; axis < 3; axis++) {
			for (int dir = -1; dir <= 1; dir += 2) {
				double[][] nearest = null;
				double best = 1e9;
				for (double[][] box : skyBoxes) {
					boolean overlaps = true;
					for (int a = 0; a < 3; a++) {
						if (a != axis && (cam[a] < box[0][a] || cam[a] > box[1][a])) overlaps = false;
					}
					double gap = dir < 0 ? cam[axis] - box[1][axis] : box[0][axis] - cam[axis];
					if (overlaps && gap >= 0 && gap < best) {
						best = gap;
						nearest = box;
					}
				}
				if (nearest == null) continue;
				walls++;
				grow(lo, hi, nearest[0]);
				grow(lo, hi, nearest[1]);
			}
		}
		if (walls < 4) return null;
		for (int a = 0; a < 3; a++) {
			lo[a] -= 64;
			hi[a] += 64;
		}
		grow(lo, hi, cam);
		return new double[][] {lo, hi};
	}

	// ---------------------------------------------------------------- helpers

	private static boolean inside(double[][] box, double[] p) {
		if (box == null) return false;
		for (int a = 0; a < 3; a++) {
			if (p[a] < box[0][a] || p[a] > box[1][a]) return false;
		}
		return true;
	}

	private static void grow(double[] lo, double[] hi, double[] p) {
		for (int a = 0; a < 3; a++) {
			lo[a] = Math.min(lo[a], p[a]);
			hi[a] = Math.max(hi[a], p[a]);
		}
	}

	private static double dist2(double[] a, double[] b) {
		double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
		return x * x + y * y + z * z;
	}

	private static double[] lerp(double[] a, double[] b, double t) {
		return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
	}

	public static int cell(double units) {
		return (int) Math.floor(units / UNITS);
	}

	/** Index into cells for Minecraft block coordinates, or -1 outside the grid. */
	public int index(int x, int y, int z) {
		x -= minX;
		y -= minY;
		z -= minZ;
		if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return -1;
		return (y * sizeZ + z) * sizeX + x;
	}

	private byte id(String block) {
		Integer i = paletteIndex.get(block);
		if (i == null) {
			i = palette.size();
			palette.add(block);
			paletteIndex.put(block, i);
		}
		return (byte) (int) i;
	}

	private void bump(String key) {
		stats.merge(key, 1, Integer::sum);
	}

	/** Stand-alone check: counts plus a top-down picture (highest block per column). */
	public static void main(String[] args) throws Exception {
		long t0 = System.nanoTime();
		GhostTownMap map = load(Path.of(args[0]));
		System.out.printf("converted in %d ms%n", (System.nanoTime() - t0) / 1_000_000);
		System.out.printf("grid %d x %d x %d from (%d, %d, %d)%n", map.sizeX, map.sizeY, map.sizeZ, map.minX, map.minY, map.minZ);
		map.stats.forEach((k, v) -> System.out.println("  " + k + ": " + v));
		System.out.println("red spawns " + str(map.redSpawns) + "\nbot spawns " + str(map.botSpawns)
			+ "\nhatch " + (map.hatch == null ? "none" : java.util.Arrays.toString(map.hatch)));
		if (args.length > 1) {
			var img = new java.awt.image.BufferedImage(map.sizeX * 3, map.sizeZ * 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
			for (int z = 0; z < map.sizeZ; z++) {
				for (int x = 0; x < map.sizeX; x++) {
					int rgb = 0x000020;
					for (int y = map.sizeY - 1; y >= 0; y--) {
						byte c = map.cells[(y * map.sizeZ + z) * map.sizeX + x];
						if (c == 0) continue;
						String name = map.palette.get(c);
						if (name.contains("barrier")) continue;  // invisible in game too
						int base = name.contains("grass") ? 0x3c7a2a : name.contains("dirt") ? 0x7a5a3a : name.contains("stone") || name.contains("cobble") ? 0x808080
							: name.contains("barrier") ? 0xff0000 : name.contains("plank") || name.contains("log") ? 0xa0703a : 0x6060c0;
						double shade = 0.4 + 0.6 * y / map.sizeY;
						rgb = ((int) ((base >> 16 & 255) * shade) << 16) | ((int) ((base >> 8 & 255) * shade) << 8) | (int) ((base & 255) * shade);
						break;
					}
					for (int dy = 0; dy < 3; dy++) for (int dx = 0; dx < 3; dx++) img.setRGB(x * 3 + dx, z * 3 + dy, rgb);
				}
			}
			for (int[] s : map.redSpawns) mark(img, map, s, 0xff3030);
			for (int[] s : map.botSpawns) mark(img, map, s, 0x3080ff);
			if (map.hatch != null) mark(img, map, map.hatch, 0xffff00);
			javax.imageio.ImageIO.write(img, "png", new java.io.File(args[1]));
		}
	}

	private static void mark(java.awt.image.BufferedImage img, GhostTownMap map, int[] p, int rgb) {
		int x = (p[0] - map.minX) * 3, z = (p[2] - map.minZ) * 3;
		for (int d = -3; d <= 3; d++) {
			if (x + d >= 0 && x + d < img.getWidth() && z >= 0 && z < img.getHeight()) img.setRGB(x + d, z, rgb);
			if (z + d >= 0 && z + d < img.getHeight() && x >= 0 && x < img.getWidth()) img.setRGB(x, z + d, rgb);
		}
	}

	private static String str(List<int[]> list) {
		StringBuilder sb = new StringBuilder();
		for (int[] p : list) sb.append(java.util.Arrays.toString(p)).append(' ');
		return sb.toString();
	}
}
