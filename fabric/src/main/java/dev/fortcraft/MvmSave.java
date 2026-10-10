package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * TF2's MvM money and upgrades, saved with the Minecraft world (Alex, 2026-10-10), in
 * fortcraft_mvm.txt in the world's folder. When a world opens, its file is sent to TF2
 * (MvmRestore); after TF2 has applied it, TF2's state (MvmState) is written back to the file
 * whenever it changes. TF2 only reports after applying the restore, so a fresh TF2 can't wipe a
 * save. File: "currency N", then one "upgrade class itemDef upgradeIndex cost" line per upgrade.
 */
public final class MvmSave {
	private static MinecraftServer server;
	private static Path file;
	private static int restoreSeq;
	private static String lastSaved = "";
	private static long nextCheck;

	private MvmSave() {
	}

	/** Once per Minecraft frame while TF2 is linked. */
	public static void tick(Minecraft minecraft) {
		MinecraftServer now = minecraft.getSingleplayerServer();
		if (now != server) {
			server = now;
			restoreSeq = 0;
			file = now == null ? null : now.getWorldPath(LevelResource.ROOT).resolve("fortcraft_mvm.txt");
			if (file != null) {
				load();
			}
		}
		if (file == null || restoreSeq == 0 || System.nanoTime() < nextCheck) {
			return;
		}
		nextCheck = System.nanoTime() + 1_000_000_000L;
		FortLink.MvmState state = FortLink.readMvmState();
		if (state == null || state.restoreApplied() != restoreSeq) {
			return;  // TF2 hasn't applied this world's save yet
		}
		String text = format(state.currency(), state.upgrades());
		if (text.equals(lastSaved)) {
			return;
		}
		try {
			Path temp = file.resolveSibling("fortcraft_mvm.txt.tmp");
			Files.writeString(temp, text, StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			lastSaved = text;
			FortCraft.LOG.info("FortCraft: saved MvM ${} and {} upgrades with the world", state.currency(), state.upgrades().size());
		} catch (IOException e) {
			FortCraft.LOG.warn("FortCraft: couldn't save MvM money and upgrades to {}", file, e);
		}
	}

	private static void load() {
		int currency = 0;
		List<FortLink.MvmUpgrade> upgrades = new ArrayList<>();
		String text = "";
		try {
			if (Files.exists(file)) {
				text = Files.readString(file, StandardCharsets.UTF_8);
				for (String line : text.split("\\R")) {
					String[] p = line.trim().split("\\s+");
					if (p.length == 2 && p[0].equals("currency")) {
						currency = Integer.parseInt(p[1]);
					} else if (p.length == 5 && p[0].equals("upgrade")) {
						upgrades.add(new FortLink.MvmUpgrade(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
							Integer.parseInt(p[3]), Integer.parseInt(p[4])));
					}
				}
			}
		} catch (IOException | NumberFormatException e) {
			FortCraft.LOG.warn("FortCraft: couldn't read {}; starting with no MvM money or upgrades", file, e);
		}
		lastSaved = text;
		restoreSeq = FortLink.writeMvmRestore(currency, upgrades);
		FortCraft.LOG.info("FortCraft: world's MvM save sent to TF2: ${} and {} upgrades", currency, upgrades.size());
	}

	private static String format(int currency, List<FortLink.MvmUpgrade> upgrades) {
		StringBuilder sb = new StringBuilder("currency ").append(currency).append('\n');
		for (FortLink.MvmUpgrade u : upgrades) {
			sb.append("upgrade ").append(u.playerClass()).append(' ').append(u.itemDef()).append(' ')
				.append(u.upgrade()).append(' ').append(u.cost()).append('\n');
		}
		return sb.toString();
	}
}
