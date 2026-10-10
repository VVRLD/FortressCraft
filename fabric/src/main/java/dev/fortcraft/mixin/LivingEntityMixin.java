package dev.fortcraft.mixin;

import dev.fortcraft.Combat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No XP from mobs while TF2 is linked: kills earn MvM money instead (Combat.mvmMoney). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "dropExperience", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noXp(ServerLevel level, Entity killer, CallbackInfo ci) {
		if (Combat.linked) {
			ci.cancel();
		}
	}
}
