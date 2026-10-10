package dev.fortcraft.mixin;

import dev.fortcraft.Combat;
import dev.fortcraft.FortCraft;
import dev.fortcraft.link.FortLink;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	/** Minecraft's hit cooldown: half a second (10 ticks) after a hit, only a bigger hit counts. */
	@Unique
	private static final int FORTCRAFT$COOLDOWN_TICKS = 10;

	/** Game tick of the last hit sent; far in the past at first (not Long.MIN_VALUE: now - MIN_VALUE overflows). */
	@Unique
	private long fortcraft$lastHitTick = -1_000_000L;

	@Unique
	private float fortcraft$lastHitAmount;

	/**
	 * While TF2 is linked, TF2's health is the real one: damage to Minecraft's player (mobs, lava,
	 * drowning...) is sent to TF2 instead, in Minecraft damage points, and TF2 scales it by 7.5x
	 * (1 heart = 15 TF2 HP). Cancelling Minecraft's damage also skipped
	 * Minecraft's hit cooldown, so a slime touching you hit every tick (20 times a second); the
	 * cooldown is applied here instead, the way Minecraft does it. Falls are left to TF2's own
	 * fall damage. In creative mode nothing is sent (TF2 also turns god mode on).
	 */
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void fortcraft$toTf2(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (!Combat.linked) {
			return;
		}
		ServerPlayer self = (ServerPlayer) (Object) this;
		cir.setReturnValue(false);
		if (self.isCreative() || source.is(DamageTypeTags.IS_FALL) || amount <= 0) {
			return;
		}
		long now = level.getGameTime();
		float send = amount;
		if (now >= fortcraft$lastHitTick && now - fortcraft$lastHitTick < FORTCRAFT$COOLDOWN_TICKS) {
			if (amount <= fortcraft$lastHitAmount) {
				return; // still recovering from the last hit
			}
			send = amount - fortcraft$lastHitAmount; // a bigger hit: only the difference, as Minecraft does
			fortcraft$lastHitAmount = amount;
		} else {
			fortcraft$lastHitTick = now;
			fortcraft$lastHitAmount = amount;
		}
		Vec3 from = source.getSourcePosition() != null ? source.getSourcePosition() : self.position();
		FortLink.writeHurt(send, from.x, from.y, from.z);
		FortCraft.LOG.info("FortCraft: {} hit the player for {} Minecraft health; sent to TF2", source.getMsgId(),
			String.format("%.1f", send));
	}
}
