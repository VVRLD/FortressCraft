package dev.fortcraft;

import dev.fortcraft.link.FortLink;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * Backpack phase C (docs/DESIGN.md): Minecraft's 2x2 crafting, offered in TF2's crafting screen.
 * Twice a second Minecraft's built-in server lists the crafting recipes that fit a 2x2 grid and
 * that the player's inventory can make now, using Minecraft's own recipe matching; TF2 shows
 * them. A craft request from TF2 is done on the server like a crafting grid would: the inputs
 * are taken, leftovers (buckets) and the result go back into the inventory, or drop if full.
 */
public final class Crafting {
	private static final long INTERVAL_NANOS = 500_000_000L;
	private static final int MAX_RECIPES = 64;

	private static long lastScan;
	private static volatile List<FortLink.CraftRecipe> sent = List.of();
	private static int requestsSeen = -1;

	private Crafting() {
	}

	/** Called every frame while TF2 is linked (render thread); the work runs on the server thread. */
	public static void tick(Minecraft minecraft) {
		var server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null) {
			return;
		}
		UUID playerId = minecraft.player.getUUID();
		int requests = FortLink.craftRequestCount();
		if (requestsSeen == -1) {
			requestsSeen = requests; // don't replay a request from before Minecraft started
		}
		if (requests != requestsSeen) {
			requestsSeen = requests;
			String key = FortLink.craftRequestKey();
			server.execute(() -> craft(server.getPlayerList().getPlayer(playerId), key));
			lastScan = 0; // rescan right after
		}
		long now = System.nanoTime();
		if (now - lastScan < INTERVAL_NANOS) {
			return;
		}
		lastScan = now;
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				return;
			}
			List<FortLink.CraftRecipe> recipes = scan(player);
			if (!recipes.equals(sent)) {
				FortLink.writeRecipes(recipes);
				FortCraft.LOG.info("FortCraft: {} Minecraft recipes craftable: {}", recipes.size(),
					recipes.stream().map(FortLink.CraftRecipe::name).toList());
				sent = recipes;
			}
		});
	}

	/** One 2x2 recipe the inventory can make: which items it would use, laid out in the grid. */
	private record Plan(RecipeHolder<?> holder, CraftingRecipe recipe, CraftingInput input, List<Holder<Item>> used) {
	}

	private static List<FortLink.CraftRecipe> scan(ServerPlayer player) {
		StackedItemContents contents = contentsOf(player.getInventory());
		List<FortLink.CraftRecipe> out = new ArrayList<>();
		for (RecipeHolder<?> holder : player.level().getServer().getRecipeManager().getRecipes()) {
			Plan plan = plan(player, holder, contents);
			if (plan == null) {
				continue;
			}
			ItemStack result = assemble(plan);
			if (result.isEmpty()) {
				continue;
			}
			String resultId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
			List<String> ingredients = new ArrayList<>();
			for (Holder<Item> item : plan.used()) {
				ingredients.add(item.unwrapKey().map(k -> k.identifier().toString()).orElse(""));
			}
			out.add(new FortLink.CraftRecipe(holder.id().identifier().toString(), label(result), describe(plan.used()),
				resultId, Backpack.icon(Minecraft.getInstance(), resultId), ingredients));
		}
		out.sort(Comparator.comparing(FortLink.CraftRecipe::name).thenComparing(FortLink.CraftRecipe::key));
		return out.size() > MAX_RECIPES ? out.subList(0, MAX_RECIPES) : out;
	}

	private static void craft(ServerPlayer player, String key) {
		if (player == null || key.isEmpty()) {
			return;
		}
		var holder = player.level().getServer().getRecipeManager()
			.byKey(ResourceKey.create(Registries.RECIPE, Identifier.parse(key))).orElse(null);
		Plan plan = holder == null ? null : plan(player, holder, contentsOf(player.getInventory()));
		if (plan == null) {
			FortCraft.LOG.info("FortCraft: can't craft {} now (missing items or not a 2x2 recipe)", key);
			FortLink.writeCraftResult(false);
			return;
		}
		ItemStack result = assemble(plan);
		var remaining = plan.recipe().getRemainingItems(plan.input());
		Inventory inventory = player.getInventory();
		for (Holder<Item> item : plan.used()) {
			for (int slot = 0; slot < 36; slot++) {
				if (inventory.getItem(slot).getItem() == item.value()) {
					inventory.removeItem(slot, 1);
					break;
				}
			}
		}
		for (ItemStack leftover : remaining) {
			if (!leftover.isEmpty()) {
				inventory.placeItemBackInInventory(leftover, Prediction.SERVER_ONLY);
			}
		}
		String label = label(result);
		inventory.placeItemBackInInventory(result, Prediction.SERVER_ONLY);
		inventory.setChanged();
		FortCraft.LOG.info("FortCraft: crafted {} from {}", label, describe(plan.used()));
		FortLink.writeCraftResult(true);
	}

	private static StackedItemContents contentsOf(Inventory inventory) {
		StackedItemContents contents = new StackedItemContents();
		for (int slot = 0; slot < 36; slot++) {
			contents.accountStack(inventory.getItem(slot));
		}
		return contents;
	}

	/** Null unless this is an ordinary crafting recipe that fits 2x2 and the inventory can make. */
	private static Plan plan(ServerPlayer player, RecipeHolder<?> holder, StackedItemContents contents) {
		if (!(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial()) {
			return null;
		}
		PlacementInfo placement = recipe.placementInfo();
		if (placement.isImpossibleToPlace()) {
			return null;
		}
		int width;
		int height;
		if (recipe instanceof ShapedRecipe shaped) {
			width = shaped.getWidth();
			height = shaped.getHeight();
		} else if (recipe instanceof ShapelessRecipe && placement.ingredients().size() <= 4) {
			width = 2;
			height = 2;
		} else {
			return null;
		}
		if (width > 2 || height > 2) {
			return null;
		}
		List<Holder<Item>> used = new ArrayList<>();
		if (!contents.canCraft(recipe, used::add) || used.size() != placement.ingredients().size()) {
			return null;
		}
		List<ItemStack> grid = new ArrayList<>();
		if (recipe instanceof ShapedRecipe) {
			for (int index : placement.slotsToIngredientIndex()) {
				grid.add(index == PlacementInfo.EMPTY_SLOT ? ItemStack.EMPTY : new ItemStack(used.get(index)));
			}
		} else {
			for (int i = 0; i < width * height; i++) {
				grid.add(i < used.size() ? new ItemStack(used.get(i)) : ItemStack.EMPTY);
			}
		}
		CraftingInput input = CraftingInput.of(width, height, grid);
		if (!recipe.matches(input, player.level())) {
			return null;
		}
		return new Plan(holder, recipe, input, used);
	}

	@SuppressWarnings("unchecked")
	private static ItemStack assemble(Plan plan) {
		return ((Recipe<CraftingInput>) plan.recipe()).assemble(plan.input());
	}

	private static String label(ItemStack stack) {
		return stack.getHoverName().getString() + " x" + stack.getCount();
	}

	/** "Oak Planks x2, Stick x1": what the recipe uses. */
	private static String describe(List<Holder<Item>> used) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Holder<Item> item : used) {
			counts.merge(new ItemStack(item).getHoverName().getString(), 1, Integer::sum);
		}
		List<String> parts = new ArrayList<>();
		counts.forEach((name, n) -> parts.add(name + " x" + n));
		return String.join(", ", parts);
	}
}
