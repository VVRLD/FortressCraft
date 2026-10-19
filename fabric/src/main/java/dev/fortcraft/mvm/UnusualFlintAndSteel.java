package dev.fortcraft.mvm;

import dev.fortcraft.FortCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * The Unusual Flint and Steel (Alex, 2026-10-19): a purple flint and steel that lights an iron-block
 * frame into a white portal to the Ghost Town. On anything else it works like a normal one.
 */
public final class UnusualFlintAndSteel extends FlintAndSteelItem {
	public UnusualFlintAndSteel(Properties properties) {
		super(properties);
	}

	@Override
	public Component getName(ItemStack stack) {
		return Component.literal("Unusual Flint and Steel");
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos clicked = context.getClickedPos();
		if (level.getBlockState(clicked).is(Blocks.IRON_BLOCK)) {
			BlockPos inside = clicked.relative(context.getClickedFace());
			if (level.isClientSide()) {
				return PortalFrame.find(level, inside) != null ? InteractionResult.SUCCESS : super.useOn(context);
			}
			PortalFrame frame = PortalFrame.find(level, inside);
			if (frame != null) {
				frame.light(level);
				level.playSound(context.getPlayer(), inside, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, 0.8F);
				level.playSound(null, inside, SoundEvents.PORTAL_TRIGGER, SoundSource.BLOCKS, 0.4F, 1.6F);
				if (context.getPlayer() != null) {
					context.getItemInHand().hurtAndBreak(1, context.getPlayer(), context.getHand().asEquipmentSlot());
				}
				FortCraft.LOG.info("FortCraft: lit a Ghost Town portal {}x{} at {} (axis {})",
					frame.width(), frame.height(), frame.bottomLeft(), frame.axis());
				GhostTown.prepare(level.getServer());
				return InteractionResult.SUCCESS_SERVER;
			}
			FortCraft.LOG.info("FortCraft: Unusual Flint and Steel on iron at {}: no complete frame around {}", clicked, inside);
		}
		return super.useOn(context);
	}
}
