package dev.fortcraft.mixin;

import dev.fortcraft.Hand;
import dev.fortcraft.Overlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Holding a block without TF2's backpack (GitHub issues 24, 28: when Valve's item server doesn't
 * answer, TF2's backpack never loads). In Minecraft's own inventory, a middle click on one of
 * your items holds it, exactly as "Equip to hand" in TF2's backpack does (docs/DESIGN.md).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
	private static final int MIDDLE_BUTTON = 2;

	@Shadow
	protected Slot hoveredSlot;

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void fortcraft$holdFromInventory(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		if (event.button() != MIDDLE_BUTTON || !Overlay.active()) {
			return;
		}
		Slot slot = hoveredSlot;
		if (slot == null || !(slot.container instanceof Inventory) || slot.getItem().isEmpty()) {
			return;
		}
		if (Hand.holdFromInventory(Minecraft.getInstance(), slot.getItem())) {
			cir.setReturnValue(true);
		}
	}
}
