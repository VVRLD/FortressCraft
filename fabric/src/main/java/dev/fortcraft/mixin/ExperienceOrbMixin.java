package dev.fortcraft.mixin;

import dev.fortcraft.Combat;
import dev.fortcraft.FortCraft;
import dev.fortcraft.link.FortLink;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While TF2 is linked, Minecraft's other XP (mining coal and other ores, taking items out of a
 * furnace, breeding, fishing, trading, bottles o' enchanting...) drops $5-10 of TF2's MvM cash
 * where the XP would have appeared, instead of XP orbs (Alex, 2026-10-10). Mobs' XP is handled
 * by LivingEntityMixin (Combat.mvmMoney). Every XP orb is created through awardWithDirection.
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
	private static final int MONEY_ONLY = 1 << 7;  // protocol kMobHitMoneyOnly

	@Inject(method = "awardWithDirection", at = @At("HEAD"), cancellable = true)
	private static void fortcraft$moneyInstead(ServerLevel level, Vec3 pos, Vec3 direction, int amount, CallbackInfo ci) {
		if (!Combat.linked || amount <= 0) {
			return;
		}
		int money = 5 + level.getRandom().nextInt(6);
		FortLink.writeMobHit(pos.x, pos.y, pos.z, 0.0f, true, MONEY_ONLY | (money << 16));
		FortCraft.LOG.info("FortCraft: Minecraft XP ({}) became ${} of MvM cash at {}", amount, money, pos);
		ci.cancel();
	}
}
