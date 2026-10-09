package dev.fortcraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.fortcraft.Overlay;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/**
	 * No Minecraft view bobbing while TF2 is linked. Minecraft bobs its camera from its own
	 * walking speed and on-ground state, which come from Minecraft's movement code, not from TF2's
	 * (TF2 has no camera bob). That made the world sway and twitch under TF2's steady weapon
	 * and HUD, especially when the on-ground state flickered (jumps, landing, walls).
	 */
	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noBob(CameraRenderState camera, PoseStack poseStack, CallbackInfo ci) {
		if (Overlay.active()) {
			ci.cancel();
		}
	}

	/**
	 * No Minecraft hurt tilt while TF2 is linked either: TF2 owns damage and its feedback, and a
	 * tilted Minecraft camera moved the world against TF2's picture.
	 */
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noHurtTilt(CameraRenderState camera, PoseStack poseStack, CallbackInfo ci) {
		if (Overlay.active()) {
			ci.cancel();
		}
	}
}
