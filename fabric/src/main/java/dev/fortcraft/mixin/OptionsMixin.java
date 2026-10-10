package dev.fortcraft.mixin;

import dev.fortcraft.Tf2Keys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import com.mojang.blaze3d.platform.InputConstants;
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

	/**
	 * Alex (2026-10-10): E opens TF2's backpack, F opens Minecraft's inventory, and TF2 reloads by
	 * itself (cl_autoreload). So while Minecraft's inventory and the backpack are both still on E,
	 * move the inventory to F (Minecraft's swap-offhand key, which then gets no key). TF2's manual
	 * Reload stays on R (the Eureka Effect's teleport menu needs it). Once moved, players' own
	 * choices are left alone.
	 */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void fortcraft$inventoryOnR(CallbackInfo ci) {
		Options self = (Options) (Object) this;
		InputConstants.Key e = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_E);
		InputConstants.Key r = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_R);
		// R too: the version from earlier on 2026-10-10 put the inventory on R, and Minecraft may
		// have saved that already.
		InputConstants.Key f = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F);
		// TF2's Reload back on R (2026-10-10): with the inventory on F, R is free again, and the
		// Eureka Effect opens its teleport menu with Reload. An earlier version unbound it.
		if (self.keyInventory.matches(f) && Tf2Keys.RELOAD.isUnbound()) {
			Tf2Keys.RELOAD.setKey(r);
			KeyMapping.resetMapping();
		}
		if ((self.keyInventory.matches(e) || self.keyInventory.matches(r)) && Tf2Keys.BACKPACK.matches(e)) {
			if (self.keySwapOffhand.matches(f)) {
				self.keySwapOffhand.setKey(InputConstants.UNKNOWN);
			}
			self.keyInventory.setKey(f);
			KeyMapping.resetMapping();
			// Not saved from here (the constructor is still running): it simply happens again next start
			// until Minecraft next saves its options.
		}
	}
}
