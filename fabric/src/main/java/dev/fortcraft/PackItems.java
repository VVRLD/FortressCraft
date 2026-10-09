package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** Original FortCraft supply items. TF2, not Minecraft, owns the health/ammo they restore. */
public final class PackItems {
	private record Pack(String key, String name, Item ingredient, int cost, int kind, PackItem item) {
	}

	private static final List<Pack> PACKS = new ArrayList<>();
	private static int pendingRequest;
	private static UUID pendingPlayer;
	private static PackItem pendingItem;

	private PackItems() {
	}

	public static void register() {
		add("small_health_pack", "Small Health Pack", Items.APPLE, 5, 1);
		add("large_health_pack", "Large Health Pack", Items.APPLE, 9, 2);
		add("small_ammo_pack", "Small Ammo Pack", Items.IRON_INGOT, 5, 3);
		add("large_ammo_pack", "Large Ammo Pack", Items.IRON_INGOT, 9, 4);
	}

	private static void add(String path, String name, Item ingredient, int cost, int kind) {
		Identifier id = Identifier.fromNamespaceAndPath("fortcraft", path);
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
		PackItem item = Registry.register(BuiltInRegistries.ITEM, key,
			new PackItem(kind, name, new Item.Properties().setId(key).stacksTo(16)));
		PACKS.add(new Pack(id.toString(), name, ingredient, cost, kind, item));
	}

	public static boolean isPackRecipe(String key) {
		return PACKS.stream().anyMatch(pack -> pack.key().equals(key));
	}

	/**
	 * Every supply pack, always (Alex, 2026-10-09: show them whether or not you have the
	 * ingredients). The inputs line says how many you have; crafting still checks on the server.
	 */
	public static List<FortLink.CraftRecipe> craftable(ServerPlayer player) {
		List<FortLink.CraftRecipe> result = new ArrayList<>();
		for (Pack pack : PACKS) {
			int have = count(player.getInventory(), pack.ingredient());
			String ingredientId = BuiltInRegistries.ITEM.getKey(pack.ingredient()).toString();
			result.add(new FortLink.CraftRecipe(pack.key(), pack.name() + " x1",
				pack.ingredient().getName(new ItemStack(pack.ingredient())).getString() + " x" + pack.cost()
					+ (have >= pack.cost() ? "" : " (you have " + have + ")"),
				pack.key(), Backpack.icon(net.minecraft.client.Minecraft.getInstance(), pack.key()),
				List.of(ingredientId)));
		}
		return result;
	}

	/** Runs on Minecraft's integrated server, rechecking ingredients before removing any. */
	public static boolean craft(ServerPlayer player, String key) {
		Pack pack = PACKS.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
		if (pack == null || count(player.getInventory(), pack.ingredient()) < pack.cost()) return false;
		Inventory inv = player.getInventory();
		int left = pack.cost();
		for (int slot = 0; slot < 36 && left > 0; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (!stack.is(pack.ingredient())) continue;
			int take = Math.min(left, stack.getCount());
			inv.removeItem(slot, take);
			left -= take;
		}
		inv.placeItemBackInInventory(new ItemStack(pack.item()), Prediction.SERVER_ONLY);
		inv.setChanged();
		FortCraft.LOG.info("FortCraft: crafted {} using {} x{}", pack.name(), pack.ingredient(), pack.cost());
		return true;
	}

	private static int count(Inventory inv, Item item) {
		int total = 0;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	/** True for a supply pack's item id ("fortcraft:small_health_pack" ...). */
	public static boolean isPackId(String id) {
		return PACKS.stream().anyMatch(pack -> pack.key().equals(id));
	}

	/**
	 * "Use" from TF2's backpack: take one pack of this kind from Minecraft's inventory and ask
	 * TF2 to apply it, as right-clicking a held pack does. Runs on Minecraft's integrated server.
	 */
	public static void useFromInventory(ServerPlayer player, String id) {
		Pack pack = PACKS.stream().filter(p -> p.key().equals(id)).findFirst().orElse(null);
		if (pack == null || pendingRequest != 0) {
			return;
		}
		Inventory inv = player.getInventory();
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.is(pack.item())) {
				int request = FortLink.requestPackUse(pack.kind());
				if (request == 0) {
					return;
				}
				pendingRequest = request;
				pendingPlayer = player.getUUID();
				pendingItem = pack.item();
				inv.removeItem(slot, 1);
				inv.setChanged();
				FortCraft.LOG.info("FortCraft: used {} from TF2's backpack (request {})", pack.name(), request);
				return;
			}
		}
		FortCraft.LOG.info("FortCraft: no {} in Minecraft's inventory to use", pack.name());
	}

	public static boolean heldPack(Player player) {
		return player.getMainHandItem().getItem() instanceof PackItem;
	}

	/** Poll TF2's reply from the integrated server's ordinary crafting scan. */
	public static void tickServer(ServerPlayer player) {
		if (pendingRequest == 0 || FortLink.packUseResult() != pendingRequest) return;
		boolean ok = FortLink.packUseOk();
		if (!ok && pendingPlayer.equals(player.getUUID())) {
			player.getInventory().placeItemBackInInventory(new ItemStack(pendingItem), Prediction.SERVER_ONLY);
			player.getInventory().setChanged();
		}
		FortCraft.LOG.info("FortCraft: supply pack {}", ok ? "used" : "returned (already full or unavailable)");
		pendingRequest = 0;
		pendingPlayer = null;
		pendingItem = null;
	}

	private static final class PackItem extends Item {
		private final int kind;
		private final String displayName;

		private PackItem(int kind, String displayName, Properties properties) {
			super(properties);
			this.kind = kind;
			this.displayName = displayName;
		}

		/** Its plain name everywhere (backpack, crafting, tooltips), whatever the language files do. */
		@Override
		public net.minecraft.network.chat.Component getName(ItemStack stack) {
			return net.minecraft.network.chat.Component.literal(displayName);
		}

		@Override
		public InteractionResult use(Level level, Player player, InteractionHand hand) {
			if (!Combat.linked || !Hand.equipped()) return InteractionResult.PASS;
			if (level.isClientSide()) return InteractionResult.SUCCESS;
			if (!(player instanceof ServerPlayer serverPlayer) || pendingRequest != 0) return InteractionResult.FAIL;
			int request = FortLink.requestPackUse(kind);
			if (request == 0) return InteractionResult.FAIL;
			pendingRequest = request;
			pendingPlayer = serverPlayer.getUUID();
			pendingItem = this;
			player.getItemInHand(hand).shrink(1);
			player.getInventory().setChanged();
			FortCraft.LOG.info("FortCraft: asked TF2 to use supply pack kind={} request={}", kind, request);
			return InteractionResult.SUCCESS_SERVER;
		}
	}
}
