package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ScrollWheelHandler;

/** TF2 weapon selection, kept separate from Minecraft's saved hotbar item slot. */
public final class WeaponSelection {
	private static final int SLOT_COUNT = 9; // preserve the old hotbar-to-TF2 input range for now
	private static final ScrollWheelHandler WHEEL = new ScrollWheelHandler();
	private static int slot;
	private static boolean linked;

	private WeaponSelection() {
	}

	/** Called every input frame, even when TF2 is disconnected, so a new link starts fresh. */
	public static int selectedSlot(Minecraft minecraft, boolean guestAlive) {
		if (!guestAlive || minecraft.player == null) {
			linked = false;
			return minecraft.player == null ? 0 : minecraft.player.getInventory().getSelectedSlot();
		}
		ensureLinked(minecraft);
		return slot;
	}

	/** Number keys go to TF2; Minecraft must not select the item in that numbered slot. */
	public static void beforeKeybinds(Minecraft minecraft) {
		if (minecraft.gui.screen() != null || minecraft.player == null) {
			return;
		}
		ensureLinked(minecraft);
		var keys = minecraft.options.keyHotbarSlots;
		for (int i = 0; i < Math.min(SLOT_COUNT, keys.length); i++) {
			while (keys[i].consumeClick()) {
				select(minecraft, i, "key");
			}
		}
	}

	/** Return true when gameplay scroll was consumed for TF2 weapon selection. */
	public static boolean onScroll(Minecraft minecraft, long window, double scrollX, double scrollY) {
		if (minecraft.player == null || minecraft.gui.screen() != null || window != minecraft.getWindow().handle()) {
			return false;
		}
		ensureLinked(minecraft);
		boolean discrete = minecraft.options.discreteMouseScroll().get();
		double sensitivity = minecraft.options.mouseWheelSensitivity().get();
		double x = (discrete ? Math.signum(scrollX) : scrollX) * sensitivity;
		double y = (discrete ? Math.signum(scrollY) : scrollY) * sensitivity;
		var movement = WHEEL.onMouseScroll(x, y);
		int steps = movement.y != 0 ? movement.y : -movement.x;
		if (steps != 0) {
			select(minecraft, ScrollWheelHandler.getNextScrollWheelSelection(steps, slot, SLOT_COUNT), "wheel");
		}
		return true;
	}

	private static void ensureLinked(Minecraft minecraft) {
		if (!linked) {
			linked = true;
			slot = minecraft.player.getInventory().getSelectedSlot();
			FortCraft.LOG.info("FortCraft: TF2 weapon input separated from Minecraft hotbar; initial slot {}", slot + 1);
		}
	}

	/** The TF2 weapon slot currently requested (0-based). */
	public static int current() {
		return slot;
	}

	/** Sets the requested TF2 weapon slot without a key press (holding a Minecraft item uses melee). */
	public static void force(int next) {
		slot = next;
	}

	private static void select(Minecraft minecraft, int next, String source) {
		Hand.onWeaponSelected(minecraft);
		slot = next;
		// The PDA needs each actual key press, not the persistent requested weapon slot. Otherwise
		// equipping it with slot 4 still requested auto-selects the teleporter exit.
		if ("key".equals(source) && next < 4) {
			FortLink.sendUiCommand(30 + next);
		}
		FortCraft.LOG.info("FortCraft: TF2 weapon slot {} requested by {}; Minecraft item slot stays {}",
			slot + 1, source, minecraft.player.getInventory().getSelectedSlot() + 1);
	}
}
