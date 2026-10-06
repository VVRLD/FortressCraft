package dev.fortcraft;

import com.mojang.blaze3d.platform.NativeImage;
import dev.fortcraft.link.FortLink;
import java.io.IOException;
import java.io.InputStream;
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
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Sends Minecraft's inventory to TF2, one entry per item type, so TF2 can show the stacks on its
 * backpack's last page (backpack phase A, docs/DESIGN.md). Each item's icon is Minecraft's own
 * texture, written once as a TF2 material (.vtf + .vmt) into this copy's mod_tf/materials.
 */
public final class Backpack {
	private static final long INTERVAL_NANOS = 250_000_000L;
	private static final int ICON_SIZE = 128;
	/** TF2's mod folder, from Minecraft's run folder (fabric/run): ../../../source-sdk-2013/game/mod_tf. */
	private static final Path MOD_DIR = Path.of("..", "..", "..", "source-sdk-2013", "game", "mod_tf").toAbsolutePath().normalize();

	private static long lastSent;
	private static List<FortLink.BackpackStack> sent = List.of();
	private static final Map<String, String> icons = new HashMap<>(); // item id -> material ("" = none)
	private static boolean modDirChecked;
	private static boolean modDirOk;

	private Backpack() {
	}

	/** Called every frame while TF2 is linked; rescans a few times a second and sends changes. */
	public static void tick(Minecraft minecraft) {
		long now = System.nanoTime();
		if (minecraft.player == null || now - lastSent < INTERVAL_NANOS) {
			return;
		}
		lastSent = now;
		var inventory = minecraft.player.getInventory();
		Map<String, int[]> counts = new LinkedHashMap<>();
		Map<String, String> names = new HashMap<>();
		for (int slot = 0; slot < 36; slot++) {
			var stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			counts.computeIfAbsent(id, k -> new int[1])[0] += stack.getCount();
			names.putIfAbsent(id, stack.getItem().getName(stack).getString());
		}
		List<FortLink.BackpackStack> stacks = new ArrayList<>();
		for (var e : counts.entrySet()) {
			stacks.add(new FortLink.BackpackStack(e.getKey(), names.get(e.getKey()), icon(minecraft, e.getKey()), e.getValue()[0]));
		}
		if (!stacks.equals(sent)) {
			FortLink.writeBackpack(stacks);
			FortCraft.LOG.info("FortCraft: backpack sent {} item types: {}", stacks.size(),
				stacks.stream().map(s -> s.name() + " x" + s.count()).toList());
			sent = stacks;
		}
	}

	/**
	 * The TF2 material for an item's icon, writing it the first time; "" if there is none. Also
	 * used from the server thread for crafting results, hence synchronized.
	 */
	public static synchronized String icon(Minecraft minecraft, String id) {
		return icons.computeIfAbsent(id, k -> {
			try {
				return writeIcon(minecraft, k);
			} catch (IOException | RuntimeException ex) {
				FortCraft.LOG.warn("FortCraft: no backpack icon for {}: {}", k, ex.toString());
				return "";
			}
		});
	}

	private static String writeIcon(Minecraft minecraft, String id) throws IOException {
		if (!modDirChecked) {
			modDirChecked = true;
			modDirOk = Files.isRegularFile(MOD_DIR.resolve("gameinfo.txt"));
			FortCraft.LOG.info("FortCraft: backpack icons go to {} ({})", MOD_DIR, modDirOk ? "found" : "missing; no icons");
		}
		if (!modDirOk) {
			return "";
		}
		String file = "v2_" + id.replace(':', '_').replace('/', '_'); // v2: tinted leaves and grass
		String material = "backpack/fortcraft/" + file;
		Path dir = MOD_DIR.resolve("materials").resolve("backpack").resolve("fortcraft");
		Path vtf = dir.resolve(file + "_large.vtf");
		if (Files.isRegularFile(vtf)) {
			return material; // written in an earlier session
		}
		int[] argb = loadTexture(minecraft, id);
		if (argb == null) {
			FortCraft.LOG.info("FortCraft: no texture found for {}; TF2 shows its default icon", id);
			return "";
		}
		Files.createDirectories(dir);
		Files.write(vtf, vtf(argb, ICON_SIZE));
		String vmt = "\"UnlitGeneric\"\n{\n\t\"$baseTexture\" \"" + material + "_large\"\n\t\"$translucent\" 1\n"
			+ "\t\"$vertexcolor\" 1\n\t\"$vertexalpha\" 1\n\t\"$no_fullbright\" 1\n\t\"$ignorez\" 1\n}\n";
		Files.writeString(dir.resolve(file + "_large.vmt"), vmt, StandardCharsets.US_ASCII);
		Files.writeString(dir.resolve(file + ".vmt"), vmt, StandardCharsets.US_ASCII);
		FortCraft.LOG.info("FortCraft: wrote backpack icon {}", material);
		return material;
	}

	/**
	 * The item's flat texture, scaled to ICON_SIZE (nearest neighbour, pixel-art look), ARGB. Items
	 * use textures/item/<name>; blocks their side, front or plain texture. Null if none is found.
	 */
	private static int[] loadTexture(Minecraft minecraft, String id) throws IOException {
		Identifier key = Identifier.parse(id);
		String path = key.getPath();
		String[] candidates = {
			"textures/item/" + path + ".png",
			"textures/block/" + path + "_side.png",
			"textures/block/" + path + "_front.png",
			"textures/block/" + path + ".png",
			"textures/block/" + path + "_top.png",
		};
		for (String candidate : candidates) {
			var resource = minecraft.getResourceManager().getResource(Identifier.fromNamespaceAndPath(key.getNamespace(), candidate));
			if (resource.isEmpty()) {
				continue;
			}
			int tint = tintFor(candidate);
			try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
				int w = image.getWidth();
				int h = Math.min(image.getHeight(), w); // animated textures are a vertical strip: first frame
				int[] out = new int[ICON_SIZE * ICON_SIZE];
				for (int y = 0; y < ICON_SIZE; y++) {
					for (int x = 0; x < ICON_SIZE; x++) {
						out[y * ICON_SIZE + x] = multiply(image.getPixel(x * w / ICON_SIZE, y * h / ICON_SIZE), tint);
					}
				}
				return out;
			}
		}
		return null;
	}

	/**
	 * Minecraft stores leaves, grass and vines grey and colours them by biome when drawing; an
	 * icon gets the plains colour (spruce and birch leaves have fixed colours). 0xFFFFFF = none.
	 */
	private static int tintFor(String texture) {
		String name = texture.substring(texture.lastIndexOf('/') + 1).replace(".png", "");
		if (name.equals("spruce_leaves")) {
			return 0x619961;
		}
		if (name.equals("birch_leaves")) {
			return 0x80A755;
		}
		if (name.endsWith("_leaves") && !name.contains("azalea") && !name.contains("cherry") && !name.contains("pale_oak")) {
			return 0x77AB2F; // foliage
		}
		return switch (name) {
			case "short_grass", "grass", "fern", "tall_grass_top", "tall_grass_bottom", "large_fern_top", "large_fern_bottom",
				"vine", "lily_pad", "sugar_cane", "grass_block_top" -> 0x91BD59;
			default -> 0xFFFFFF;
		};
	}

	private static int multiply(int argb, int rgb) {
		if (rgb == 0xFFFFFF) {
			return argb;
		}
		int r = ((argb >> 16) & 0xFF) * ((rgb >> 16) & 0xFF) / 255;
		int g = ((argb >> 8) & 0xFF) * ((rgb >> 8) & 0xFF) / 255;
		int b = (argb & 0xFF) * (rgb & 0xFF) / 255;
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	/** A Valve texture file (VTF 7.2): one BGRA8888 image, no mipmaps, no thumbnail. */
	private static byte[] vtf(int[] argb, int size) {
		ByteBuffer b = ByteBuffer.allocate(80 + size * size * 4).order(ByteOrder.LITTLE_ENDIAN);
		b.put(new byte[] { 'V', 'T', 'F', 0 });
		b.putInt(7).putInt(2);      // version 7.2
		b.putInt(80);               // header size
		b.putShort((short) size).putShort((short) size);
		b.putInt(0x0004 | 0x0008 | 0x0100 | 0x0200 | 0x2000); // clamp S/T, no mip, no LOD, 8-bit alpha
		b.putShort((short) 1);      // frames
		b.putShort((short) 0);      // first frame
		b.putInt(0);                // padding
		b.putFloat(0.5f).putFloat(0.5f).putFloat(0.5f); // reflectivity
		b.putInt(0);                // padding
		b.putFloat(1.0f);           // bumpmap scale
		b.putInt(12);               // high-res format: BGRA8888
		b.put((byte) 1);            // mipmap count
		b.putInt(-1);               // low-res format: none
		b.put((byte) 0).put((byte) 0); // low-res width, height
		b.putShort((short) 1);      // depth
		b.position(80);
		for (int p : argb) {
			b.put((byte) p).put((byte) (p >> 8)).put((byte) (p >> 16)).put((byte) (p >>> 24)); // B, G, R, A
		}
		return b.array();
	}
}
