package dev.fortcraft.mvm;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

/** FortCraft's Mann vs. Machine blocks and items (2026-10-19: the Ghost Town portal). */
public final class MvmBlocks {
	public static MvmPortalBlock PORTAL;
	public static Item UNUSUAL_FLINT_AND_STEEL;

	private MvmBlocks() {
	}

	/** From BlocksMixin, while Minecraft's block registry is still open. */
	public static void register() {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("fortcraft", "mvm_portal"));
		PORTAL = Registry.register(BuiltInRegistries.BLOCK, key, new MvmPortalBlock(BlockBehaviour.Properties.of()
			.setId(key)
			.noCollision()
			.randomTicks()
			.strength(-1.0F)
			.sound(SoundType.GLASS)
			.lightLevel(state -> 11)
			.pushReaction(PushReaction.IMMOVEABLE)
			.noLootTable()));
	}

	/** From ItemsMixin, while Minecraft's item registry is still open. */
	public static void registerItems() {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("fortcraft", "unusual_flint_and_steel"));
		UNUSUAL_FLINT_AND_STEEL = Registry.register(BuiltInRegistries.ITEM, key,
			new UnusualFlintAndSteel(new Item.Properties().setId(key).durability(64)));
	}
}
