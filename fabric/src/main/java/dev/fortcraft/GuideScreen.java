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
 * config/fortcraft_guide_seen.txt in Minecraft's folder. Pages (2026-10-20): the basics, the
 * Ghost Town (MvM dimension), village raids with MvM robots.
 */
public final class GuideScreen extends Screen {
	private static final String[][] PAGES = {
		{
			"§6FortCraft §f- you play as your TF2 merc in Minecraft",
			"",
			"§eMove, shoot, jump§f: TF2 controls.  §e1-9 / wheel§f: weapons",
			"§eE§f: TF2 backpack (last page = your Minecraft items)",
			"   Right-click an item > Equip to hand to mine, build or eat",
			"§eF§f: Minecraft inventory (middle-click = hold)",
			"§eM§f: loadout + crafting",
			"§e,§f / §e.§f: class / team   §eG§f: taunt   §eZ X C§f: voice",
			"§eR§f: reload (Eureka Effect: teleport menu)",
			"",
			"§aEnchanting table§f: MvM upgrades. Mobs drop cash, walk over it",
			"   More bookshelves around it = more upgrade levels",
			"§aNo hunger§f: food heals you. Craft Health Kits and Ammo Packs (M)",
			"",
			"§7` TF2 console  -  P TF2 menu  -  H this guide again",
		},
		{
			"§6Mann vs. Machine: the Ghost Town",
			"",
			"§eGet there§f: craft an §dUnusual Flint and Steel§f",
			"   (flint and steel + amethyst shard, or the creative menu)",
			"§eBuild§f an upright frame of iron blocks, like a Nether portal",
			"   (inside 2 wide, 3 tall), hold the flint and steel and",
			"   right-click inside it: it fills with a white portal",
			"§eStep in§f: you arrive in RED's spawn, at night",
			"",
			"§aWaves§f: press the button by the bookshelves, or wait 30 s",
			"   Robots come down the hill in groups. One carries the bomb:",
			"   stop it before it reaches the hatch outside your spawn",
			"   Kill the carrier and the bomb drops for another robot",
			"   At the hatch it takes 3-5 s to deploy: kill it in time!",
			"§aBetween waves§f: spend your cash at the enchanting table",
			"",
			"§7Die and you respawn in RED's spawn. The white portal = home",
		},
		{
			"§6Village raids: Mann vs. Machine robots",
			"",
			"§eRaiders are TF2 robots§f: pillagers, vindicators, evokers,",
			"   witches and ravagers show up as MvM's Soldiers, Snipers,",
			"   Demoknights, Medics, Jarate Snipers, Giant Heavies...",
			"§eStart a raid§f: drink an Ominous Bottle (Bad Omen),",
			"   then walk into a village",
			"",
			"§aTF2's wave panel§f shows each raid wave and who's left",
			"   More giants every wave; the last brings a boss and a Tank",
			"§aBeat the raid§f: $500, on top of the cash every robot drops",
		},
	};

	private int page;

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

	/** Room for the longest page, so the buttons stay put when turning pages. */
	private static int lines() {
		int most = 0;
		for (String[] p : PAGES) most = Math.max(most, p.length);
		return most;
	}

	@Override
	protected void init() {
		int y = top() + lines() * 12 + 12;
		addRenderableWidget(Button.builder(Component.literal("< Back"), b -> turn(-1))
			.bounds(width / 2 - 160, y, 70, 20).build()).active = page > 0;
		addRenderableWidget(Button.builder(Component.literal("Got it"), b -> onClose())
			.bounds(width / 2 - 50, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Next >"), b -> turn(1))
			.bounds(width / 2 + 90, y, 70, 20).build()).active = page < PAGES.length - 1;
	}

	private void turn(int by) {
		page = Math.max(0, Math.min(PAGES.length - 1, page + by));
		rebuildWidgets();
	}

	private int top() {
		return Math.max(10, height / 2 - (lines() * 12 + 40) / 2);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int top = top();
		int boxWidth = 340;
		graphics.fill(width / 2 - boxWidth / 2 - 8, top - 8, width / 2 + boxWidth / 2 + 8, top + lines() * 12 + 40, 0xC0101010);
		String[] lines = PAGES[page];
		for (int i = 0; i < lines.length; i++) {
			graphics.text(font, lines[i], width / 2 - boxWidth / 2, top + i * 12, 0xFFFFFFFF, true);
		}
		String count = "§7" + (page + 1) + " / " + PAGES.length;
		graphics.text(font, count, width / 2 + boxWidth / 2 - font.width(count), top, 0xFFFFFFFF, true);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
