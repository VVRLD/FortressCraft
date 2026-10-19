package dev.fortcraft.mixin;

import dev.fortcraft.mvm.MvmBlocks;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Blocks.class)
public abstract class BlocksMixin {
	/**
	 * Add FortCraft's blocks after Minecraft's own, but before Blocks' last step
	 * (SolidDebugger.runAndDump) puts every block's states into Block.BLOCK_STATE_REGISTRY;
	 * a block added after that has no state ids and can't be saved or sent to the client.
	 */
	@Inject(method = "<clinit>", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/state/SolidDebugger;runAndDump(Ljava/lang/Runnable;)V"))
	private static void fortcraft$registerBlocks(CallbackInfo ci) {
		MvmBlocks.register();
	}
}
