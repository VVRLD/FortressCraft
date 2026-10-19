package dev.fortcraft.mixin;

import dev.fortcraft.FortCraftPack;
import java.util.function.Consumer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BuiltInPackSource.class)
public abstract class BuiltInPackSourceMixin {
	@Shadow
	@Final
	private PackType packType;

	/** Offer FortCraft's own pack next to Minecraft's built-in ones (client assets and server data). */
	@Inject(method = "loadPacks", at = @At("TAIL"))
	private void fortcraft$addPack(Consumer<Pack> consumer, CallbackInfo ci) {
		Pack pack = FortCraftPack.create(packType);
		if (pack != null) consumer.accept(pack);
	}
}
