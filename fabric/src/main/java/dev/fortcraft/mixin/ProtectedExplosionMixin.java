package dev.fortcraft.mixin;

import dev.fortcraft.mvm.GhostTown;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
public abstract class ProtectedExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	/** Minecraft explosions (creepers, TNT) still hurt, but break no Ghost Town blocks. */
	@Inject(method = "calculateExplodedPositions", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noBlastHoles(CallbackInfoReturnable<List<BlockPos>> cir) {
		if (GhostTown.isProtected(level)) cir.setReturnValue(new ArrayList<>());
	}
}
