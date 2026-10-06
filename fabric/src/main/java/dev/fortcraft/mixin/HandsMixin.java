package dev.fortcraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.fortcraft.Hand;
import dev.fortcraft.Overlay;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HandsMixin {
	/**
	 * While TF2 is linked, TF2's weapon (in the overlay) replaces Minecraft's hand, except while a
	 * Minecraft item is held through "Equip to hand": then Minecraft draws it and TF2 hides its weapon.
	 */
	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void fortcraft$hideHand(float partialTick, PoseStack poseStack, SubmitNodeCollector collector,
		PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState handsState, CallbackInfo ci) {
		if (Overlay.active() && !Hand.equipped()) {
			ci.cancel();
		}
	}
}
