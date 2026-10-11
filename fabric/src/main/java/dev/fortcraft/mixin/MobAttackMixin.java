package dev.fortcraft.mixin;

import dev.fortcraft.Robots;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every melee hit a mob makes is told to TF2, so the robot it is drawn as swings (Alex,
 * 2026-10-11: the Demoknights didn't swing; Minecraft's client swing flag never showed it).
 */
@Mixin(Mob.class)
public abstract class MobAttackMixin {
	@Inject(method = "doHurtTarget", at = @At("HEAD"))
	private void fortcraft$attack(ServerLevel level, Entity target, CallbackInfoReturnable<Boolean> cir) {
		Mob self = (Mob) (Object) this;
		Robots.noteAttack(self.getId());
		Robots.critPending = Robots.rollCrit(self);  // robots' melee can crit too
	}

	@Inject(method = "doHurtTarget", at = @At("RETURN"))
	private void fortcraft$attackDone(ServerLevel level, Entity target, CallbackInfoReturnable<Boolean> cir) {
		Robots.critPending = false;
	}
}
