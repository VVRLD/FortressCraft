package dev.fortcraft.mixin;

import dev.fortcraft.Robots;
import dev.fortcraft.link.FortLink;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A witch's Jarate jar breaking: the player within 3 blocks is covered in TF2's Jarate. Since
 * round 4 also Soldier rockets and Demoman pipes going off (Robots.blast).
 */
@Mixin(Snowball.class)
public abstract class SnowballMixin {
	@Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
	private void fortcraft$jarSplash(HitResult result, CallbackInfo ci) {
		Snowball self = (Snowball) (Object) this;
		if (self.level().isClientSide()) {
			return;
		}
		if (Robots.ROCKETS.contains(self.getId()) || Robots.PIPES.contains(self.getId())) {
			if (self.level() instanceof net.minecraft.server.level.ServerLevel level) {
				Robots.blast(level, self, result.getLocation());
			}
			self.discard();  // TF2 sees it gone and plays the explosion
			ci.cancel();
			return;
		}
		if (!Robots.JARS.contains(self.getId())) {
			return;
		}
		Vec3 at = result.getLocation();
		if (!self.level().getEntitiesOfClass(ServerPlayer.class, new AABB(at, at).inflate(3.0)).isEmpty()) {
			FortLink.writePlayerJarate();
		}
		self.discard();  // TF2 sees it gone and plays the splash
		ci.cancel();
	}
}
