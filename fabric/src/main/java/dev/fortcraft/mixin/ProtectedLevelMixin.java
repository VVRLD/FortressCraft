package dev.fortcraft.mixin;

import dev.fortcraft.mvm.GhostTown;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class ProtectedLevelMixin {
	/**
	 * Nothing breaks blocks in the Ghost Town (Alex, 2026-10-19): TF2 gunfire, melee and explosions
	 * (Combat), mobs and fire all go through destroyBlock or removeBlock. FortCraft's own building
	 * uses setBlock, which is not blocked.
	 */
	@Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noDestroy(BlockPos pos, boolean drop, Entity breaker, int updateLimit, CallbackInfoReturnable<Boolean> cir) {
		if (GhostTown.isProtected((Level) (Object) this, pos)) cir.setReturnValue(false);
	}

	@Inject(method = "removeBlock", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noRemove(BlockPos pos, boolean moving, CallbackInfoReturnable<Boolean> cir) {
		if (GhostTown.isProtected((Level) (Object) this, pos)) cir.setReturnValue(false);
	}
}
