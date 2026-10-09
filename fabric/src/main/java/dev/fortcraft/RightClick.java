package dev.fortcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Who gets the right mouse button while TF2 is linked. Decided once when the button goes down,
 * by Minecraft itself: the aimed block or mob within reach is offered the click first (doors,
 * chests, bells, note blocks, feeding and breeding animals, villager trading, leads...). If it
 * reacts, the press is Minecraft's. An item explicitly held through TF2's backpack also gets
 * its normal Minecraft use after the target declines, including use in air (food and pearls).
 * Otherwise the whole hold is TF2's secondary fire (scope, detonate, charge, cloak, airblast...).
 * A hidden Minecraft item never runs on its own (ItemStackMixin).
 */
public final class RightClick {
	private static boolean wasDown;
	private static boolean toMinecraft;

	private RightClick() {
	}

	/** Called every input frame while linked; true while TF2 should see its secondary-fire button held. */
	public static boolean tf2Attack2(Minecraft minecraft) {
		return latch(minecraft) && !toMinecraft;
	}

	/** Called instead of Minecraft's own right-click while linked; the press is handled in latch. */
	public static void minecraftUse(Minecraft minecraft) {
		latch(minecraft);
	}

	/** Edge detection shared by the frame input and Minecraft's tick, whichever sees the press first. */
	private static boolean latch(Minecraft minecraft) {
		boolean down = minecraft.options.keyUse.isDown();
		if (down && !wasDown) {
			boolean heldItem = Hand.equipped();
			boolean used = tryMinecraft(minecraft, heldItem);
			toMinecraft = heldItem || used;
			if (!toMinecraft) {
				FortCraft.LOG.info("FortCraft: right-click goes to TF2");
			}
		}
		wasDown = down;
		return down;
	}

	/** Offers the target first, then an explicitly held item; true if Minecraft reacted. */
	private static boolean tryMinecraft(Minecraft minecraft, boolean heldItem) {
		var player = minecraft.player;
		if (player == null || minecraft.gameMode == null || minecraft.level == null) {
			return false;
		}
		if (heldItem && PackItems.heldPack(player)) {
			return minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND).consumesAction();
		}
		if (minecraft.hitResult != null) {
			InteractionResult result;
			String target;
			if (minecraft.hitResult instanceof EntityHitResult hit) {
				if (player.isWithinEntityInteractionRange(hit.getEntity(), 0)) {
					target = hit.getEntity().getName().getString();
					result = minecraft.gameMode.interact(player, hit.getEntity(), hit, InteractionHand.MAIN_HAND);
					if (logAndConsumed(target, result)) return true;
				}
			} else if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == BlockHitResult.Type.BLOCK) {
				target = minecraft.level.getBlockState(hit.getBlockPos()).getBlock().getName().getString();
				result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
				if (logAndConsumed(target, result)) return true;
			}
		}
		if (!heldItem) return false;
		InteractionResult itemResult = minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		FortCraft.LOG.info("FortCraft: held Minecraft item {} use: {}",
			player.getMainHandItem().getDisplayName().getString(), itemResult);
		return itemResult.consumesAction();
	}

	private static boolean logAndConsumed(String target, InteractionResult result) {
		boolean used = result.consumesAction();
		FortCraft.LOG.info("FortCraft: right-click offered to {}: {}{}", target, result, used ? " -> Minecraft" : "");
		return used;
	}
}
