package dev.fortcraft.mixin;

import dev.fortcraft.Combat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No hunger while TF2 is linked (Alex, 2026-10-10): the food bar stays full, so nobody starves and
 * nothing slows down. Food still heals TF2's health (ItemStackMixin); PlayerMixin lets you eat
 * with a full bar.
 */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noHunger(ServerPlayer player, CallbackInfo ci) {
		if (Combat.linked) {
			FoodData self = (FoodData) (Object) this;
			self.setFoodLevel(20);
			self.setSaturation(5.0f);
			ci.cancel();
		}
	}
}
