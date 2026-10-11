package dev.fortcraft.mixin;

import dev.fortcraft.Combat;
import dev.fortcraft.Robots;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While TF2 is linked, witches (drawn as Jarate Snipers) throw Jarate instead of potions (Alex,
 * 2026-10-11). The jar is a snowball Minecraft doesn't draw; TF2 draws the jar, and its hit
 * covers the player in Jarate (SnowballMixin).
 */
@Mixin(Witch.class)
public abstract class WitchMixin {
	@Inject(method = "performRangedAttack", at = @At("HEAD"), cancellable = true)
	private void fortcraft$jarate(LivingEntity target, float power, CallbackInfo ci) {
		if (!Combat.linked) {
			return;
		}
		Witch self = (Witch) (Object) this;
		Snowball jar = new Snowball(self.level(), self, new ItemStack(Items.SNOWBALL));
		double dx = target.getX() - self.getX(), dz = target.getZ() - self.getZ();
		double dy = target.getEyeY() - 1.1 - jar.getY();
		jar.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.2, dz, 0.9f, 6.0f);
		Robots.JARS.add(jar.getId());
		self.level().addFreshEntity(jar);
		Robots.noteAttack(self.getId());
		ci.cancel();
	}
}
