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
	 * move the inventory to F (Minecraft's swap-offhand key, which then gets no key) and unbind
	 * TF2's manual reload. Both can be bound again in Controls. Once moved, players' own choices
	 * are left alone.
	 */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void fortcraft$inventoryOnR(CallbackInfo ci) {
		Options self = (Options) (Object) this;
		InputConstants.Key e = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_E);
		InputConstants.Key r = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_R);
		// R too: the version from earlier on 2026-10-10 put the inventory on R, and Minecraft may
		// have saved that already.
		if ((self.keyInventory.matches(e) || self.keyInventory.matches(r)) && Tf2Keys.BACKPACK.matches(e)) {
			InputConstants.Key f = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F);
			if (Tf2Keys.RELOAD.matches(r)) {
				Tf2Keys.RELOAD.setKey(InputConstants.UNKNOWN);
			}
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
