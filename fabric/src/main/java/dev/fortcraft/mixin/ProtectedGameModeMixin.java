package dev.fortcraft.mixin;

import dev.fortcraft.mvm.GhostTown;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class ProtectedGameModeMixin {
	@Shadow
	protected ServerLevel level;

	/** Players (creative included) can't break Ghost Town blocks; the client gets the block back. */
	@Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noPlayerBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (GhostTown.isProtected(level, pos)) cir.setReturnValue(false);  // fire can still be punched out
	}
}
