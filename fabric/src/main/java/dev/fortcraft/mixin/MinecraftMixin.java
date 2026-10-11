package dev.fortcraft.mixin;

import dev.fortcraft.FortCraft;
import dev.fortcraft.Overlay;
import dev.fortcraft.Puppet;
import dev.fortcraft.RightClick;
import dev.fortcraft.Taunts;
import dev.fortcraft.Tf2Menus;
import dev.fortcraft.VoiceCommands;
import dev.fortcraft.WeaponSelection;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/**
	 * Minecraft aims (the block outline, placing, using) from its own player's eyes, 1.62 blocks up,
	 * but you look through TF2's camera, lower for most mercs. So a block you aimed at the side of
	 * could get hit on its top and placing put the new block on top of it (Alex, 2026-10-11). While
	 * linked, re-aim at blocks from the camera you actually see through.
	 */
	@Inject(method = "pick", at = @At("TAIL"))
	private void fortcraft$aimFromCamera(float partialTick, CallbackInfo ci) {
		Minecraft self = (Minecraft) (Object) this;
		if (!dev.fortcraft.Combat.linked || self.player == null || self.level == null || Puppet.tf2EyeHeight() <= 0
			|| self.hitResult instanceof net.minecraft.world.phys.EntityHitResult) {
			return;
		}
		net.minecraft.client.Camera camera = self.gameRenderer.mainCamera();
		net.minecraft.world.phys.Vec3 from = camera.position();
		org.joml.Vector3fc f = camera.forwardVector();
		net.minecraft.world.phys.Vec3 to = from.add(new net.minecraft.world.phys.Vec3(f.x(), f.y(), f.z()).scale(self.player.blockInteractionRange()));
		self.hitResult = self.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.OUTLINE,
			net.minecraft.world.level.ClipContext.Fluid.NONE, self.player));
	}

	@Unique
	private static boolean fortcraft$useBlockedThisHold;

	/** Once per render frame, after this frame's game ticks and right before drawing. */
	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
	private void fortcraft$beforeRender(boolean advanceGameTime, CallbackInfo ci) {
		Puppet.beforeRender((Minecraft) (Object) this);
	}

	/** While TF2 is linked: number keys and Q go to TF2 during taunts, and P is TF2's menu key. */
	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void fortcraft$tauntKeys(CallbackInfo ci) {
		Minecraft minecraft = (Minecraft) (Object) this;
		if (!minecraft.options.keyUse.isDown()) {
			fortcraft$useBlockedThisHold = false;
		}
		if (Overlay.active()) {
			Taunts.beforeKeybinds(minecraft);
			Tf2Menus.beforeKeybinds(minecraft);
			VoiceCommands.beforeKeybinds(minecraft);
			WeaponSelection.beforeKeybinds(minecraft);
		}
	}

	/**
	 * Until the backpack equips a Minecraft item, every hotbar position belongs to TF2: Minecraft's
	 * own item use never runs. Aiming at a door, chest etc. still opens it (RightClick).
	 */
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noHiddenItemUse(CallbackInfo ci) {
		if (Overlay.active()) {
			Minecraft minecraft = (Minecraft) (Object) this;
			if (!fortcraft$useBlockedThisHold) {
				int slot = minecraft.player == null ? -1 : minecraft.player.getInventory().getSelectedSlot() + 1;
				FortCraft.LOG.info("FortCraft: blocked Minecraft item use while TF2 weapon slot {} is active", slot);
				fortcraft$useBlockedThisHold = true;
			}
			RightClick.minecraftUse(minecraft);
			ci.cancel();
		}
	}

	/** While TF2 is linked, the attack button fires TF2's weapon instead of punching/breaking. */
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noAttack(CallbackInfoReturnable<Boolean> cir) {
		if (Overlay.active()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void fortcraft$noContinueAttack(boolean attacking, CallbackInfo ci) {
		if (Overlay.active()) {
			ci.cancel();
		}
	}
}
