package dev.fortcraft.mixin;

import dev.fortcraft.Overlay;
import dev.fortcraft.Puppet;
import dev.fortcraft.link.FortLink;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	public abstract Entity entity();

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	/**
	 * Put Minecraft's camera at TF2's eye. TF2's eye (68 units for a standing Soldier, about 1.42
	 * blocks) is lower than Minecraft's (1.62), so TF2's rockets and smoke, and the invisible
	 * block outlines TF2 uses to hide them, came out shifted against Minecraft's world.
	 */
	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void fortcraft$tf2Eye(float partialTick, CallbackInfo ci) {
		// Taunting: TF2 renders from its third-person taunt camera; follow it exactly, so TF2's
		// picture of your Soldier lines up with Minecraft's world.
		FortLink.Tf2Camera cam = Overlay.active() ? FortLink.readCamera() : null;
		if (cam != null && cam.thirdPerson()) {
			setPosition(cam.x(), cam.y(), cam.z());
			setRotation(cam.yaw(), cam.pitch());
			return;
		}
		float eye = Puppet.tf2EyeHeight();
		Entity e = entity();
		if (eye > 0 && e != null) {
			// Stand and look where TF2 stood and looked when it drew the overlay on screen, so TF2's
			// buildings and effects stay planted in the world while turning and moving (see
			// Overlay.ANCHOR).
			double[] at = Overlay.anchoredPosition();
			if (at != null) {
				setPosition(at[0], at[1], at[2]);
			} else {
				setPosition(e.getX(), e.getY() + eye, e.getZ());
			}
			// Look the way TF2 looked when it drew the overlay on screen, so TF2's buildings and
			// effects stay planted in the world (see Overlay.ANCHOR).
			float[] look = Overlay.anchoredLook(e.getYRot(), e.getXRot());
			if (look != null) {
				setRotation(look[0], look[1]);
			}
		}
	}

	/** TF2's Sniper scope (and other zooms) narrow Minecraft's view by the same factor. */
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void fortcraft$tf2Zoom(float partialTick, CallbackInfoReturnable<Float> cir) {
		FortLink.Tf2Camera cam = Overlay.active() ? FortLink.readCamera() : null;
		if (cam != null && cam.zoom() > 0.01f && cam.zoom() < 0.999f) {
			double half = Math.toRadians(cir.getReturnValueF()) * 0.5;
			cir.setReturnValue((float) Math.toDegrees(2.0 * Math.atan(Math.tan(half) * cam.zoom())));
		}
	}
}
