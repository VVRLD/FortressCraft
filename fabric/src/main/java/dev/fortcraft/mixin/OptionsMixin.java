package dev.fortcraft.mixin;

import dev.fortcraft.Tf2Keys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public abstract class OptionsMixin {
	@Shadow
	@Final
	@Mutable
	public KeyMapping[] keyMappings;

	/**
	 * Add FortCraft's TF2 keys (Tf2Keys) to Minecraft's key list just before the constructor reads
	 * options.txt, so they show in Controls and their rebinds are saved and loaded like Minecraft's.
	 */
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;load()V"))
	private void fortcraft$addTf2Keys(CallbackInfo ci) {
		keyMappings = Tf2Keys.addTo(keyMappings);
	}
}
