package dev.fortcraft.mixin;

import dev.fortcraft.PackItems;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Items.class)
public abstract class ItemsMixin {
	/**
	 * Add FortCraft's supply packs right after Minecraft's own items, while the item registry still
	 * accepts new entries. Minecraft freezes its registries (BuiltInRegistries.bootStrap: createContents,
	 * then freeze) before mod entrypoints run, and without Fabric API nothing reopens them, so
	 * registering from FortCraft.onInitialize crashed with "This registry can't create intrusive holders".
	 */
	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void fortcraft$registerPacks(CallbackInfo ci) {
		PackItems.register();
	}
}
