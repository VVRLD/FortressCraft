package dev.fortcraft.mixin;

import dev.fortcraft.mvm.GhostTown;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public abstract class ProtectedFireMixin {
	/** Fire doesn't burn Ghost Town blocks away (it would replace them with fire, not remove them). */
	@Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noBurn(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
		if (GhostTown.isProtected(level)) ci.cancel();
	}

	/**
	 * In the Ghost Town fire never spreads (Alex, 2026-10-19): instead of Minecraft's fire tick
	 * (which lights neighbouring blocks), each flame just goes out after a few seconds.
	 */
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noSpread(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (!GhostTown.isProtected(level)) return;
		ci.cancel();
		if (random.nextInt(3) == 0) {
			level.removeBlock(pos, false);
		} else {
			level.scheduleTick(pos, (FireBlock) (Object) this, 30 + random.nextInt(10));
		}
	}
}
