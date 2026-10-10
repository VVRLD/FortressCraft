package dev.fortcraft.mixin;

import dev.fortcraft.FortCraftText;
import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLanguage.class)
public abstract class ClientLanguageMixin {
	/**
	 * FortCraft's own names (supply packs, the "FortCraft (TF2)" keys in Controls). This mod runs
	 * without Fabric API, so Minecraft never loads its assets/fortcraft/lang file and showed raw
	 * keys such as "item.fortcraft.large_health_pack". FortCraftText reads that file itself.
	 */
	@Inject(method = "getOrDefault", at = @At("HEAD"), cancellable = true)
	private void fortcraft$text(String key, String fallback, CallbackInfoReturnable<String> cir) {
		String text = FortCraftText.get(key);
		if (text != null) {
			cir.setReturnValue(text);
		}
	}

	@Inject(method = "has", at = @At("HEAD"), cancellable = true)
	private void fortcraft$has(String key, CallbackInfoReturnable<Boolean> cir) {
		if (FortCraftText.get(key) != null) {
			cir.setReturnValue(true);
		}
	}
}
