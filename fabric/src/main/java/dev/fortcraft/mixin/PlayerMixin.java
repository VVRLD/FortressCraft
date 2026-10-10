package dev.fortcraft.mixin;

import dev.fortcraft.Combat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A cloaked Spy can't be seen as an enemy: mobs' targeting (goals, brains, "can attack" checks)
 * all ask canBeSeenAsEnemy, so they don't pick you and drop you, until you show again (Alex,
 * 2026-10-10). Combat.cloaked comes from TF2 (more than 75% invisible).
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "canBeSeenAsEnemy", at = @At("HEAD"), cancellable = true)
	private void fortcraft$cloaked(CallbackInfoReturnable<Boolean> cir) {
		if (Combat.linked && Combat.cloaked && (Object) this instanceof ServerPlayer) {
			cir.setReturnValue(false);
		}
	}
}
