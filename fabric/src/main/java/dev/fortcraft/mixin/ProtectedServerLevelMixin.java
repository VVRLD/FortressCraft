package dev.fortcraft.mixin;

import dev.fortcraft.mvm.GhostTown;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ProtectedServerLevelMixin {
	/** No cracks on Ghost Town blocks either, since they can't break (clearing cracks still works). */
	@Inject(method = "destroyBlockProgress", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noCracks(int breaker, BlockPos pos, int progress, CallbackInfo ci) {
		if (progress >= 0 && GhostTown.isProtected((ServerLevel) (Object) this)) ci.cancel();
	}
}
