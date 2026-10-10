package dev.fortcraft;

import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Which foods count as cooked (15-50 TF2 health) rather than raw (5-15). */
public final class FoodHealing {
	/** Cooked, baked or prepared foods that aren't named "cooked_...". */
	private static final Set<String> PREPARED = Set.of(
		"baked_potato", "bread", "pumpkin_pie", "cake", "cookie", "mushroom_stew", "rabbit_stew",
		"beetroot_soup", "suspicious_stew", "golden_carrot", "golden_apple", "enchanted_golden_apple",
		"honey_bottle", "dried_kelp");

	private FoodHealing() {
	}

	public static boolean isCooked(ItemStack stack) {
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		return path.startsWith("cooked_") || PREPARED.contains(path);
	}
}
