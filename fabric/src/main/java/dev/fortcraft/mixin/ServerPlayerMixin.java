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
	 * drowning...) is sent to TF2 instead, in Minecraft damage points, and TF2 scales it so 20
	 * Minecraft health is the class's full health. Cancelling Minecraft's damage also skipped
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
		// Suffocation ("inWall") is skipped too: TF2 owns where the player is, and Minecraft's taller
		// player poked its head into the block above when jumping under a low ceiling, which hurt
		// (Alex, 2026-10-10). TF2's own collision keeps the player out of blocks.
		if (self.isCreative() || source.is(DamageTypeTags.IS_FALL) || source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)
			|| amount <= 0) {
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
		// Poison and Wither hit once, then wear off, instead of ticking damage over and over
		// (Alex, 2026-10-11).
		if (source.is(net.minecraft.world.damagesource.DamageTypes.MAGIC) && self.hasEffect(net.minecraft.world.effect.MobEffects.POISON)) {
			self.removeEffect(net.minecraft.world.effect.MobEffects.POISON);
		}
		if (source.is(net.minecraft.world.damagesource.DamageTypes.WITHER)) {
			self.removeEffect(net.minecraft.world.effect.MobEffects.WITHER);
		}
		// Robots and raids hit at full strength (Alex, 2026-10-11: "let bots do 100% damage now").
		// Earlier the same day robots did 60% and raids halved everything.
		// What kind of hit, so the MvM upgrades' resistances apply (Alex, 2026-10-11): explosions are
		// blast, arrows and other projectiles (and robot gunfire) bullets, fire and lava fire.
		int kind = 0;
		if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			kind |= 1;
		} else if (source.is(DamageTypeTags.IS_PROJECTILE)) {
			kind |= 2;
		}
		if (source.is(DamageTypeTags.IS_FIRE)) {
			kind |= 4;
		}
		if (dev.fortcraft.Robots.critPending) {
			kind |= 8;  // a robot's crit: double damage, half of it the crit bonus
			send *= 2.0f;
		}
		Vec3 from = source.getSourcePosition() != null ? source.getSourcePosition() : self.position();
		FortLink.writeHurt(send, from.x, from.y, from.z, kind);
		FortCraft.LOG.info("FortCraft: {} hit the player for {} Minecraft health; sent to TF2", source.getMsgId(),
			String.format("%.1f", send));
	}
}
