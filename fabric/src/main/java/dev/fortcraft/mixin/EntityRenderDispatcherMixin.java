package dev.fortcraft.mixin;

import dev.fortcraft.Robots;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Raiders TF2 draws as MvM robots aren't drawn by Minecraft (their model, held item, banner,
 * name and shadow), so only the robot shows (Alex, 2026-10-11).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void fortcraft$robots(E entity, Frustum frustum, double x, double y, double z, float partialTick,
		CallbackInfoReturnable<Boolean> cir) {
		if (Robots.drawnByTf2(entity)) {
			cir.setReturnValue(false);
		}
	}
}
