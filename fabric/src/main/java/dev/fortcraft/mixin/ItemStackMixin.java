package dev.fortcraft.mixin;

import dev.fortcraft.Combat;
import dev.fortcraft.Hand;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While TF2 is linked, the player's Minecraft item is hidden behind a TF2 weapon, so the item's
 * own right-click action never runs: no placing blocks, tilling, bone meal, lighting fires, name
 * tags. The exception is an item held through TF2's "Equip to hand" (Hand). Blocks and mobs
 * still react to the click themselves (doors, bells, feeding animals with the selected food,
 * trading). Runs on both the client and Minecraft's built-in server.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noHiddenUseOn(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (Combat.linked && context.getPlayer() != null && !Hand.allows((ItemStack) (Object) this)) {
			cir.setReturnValue(InteractionResult.PASS);
		}
	}

	@Inject(method = "interactLivingEntity", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noHiddenInteract(Player player, LivingEntity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (Combat.linked && !Hand.allows((ItemStack) (Object) this)) {
			cir.setReturnValue(InteractionResult.PASS);
		}
	}
}
