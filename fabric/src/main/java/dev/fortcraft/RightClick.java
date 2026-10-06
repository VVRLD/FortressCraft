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
 * reacts, the press is Minecraft's. If it doesn't, or nothing is aimed at, the whole hold is
 * TF2's secondary fire (scope, detonate, charge, cloak, airblast...). The hidden Minecraft item
 * itself never does anything on its own (ItemStackMixin), so no block is placed.
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
			toMinecraft = tryMinecraft(minecraft);
			if (!toMinecraft) {
				FortCraft.LOG.info("FortCraft: right-click goes to TF2");
			}
		}
		wasDown = down;
		return down;
	}

	/** Offers this press to the aimed block or mob, as Minecraft would; true if it reacted. */
	private static boolean tryMinecraft(Minecraft minecraft) {
		var player = minecraft.player;
		if (player == null || minecraft.gameMode == null || minecraft.level == null || minecraft.hitResult == null) {
			return false;
		}
		InteractionResult result;
		String target;
		if (minecraft.hitResult instanceof EntityHitResult hit) {
			if (!player.isWithinEntityInteractionRange(hit.getEntity(), 0)) {
				return false;
			}
			target = hit.getEntity().getName().getString();
			result = minecraft.gameMode.interact(player, hit.getEntity(), hit, InteractionHand.MAIN_HAND);
		} else if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == BlockHitResult.Type.BLOCK) {
			target = minecraft.level.getBlockState(hit.getBlockPos()).getBlock().getName().getString();
			result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		} else {
			return false;
		}
		boolean used = result.consumesAction();
		FortCraft.LOG.info("FortCraft: right-click offered to {}: {}{}", target, result, used ? " -> Minecraft" : "");
		return used;
	}
}
