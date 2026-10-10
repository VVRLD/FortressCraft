package dev.fortcraft.mixin;

import dev.fortcraft.Combat;
import dev.fortcraft.FortCraft;
import dev.fortcraft.link.FortLink;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While TF2 is linked, an enchanting table is TF2's Mann vs. Machine upgrade station (Alex,
 * 2026-10-10): right-clicking it opens TF2's upgrade screen instead of Minecraft's enchanting
 * screen. The bookshelves around it are counted for upgrade tiers.
 */
@Mixin(EnchantingTableBlock.class)
public abstract class EnchantingTableMixin {
	@Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
	private void fortcraft$mvmUpgrades(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit,
		CallbackInfoReturnable<InteractionResult> cir) {
		if (!Combat.linked) {
			return;
		}
		if (!level.isClientSide()) {
			int shelves = 0;
			for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
				if (EnchantingTableBlock.isValidBookShelf(level, pos, offset)) {
					shelves++;
				}
			}
			FortLink.writeUpgradeStation(shelves, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			FortCraft.LOG.info("FortCraft: enchanting table at {} opens TF2's upgrades ({} bookshelves)", pos, shelves);
		}
		cir.setReturnValue(InteractionResult.SUCCESS);
	}
}
