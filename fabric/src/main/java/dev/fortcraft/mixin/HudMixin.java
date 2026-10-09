package dev.fortcraft.mixin;

import dev.fortcraft.Overlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudMixin {
	/** While TF2 is linked, its HUD replaces Minecraft's (hotbar, hearts, crosshair). */
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void fortcraft$tf2Hud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (Overlay.active()) {
			Overlay.draw(graphics);
			ci.cancel();
		}
	}
}
