package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Backpack phase B: a Minecraft item held instead of the TF2 melee weapon ("Equip to hand" in
 * TF2's backpack, docs/DESIGN.md). The stack is moved into a Minecraft hotbar slot and selected,
 * TF2 switches to its melee slot and hides its weapon, and Minecraft draws the item in hand.
 * Left-click still swings TF2's melee (which breaks the aimed block); right-click places or uses
 * the item through Minecraft. Weapon keys, the wheel, or running out put it away.
 */
public final class Hand {
	private static final int MELEE_SLOT = 2; // TF2 slot 3 is melee for every class

	/** Minecraft id of the held item, or null. Read by ItemStackMixin on the server thread too. */
	private static volatile String equippedId;
	/**
	 * The item just put away, still allowed briefly: placing the last block empties the client's
	 * stack at once, but Minecraft's built-in server places it a moment later and must still let
	 * it through, or the block vanishes.
	 */
	private static volatile String lastId;
	private static volatile long lastAt;
	private static final long LAST_GRACE_NANOS = 1_000_000_000L;
	private static int requestsSeen = -1;
	private static int weaponSlotBefore;
	private static boolean attackWasDown;
	private static long equippedAt;
	/** After a swap, the server's copy of the inventory takes a moment to arrive; don't re-check meanwhile. */
	private static final long SETTLE_NANOS = 500_000_000L;

	private Hand() {
	}

	public static boolean equipped() {
		return equippedId != null;
	}

	/** True if this stack is the held item, so its own use (placing a block) is allowed. */
	public static boolean allows(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		String id = equippedId;
		if (id != null && id.equals(idOf(stack))) {
			return true;
		}
		String last = lastId;
		return last != null && System.nanoTime() - lastAt < LAST_GRACE_NANOS && last.equals(idOf(stack));
	}

	/** Called every frame while TF2 is linked. */
	public static void tick(Minecraft minecraft) {
		if (minecraft.player == null) {
			return;
		}
		int requests = FortLink.handRequestCount();
		if (requestsSeen == -1) {
			requestsSeen = requests; // don't replay a request from before Minecraft started
		}
		if (requests != requestsSeen) {
			requestsSeen = requests;
			String id = FortLink.handRequestId();
			if (id.isEmpty()) {
				putAway(minecraft, "TF2 asked");
			} else if (id.startsWith("use:")) {
				useFromBackpack(minecraft, id.substring(4));
			} else if (PackItems.isPackId(id)) {
				useFromBackpack(minecraft, id);  // packs are used, never held (no hand model)
			} else {
				equip(minecraft, id);
			}
		}
		if (equippedId == null) {
			return;
		}
		Inventory inventory = minecraft.player.getInventory();
		if (System.nanoTime() - equippedAt > SETTLE_NANOS && !equippedId.equals(idOf(inventory.getSelectedItem()))) {
			// Used up (or moved): hold the next stack of the same item, if there is one.
			String id = equippedId;
			if (findSlot(inventory, id) >= 0) {
				equip(minecraft, id);
			} else {
				putAway(minecraft, "stack used up");
			}
			return;
		}
		boolean attack = minecraft.gui.screen() == null && minecraft.options.keyAttack.isDown();
		if (attack && !attackWasDown) {
			minecraft.player.swing(InteractionHand.MAIN_HAND, minecraft.player.getMainHandItem().getAttackAnimation(), false); // melee-style swing
		}
		attackWasDown = attack;
	}

	/** "Use" on a supply pack in TF2's backpack. */
	private static void useFromBackpack(Minecraft minecraft, String id) {
		var server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		java.util.UUID player = minecraft.player.getUUID();
		server.execute(() -> {
			var serverPlayer = server.getPlayerList().getPlayer(player);
			if (serverPlayer != null) {
				PackItems.useFromInventory(serverPlayer, id);
			}
		});
	}

	/** A TF2 weapon key or wheel step: the weapon comes back instead of the held item. */
	public static void onWeaponSelected(Minecraft minecraft) {
		if (equippedId != null) {
			putAway(minecraft, "weapon selected");
		}
	}

	private static void equip(Minecraft minecraft, String id) {
		Inventory inventory = minecraft.player.getInventory();
		int slot = findSlot(inventory, id);
		if (slot < 0) {
			FortCraft.LOG.info("FortCraft: can't hold {}: not in Minecraft's inventory", id);
			return;
		}
		int hotbar = slot;
		if (slot >= Inventory.getSelectionSize()) {
			// In the main inventory: swap it into an empty hotbar slot, or the selected one.
			hotbar = inventory.getSelectedSlot();
			for (int i = 0; i < Inventory.getSelectionSize(); i++) {
				if (inventory.getItem(i).isEmpty()) {
					hotbar = i;
					break;
				}
			}
			swapOnServer(minecraft, slot, hotbar);
		}
		inventory.setSelectedSlot(hotbar);
		if (equippedId == null) {
			weaponSlotBefore = WeaponSelection.current();
		}
		equippedId = id;
		equippedAt = System.nanoTime();
		WeaponSelection.force(MELEE_SLOT);
		FortLink.writeHandState(id);
		FortCraft.LOG.info("FortCraft: holding {} (Minecraft slot {}, from slot {}); TF2 melee hidden", id, hotbar + 1, slot + 1);
	}

	private static void putAway(Minecraft minecraft, String why) {
		if (equippedId == null) {
			return;
		}
		FortCraft.LOG.info("FortCraft: put away {} ({}); TF2 weapon slot {} back", equippedId, why, weaponSlotBefore + 1);
		lastAt = System.nanoTime();
		lastId = equippedId;
		equippedId = null;
		FortLink.writeHandState(null);
		if (!"weapon selected".equals(why)) {
			WeaponSelection.force(weaponSlotBefore);
		}
	}

	/** Swaps two inventory slots on Minecraft's built-in server (the client copy follows). */
	private static void swapOnServer(Minecraft minecraft, int a, int b) {
		var server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		UUID playerId = minecraft.player.getUUID();
		server.execute(() -> {
			var player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				return;
			}
			Inventory inv = player.getInventory();
			ItemStack first = inv.getItem(a);
			inv.setItem(a, inv.getItem(b));
			inv.setItem(b, first);
			inv.setSelectedSlot(b);
		});
		// Show it at once; the server's copy arrives a moment later and agrees.
		Inventory inv = minecraft.player.getInventory();
		ItemStack first = inv.getItem(a);
		inv.setItem(a, inv.getItem(b));
		inv.setItem(b, first);
	}

	private static int findSlot(Inventory inventory, String id) {
		int selected = inventory.getSelectedSlot();
		if (id.equals(idOf(inventory.getItem(selected)))) {
			return selected;
		}
		for (int i = 0; i < 36; i++) {
			if (id.equals(idOf(inventory.getItem(i)))) {
				return i;
			}
		}
		return -1;
	}

	private static String idOf(ItemStack stack) {
		return stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}
}
