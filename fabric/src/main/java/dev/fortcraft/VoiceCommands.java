package dev.fortcraft;

import com.mojang.blaze3d.platform.InputConstants;
import dev.fortcraft.link.FortLink;
import net.minecraft.client.Minecraft;

/** Routes TF2's stock Z/X/C voice menus without letting their number keys change weapons. */
public final class VoiceCommands {
	private static final int MENU_1 = 40, SELECT_1 = 50, CANCEL = 59;
	private static final boolean[] WAS_DOWN = new boolean[4];
	private static int openMenu;
	private static long expiresAt;

	private VoiceCommands() {
	}

	public static void tick(Minecraft minecraft) {
		boolean canOpen = minecraft.gui.screen() == null;
		FortLink.Tf2Camera cam = FortLink.readCamera();
		if (cam != null && (cam.tauntMenu() || cam.taunting() || cam.uiOpen())) {
			canOpen = false;
		}
		if (openMenu != 0 && (!canOpen || System.nanoTime() > expiresAt)) {
			cancel();
		}
		int[] keys = {InputConstants.KEY_Z, InputConstants.KEY_X, InputConstants.KEY_C};
		for (int i = 0; i < keys.length; i++) {
			if (edge(InputConstants.isKeyDown(keys[i]), i) && canOpen) {
				int requested = i + 1;
				FortLink.sendUiCommand(MENU_1 + i);
				openMenu = openMenu == requested ? 0 : requested;
				expiresAt = System.nanoTime() + 10_000_000_000L;
				FortCraft.LOG.info("FortCraft: TF2 voice menu {} {}", requested, openMenu == 0 ? "closed" : "opened");
			}
		}
		if (edge(InputConstants.isKeyDown(InputConstants.KEY_0), 3) && openMenu != 0) {
			cancel();
		}
	}

	/** Called before Minecraft's hotbar and drop key handling. */
	public static void beforeKeybinds(Minecraft minecraft) {
		if (openMenu == 0 || minecraft.gui.screen() != null) {
			return;
		}
		var slots = minecraft.options.keyHotbarSlots;
		for (int i = 0; i < Math.min(9, slots.length); i++) {
			while (slots[i].consumeClick()) {
				FortLink.sendUiCommand(SELECT_1 + i);
				FortCraft.LOG.info("FortCraft: {} -> TF2 voice menu {}", i + 1, openMenu);
				openMenu = 0;
			}
		}
		while (minecraft.options.keyDrop.consumeClick()) {
			cancel();
		}
	}

	private static void cancel() {
		if (openMenu != 0) {
			FortLink.sendUiCommand(CANCEL);
			FortCraft.LOG.info("FortCraft: TF2 voice menu cancelled");
			openMenu = 0;
		}
	}

	private static boolean edge(boolean down, int index) {
		boolean pressed = down && !WAS_DOWN[index];
		WAS_DOWN[index] = down;
		return pressed;
	}
}
