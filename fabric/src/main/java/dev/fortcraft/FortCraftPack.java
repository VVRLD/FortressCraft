package dev.fortcraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;

/**
 * FortCraft's own resources as an always-on built-in pack, for both Minecraft's resource packs
 * (assets: textures, models) and data packs (data: the Ghost Town dimension, recipes). This mod
 * runs without Fabric API, which is what normally loads a mod's assets/ and data/ folders.
 */
public final class FortCraftPack {
	public static final String ID = "fortcraft";
	private static Path root;

	private FortCraftPack() {
	}

	/** The pack for this pack type, or null if the mod's resource folder can't be found. */
	public static Pack create(PackType type) {
		Path dir = root();
		if (dir == null || !Files.isDirectory(dir.resolve(type.getDirectory()))) return null;
		PackLocationInfo info = new PackLocationInfo(ID, Component.literal("FortCraft"), PackSource.BUILT_IN, Optional.empty());
		Pack.Metadata metadata = new Pack.Metadata(Component.literal("FortCraft's blocks, items and worlds"),
			PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
		// required: always on, in every world, and can't be switched off in the pack screens.
		return new Pack(info, new PathPackResources.PathResourcesSupplier(dir), metadata,
			new PackSelectionConfig(true, Pack.Position.TOP, false));
	}

	/** The mod's resource root: the folder (or jar) that holds assets/ and data/. */
	private static Path root() {
		if (root != null) return root;
		var mod = FabricLoader.getInstance().getModContainer("fortcraft").orElse(null);
		if (mod == null) return null;
		for (Path path : mod.getRootPaths()) {
			if (Files.isDirectory(path.resolve("assets")) || Files.isDirectory(path.resolve("data"))) {
				root = path;
				FortCraft.LOG.info("FortCraft: built-in pack from {}", path);
				return root;
			}
		}
		FortCraft.LOG.warn("FortCraft: no assets/ or data/ folder found in the mod; its blocks and worlds won't load");
		return null;
	}
}
