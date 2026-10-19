package dev.fortcraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * FortCraft's quick guide (Alex, 2026-10-10: "super short and easy to go through"): shown once,
 * the first time TF2 links in a world, and again any time with H (rebindable). Remembered in
 * config/fortcraft_guide_seen.txt in Minecraft's folder.
 */
public final class GuideScreen extends Screen {
	private static final String[] LINES = {
		"\u00a76FortCraft \u00a7f- you play as your TF2 merc in Minecraft",
		"",
		"\u00a7eMove, shoot, jump\u00a7f: TF2 controls.  \u00a7e1-9 / wheel\u00a7f: weapons",
		"\u00a7eE\u00a7f: TF2 backpack (last page = your Minecraft items)",
		"   Right-click an item > Equip to hand to mine, build or eat",
		"\u00a7eF\u00a7f: Minecraft inventory (middle-click = hold)\u00a7eM\u00a7f: loadout + crafting",
		"\u00a7e,\u00a7f / \u00a7e.\u00a7f: class / team   \u00a7eG\u00a7f: taunt   \u00a7eZ X C\u00a7f: voice",
		"\u00a7eR\u00a7f: reload (Eureka Effect: teleport menu)",
		"",
		"\u00a7aEnchanting table\u00a7f: MvM upgrades. Mobs drop cash, walk over it",
		"   More bookshelves around it = more upgrade levels",
		"\u00a7aNo hunger\u00a7f: food heals you. Craft Health Kits and Ammo Packs (M)",
		"",
		"\u00a77` TF2 console  -  P TF2 menu  -  H this guide again",
	};

	public GuideScreen() {
		super(Component.literal("FortCraft guide"));
	}

	private static Path seenFile() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("fortcraft_guide_seen.txt");
	}

	/** Shows the guide if it has never been shown on this computer. */
	private static boolean checked;

	public static void showOnce(Minecraft minecraft) {
		if (checked || minecraft.gui.screen() != null) {
			return;
		}
		checked = true;
		Path seen = seenFile();
		if (Files.exists(seen)) {
			return;
		}
		try {
			Files.createDirectories(seen.getParent());
			Files.writeString(seen, "FortCraft's guide was shown; press H in game to see it again.\n");
		} catch (IOException e) {
			FortCraft.LOG.warn("FortCraft: couldn't remember that the guide was shown", e);
		}
		minecraft.gui.setScreen(new GuideScreen());
		FortCraft.LOG.info("FortCraft: showing the guide for the first time");
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Got it"), b -> onClose())
			.bounds(width / 2 - 50, top() + LINES.length * 12 + 12, 100, 20).build());
	}

	private int top() {
		return Math.max(10, height / 2 - (LINES.length * 12 + 40) / 2);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int top = top();
		int boxWidth = 330;
		graphics.fill(width / 2 - boxWidth / 2 - 8, top - 8, width / 2 + boxWidth / 2 + 8, top + LINES.length * 12 + 40, 0xC0101010);
		for (int i = 0; i < LINES.length; i++) {
			graphics.text(font, LINES[i], width / 2 - boxWidth / 2, top + i * 12, 0xFFFFFFFF, true);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
