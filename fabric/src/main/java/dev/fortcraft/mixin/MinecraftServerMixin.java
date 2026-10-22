package dev.fortcraft.mixin;

import dev.fortcraft.mvm.GhostTown;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	/** Before any dimension of the world opens: swap in the Coal Town map's chunks if needed. */
	@Inject(method = "createLevels", at = @At("HEAD"))
	private void fortcraft$beforeLevels(CallbackInfo ci) {
		GhostTown.beforeLevels((MinecraftServer) (Object) this);
	}

	/** Mann vs. Machine waves in the Ghost Town. */
	@Inject(method = "tickServer", at = @At("TAIL"))
	private void fortcraft$waves(java.util.function.BooleanSupplier haveTime, CallbackInfo ci) {
		dev.fortcraft.mvm.GhostTownWaves.tick((MinecraftServer) (Object) this);
	}
}
