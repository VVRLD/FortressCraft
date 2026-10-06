package dev.fortcraft.mixin;

import dev.fortcraft.Overlay;
import dev.fortcraft.WeaponSelection;
import dev.fortcraft.link.FortLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	/** During gameplay the wheel selects TF2 weapons, not Minecraft's hidden item stack. */
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void fortcraft$tf2WeaponScroll(long window, double scrollX, double scrollY, CallbackInfo ci) {
		if (Overlay.active() && WeaponSelection.onScroll(Minecraft.getInstance(), window, scrollX, scrollY)) {
			ci.cancel();
		}
	}

	/** While TF2 is zoomed (Sniper scope), turn slower by the same factor, as TF2 itself does. */
	@Inject(method = "turnPlayer", at = @At("HEAD"))
	private void fortcraft$zoomSensitivity(double movementTime, CallbackInfo ci) {
		FortLink.Tf2Camera cam = Overlay.active() ? FortLink.readCamera() : null;
		if (cam != null && cam.zoom() > 0.01f && cam.zoom() < 0.999f) {
			accumulatedDX *= cam.zoom();
			accumulatedDY *= cam.zoom();
		}
	}
}
