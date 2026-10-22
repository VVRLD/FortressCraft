package dev.fortcraft.mixin;

import dev.fortcraft.mvm.MvmBlocks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreativeModeTab.class)
public abstract class CreativeModeTabMixin {
	@Shadow
	private Collection<ItemStack> displayItems;
	@Shadow
	private Set<ItemStack> displayItemsSearchTab;

	/**
	 * The Unusual Flint and Steel in the creative menu, next to the normal one in Tools & Utilities,
	 * and in search (Alex, 2026-10-19). Without Fabric API there is no item-group event for this.
	 */
	@Inject(method = "buildContents", at = @At("TAIL"))
	private void fortcraft$addItems(CreativeModeTab.ItemDisplayParameters parameters, CallbackInfo ci) {
		Identifier id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey((CreativeModeTab) (Object) this);
		if (id == null || !id.getPath().equals("tools_and_utilities") || MvmBlocks.UNUSUAL_FLINT_AND_STEEL == null) return;
		ItemStack stack = new ItemStack(MvmBlocks.UNUSUAL_FLINT_AND_STEEL);
		ArrayList<ItemStack> items = new ArrayList<>(displayItems);
		int at = items.size();
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).is(net.minecraft.world.item.Items.FLINT_AND_STEEL)) at = i + 1;
		}
		items.add(at, stack);
		displayItems = items;
		Set<ItemStack> search = new LinkedHashSet<>(displayItemsSearchTab);
		search.add(stack.copy());
		displayItemsSearchTab = search;
	}
}
