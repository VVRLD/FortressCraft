package dev.fortcraft.mixin;

import dev.fortcraft.Combat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * While TF2 is linked, TF2 decides where the player is. Minecraft's server re-checks each move
 * against its own collision and, in survival, sends the player back when they differ ("moved
 * wrongly"): after a TF2 respawn or a fast blast jump the server kept its player at the old spot
 * (where mobs went on hitting it) while the client was put back at TF2's position every frame,
 * flipping between the two. Here the server takes the position as given, as it does in creative.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMoveMixin {
	@Shadow
	private boolean isEntityCollidingWithAnythingNew(LevelReader level, Entity entity, AABB box, double x, double y, double z) {
		throw new AssertionError();
	}

	@Redirect(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isCreative()Z"))
	private boolean fortcraft$noMovedWrongly(ServerPlayer self) {
		return Combat.linked || self.isCreative();
	}

	@Redirect(method = "handlePlayerPositionChange", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isEntityCollidingWithAnythingNew(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;DDD)Z"))
	private boolean fortcraft$noCollisionRejects(ServerGamePacketListenerImpl self, LevelReader level, Entity entity, AABB box, double x, double y, double z) {
		return !Combat.linked && isEntityCollidingWithAnythingNew(level, entity, box, x, y, z);
	}
}
