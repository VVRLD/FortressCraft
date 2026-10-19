package dev.fortcraft.mvm;

import dev.fortcraft.FortCraft;
import dev.fortcraft.link.FortLink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds files in the player's own Team Fortress 2 install (read only, never written). */
public final class Tf2Install {
	private Tf2Install() {
	}

	/** tf/maps/mvm_ghost_town.bsp, or null if TF2 or the map isn't installed. */
	public static Path ghostTownMap() {
		for (Path tf : tfFolders()) {
			Path bsp = tf.resolve("maps").resolve("mvm_ghost_town.bsp");
			if (Files.isRegularFile(bsp)) {
				FortCraft.LOG.info("FortCraft: Ghost Town map: {}", bsp);
				return bsp;
			}
		}
		FortCraft.LOG.warn("FortCraft: mvm_ghost_town.bsp not found in any Steam library");
		return null;
	}

	/** Every "Team Fortress 2/tf" folder in the Steam libraries we can find. */
	static List<Path> tfFolders() {
		Set<Path> steamRoots = new LinkedHashSet<>();
		String override = System.getenv("FORTCRAFT_TF2_DIR");
		List<Path> result = new ArrayList<>();
		if (override != null && !override.isBlank()) result.add(Path.of(override).resolve("tf"));
		String home = System.getProperty("user.home");
		if (FortLink.WINDOWS) {
			String reg = windowsSteamPath();
			if (reg != null) steamRoots.add(Path.of(reg));
			steamRoots.add(Path.of("C:\\Program Files (x86)\\Steam"));
			steamRoots.add(Path.of("C:\\Program Files\\Steam"));
		} else {
			steamRoots.add(Path.of(home, ".steam", "steam"));
			steamRoots.add(Path.of(home, ".local", "share", "Steam"));
			steamRoots.add(Path.of(home, ".var", "app", "com.valvesoftware.Steam", ".local", "share", "Steam"));
		}
		Set<Path> libraries = new LinkedHashSet<>();
		Pattern path = Pattern.compile("\"path\"\\s*\"([^\"]*)\"");
		for (Path root : steamRoots) {
			if (!Files.isDirectory(root.resolve("steamapps"))) continue;
			libraries.add(root);
			try {
				String vdf = Files.readString(root.resolve("steamapps").resolve("libraryfolders.vdf"), StandardCharsets.UTF_8);
				Matcher m = path.matcher(vdf);
				while (m.find()) libraries.add(Path.of(m.group(1).replace("\\\\", "\\")));
			} catch (IOException | RuntimeException ignored) {
			}
		}
		for (Path lib : libraries) result.add(lib.resolve("steamapps").resolve("common").resolve("Team Fortress 2").resolve("tf"));
		return result;
	}

	/** Steam's folder from the Windows registry (HKCU\Software\Valve\Steam, SteamPath). */
	private static String windowsSteamPath() {
		try {
			Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath").redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor();
			Matcher m = Pattern.compile("SteamPath\\s+REG_SZ\\s+(.+)").matcher(out);
			return m.find() ? m.group(1).trim().replace('/', '\\') : null;
		} catch (IOException | InterruptedException | RuntimeException e) {
			return null;
		}
	}
}
