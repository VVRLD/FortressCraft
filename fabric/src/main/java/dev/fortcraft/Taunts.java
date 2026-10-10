package dev.fortcraft;

import com.mojang.blaze3d.platform.InputConstants;
import dev.fortcraft.link.FortLink;
import net.minecraft.client.Minecraft;

/**
 * Taunts use TF2's own taunt menu (drawn by TF2 in the overlay, listing the taunts equipped in
 * your TF2 loadout). This class only passes the keys on, like TF2's own keys:
 * G opens the menu; G again does the weapon taunt; 1-8 pick a taunt; Q closes the menu; G or Q
 * while taunting stops the taunt.
 *
 * While TF2's menu is open or you're taunting, the number keys and Q belong to TF2: Minecraft
 * doesn't switch hotbar slots or drop items (MinecraftMixin calls {@link #beforeKeybinds}).
 */
public final class Taunts {
	private static final int TAUNT_KEY = 1, SLOT_1 = 2, CANCEL = 12;  // protocol UiCommands

	private static boolean gWasDown;

	private Taunts() {
	}

	/**
	 * True while TF2's taunt menu or the Eureka Effect's teleport menu is open, or the player is
	 * taunting: the number keys and Q go to TF2 (Q also doesn't drop the held item then).
	 */
	private static boolean tf2HasKeys() {
		FortLink.Tf2Camera cam = Overlay.active() ? FortLink.readCamera() : null;
		return cam != null && (cam.tauntMenu() || cam.taunting() || cam.eurekaMenu());
	}

	/** Once per Minecraft frame while TF2 is linked: G (not one of Minecraft's own keys). */
	public static void tick(Minecraft minecraft) {
		boolean gDown = minecraft.gui.screen() == null && Tf2Keys.TAUNT.isDown();
		if (gDown && !gWasDown) {
			FortLink.sendUiCommand(TAUNT_KEY);
			FortCraft.LOG.info("FortCraft: G -> TF2 taunt key");
		}
		gWasDown = gDown;
	}

	/**
	 * Called at the start of Minecraft's key handling each tick. While TF2 has the keys, take
	 * the number-key and Q presses before Minecraft sees them and pass them to TF2.
	 */
	public static void beforeKeybinds(Minecraft minecraft) {
		if (!tf2HasKeys()) {
			return;
		}
		var slots = minecraft.options.keyHotbarSlots;
		for (int i = 0; i < Math.min(8, slots.length); i++) {
			while (slots[i].consumeClick()) {
				FortLink.sendUiCommand(SLOT_1 + i);
				FortCraft.LOG.info("FortCraft: {} -> TF2 taunt menu", i + 1);
			}
		}
		while (minecraft.options.keyDrop.consumeClick()) {
			FortLink.sendUiCommand(CANCEL);
			FortCraft.LOG.info("FortCraft: Q -> TF2 cancel");
		}
	}
}
