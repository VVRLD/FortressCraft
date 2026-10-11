package dev.fortcraft.mixin;

import dev.fortcraft.Combat;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Raiders TF2 draws as MvM robots speak with robot voices from TF2, so Minecraft's own voices for
 * them (ambient, hurt, death, celebrate, steps) are muted while linked (Alex, 2026-10-11: "i hear
 * the pillager noises"). Their weapons (crossbow, evoker fangs) and the raid horn still play.
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	private static final String[] ROBOT_SOUNDS = {
		"entity.pillager.", "entity.vindicator.", "entity.evoker.", "entity.vex.", "entity.ravager.", "entity.witch.",
	};

	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void fortcraft$muteRobotMobs(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		if (!Combat.linked || sound == null || sound.getIdentifier() == null) {
			return;
		}
		String path = sound.getIdentifier().getPath();
		for (String prefix : ROBOT_SOUNDS) {
			if (path.startsWith(prefix)) {
				cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
				return;
			}
		}
	}
}
