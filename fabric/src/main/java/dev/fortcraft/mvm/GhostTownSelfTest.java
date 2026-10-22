package dev.fortcraft.mvm;

import dev.fortcraft.FortCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Developer check, only with -Dfortcraft.selfTest=ghosttown: builds an iron frame next to the player,
 * lights it with the Unusual Flint and Steel, travels to the Ghost Town and back, logs what it finds
 * ("FortCraft selftest:" lines in latest.log) and closes Minecraft. Does nothing in normal play.
 */
public final class GhostTownSelfTest {
	private static final boolean ON = "ghosttown".equals(System.getProperty("fortcraft.selfTest"));
	private static int step;
	private static int wait;
	private static BlockPos portal;
	private static volatile boolean busy;
	private static boolean wavesDone;
	private static boolean bombChecked;
	private static long autoSince;
	private static boolean autoDone;
	private static BlockPos testFire;

	private GhostTownSelfTest() {
	}

	/** Every render frame, from MinecraftMixin. */
	public static void tick(Minecraft minecraft) {
		if (!ON || busy) return;
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (minecraft.player == null || server == null) return;
		if (wait-- > 0) return;
		ServerPlayer sp = server.getPlayerList().getPlayer(minecraft.player.getUUID());
		if (sp == null) return;
		switch (step) {
			case 0 -> {
				wait = 200;
				step = 1;
				var recipe = server.getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,
					net.minecraft.resources.Identifier.fromNamespaceAndPath("fortcraft", "unusual_flint_and_steel")));
				net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(minecraft.player.connection.enabledFeatures(), true, minecraft.level.registryAccess());
				boolean inTab = false, inSearch = false;
				for (var tab : net.minecraft.world.item.CreativeModeTabs.allTabs()) {
					for (ItemStack s : tab.getDisplayItems()) if (s.is(MvmBlocks.UNUSUAL_FLINT_AND_STEEL)) inTab = true;
				}
				for (ItemStack s : net.minecraft.world.item.CreativeModeTabs.searchTab().getSearchTabDisplayItems()) {
					if (s.is(MvmBlocks.UNUSUAL_FLINT_AND_STEEL)) inSearch = true;
				}
				log("recipe loaded: {}; in a creative tab: {}; in creative search: {}", recipe.isPresent(), inTab, inSearch);
			}
			case 1 -> run(server, () -> {
				ServerLevel level = sp.level();
				BlockPos base = sp.blockPosition().offset(3, 0, 0);
				for (int x = -1; x <= 2; x++) {
					for (int y = -1; y <= 3; y++) {
						boolean edge = x < 0 || x == 2 || y < 0 || y == 3;
						level.setBlock(base.east(x).above(y), edge ? Blocks.IRON_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
					}
				}
				ItemStack flint = new ItemStack(MvmBlocks.UNUSUAL_FLINT_AND_STEEL);
				sp.setItemInHand(InteractionHand.MAIN_HAND, flint);
				BlockPos floor = base.below();
				var result = flint.useOn(new UseOnContext(sp, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false)));
				portal = base;
				log("lit frame at {}: result {}, block now {}, damage {}", base, result,
					level.getBlockState(base).getBlock(), flint.getDamageValue());
				step = 2;
			});
			case 2 -> {
				if (GhostTown.ready()) {
					step = 3;
				} else {
					wait = 20;
				}
			}
			case 3 -> run(server, () -> {
				TeleportTransition t = GhostTown.destination(sp.level(), sp, portal);
				log("destination into town: {}", t == null ? "none" : t.newLevel().dimension().identifier() + " " + t.position());
				if (t != null) sp.teleport(t);
				wait = 300;
				step = 4;
			});
			case 4 -> run(server, () -> {
				if (autoSince == 0) {
					sp.level().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
					autoSince = System.currentTimeMillis();
					log("waiting 33 s in town without pressing Ready: {}", GhostTownWaves.state());
				}
				if (!autoDone) {
					if (System.currentTimeMillis() - autoSince < 33_000) {
						wait = 200;
						return;
					}
					autoDone = true;
					log("after 33 s in town: {}", GhostTownWaves.state());
					GhostTownWaves.killAllForTest(sp.level());
					wait = 400;
					return;
				}
				ServerLevel level = sp.level();
				BlockPos feet = sp.blockPosition();
				int solid = 0, total = 0;
				for (BlockPos p : BlockPos.betweenClosed(feet.offset(-12, -6, -12), feet.offset(12, 6, 12))) {
					total++;
					if (!level.getBlockState(p).isAir()) solid++;
				}
				log("in {} at {}: standing in {}, on {}, {} of {} blocks around are solid; client dimension {}",
					level.dimension().identifier(), sp.position(), level.getBlockState(feet).getBlock(),
					level.getBlockState(feet.below()).getBlock(), solid, total,
					Minecraft.getInstance().level == null ? "?" : Minecraft.getInstance().level.dimension().identifier());
				var client = Minecraft.getInstance().level;
				log("town check: table {}, shelf {}, client sky colour {}, fog colour {}, block light at feet {}",
					level.getBlockState(CoalTownMap.TABLE).getBlock(),
					level.getBlockState(new BlockPos(CoalTownMap.SHELF_X, CoalTownMap.TABLE.getY(), CoalTownMap.TABLE.getZ())).getBlock(),
					client == null ? "?" : client.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_COLOR, sp.position()),
					client == null ? "?" : client.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.FOG_COLOR, sp.position()),
					level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, feet));
				if (!wavesDone) {
					sp.teleportTo(level, 533.5, 63, 50.5, java.util.Set.of(), 0, 0, true);  // where Alex's TF2 respawn put him
					GhostTown.respawnInTown(sp);
					log("respawn in town: now in {} at {}", sp.level().dimension().identifier(), sp.position());
				}
				if (GhostTown.isCoalTown() && !wavesDone) {
					log("waves before: {}; ready button {}; hatch {}; robot spawn {}", GhostTownWaves.state(),
						level.getBlockState(CoalTownMap.READY_BUTTON).getBlock(), CoalTownMap.hatch(level), CoalTownMap.robotSpawn(level));
					StringBuilder col = new StringBuilder();
					for (int y = 58; y <= 66; y++) col.append(y).append('=').append(level.getBlockState(new BlockPos(CoalTownMap.HATCH_X, y, CoalTownMap.HATCH_Z)).getBlock().getDescriptionId()).append(' ');
					log("hatch column: {}", col);
					log("route arrival->hatch {} steps, robots->arrival {} steps",
						GhostTownWaves.findPath(level, CoalTownMap.ARRIVAL, CoalTownMap.hatch(level)).size(),
						GhostTownWaves.findPath(level, CoalTownMap.robotSpawn(level), CoalTownMap.ARRIVAL).size());
					BlockPos fire = feet.east();
					level.setBlock(fire, net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState(), 3);
					boolean putOut = sp.gameMode.destroyBlock(fire);
					log("fire: placed on {}, punched out: {}, now {}", level.getBlockState(fire.below()).getBlock(), putOut, level.getBlockState(fire).getBlock());
					level.setBlock(fire, net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState(), 3);
					testFire = fire;
					level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
					GhostTownWaves.pressReadyForTest(level);
					log("waves after Ready: {}; carrier at {}", GhostTownWaves.state(), GhostTownWaves.carrierPos(level));
					wait = 2400;
					step = 40;
					return;
				}
				BlockPos floor = feet.below();
				var before = level.getBlockState(floor).getBlock();
				boolean player = sp.gameMode.destroyBlock(floor);
				boolean destroy = level.destroyBlock(floor, true, sp);
				boolean remove = level.removeBlock(floor, false);
				level.explode(null, feet.getX() + 0.5, feet.getY(), feet.getZ() + 3.5, 4.0F, net.minecraft.world.level.Level.ExplosionInteraction.TNT);
				int holes = 0;
				for (BlockPos p : BlockPos.betweenClosed(feet.offset(-12, -6, -12), feet.offset(12, 6, 12))) {
					if (!level.getBlockState(p).isAir()) holes++;
				}
				log("protection: floor {} -> player break {}, destroyBlock {}, removeBlock {}, floor now {}; solid around after a TNT-sized blast {} (was {})",
					before, player, destroy, remove, level.getBlockState(floor).getBlock(), holes, solid);
				TeleportTransition back = GhostTown.destination(level, sp, feet);
				log("destination home: {}", back == null ? "none" : back.newLevel().dimension().identifier() + " " + back.position());
				if (back != null) sp.teleport(back);
				wait = 200;
				step = 5;
			});
			case 40 -> run(server, () -> {
				ServerLevel level = sp.level();
				log("waves after ~10 s: {}; carrier at {}, hatch {}", GhostTownWaves.state(), GhostTownWaves.carrierPos(level), CoalTownMap.hatch(level));
				int fires = 0;
				for (BlockPos q : BlockPos.betweenClosed(testFire.offset(-6, -3, -6), testFire.offset(6, 3, 6))) {
					if (level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.FIRE)) fires++;
				}
				log("fire after ~10 s: {} fire blocks within 6 of it, floor under it {}", fires, level.getBlockState(testFire.below()).getBlock());
				if (!bombChecked) {
					log("robots along the lanes: {}", GhostTownWaves.robotSpreadForTest(level));
					GhostTownWaves.killCarrierForTest(level);
					bombChecked = true;
					wait = 1200;
					return;  // back here after the bomb has dropped
				}
				log("after killing the carrier: {}", GhostTownWaves.bombForTest(level));
				GhostTownWaves.killAllForTest(level);
				log("waves right after killing every robot: {}", GhostTownWaves.state());
				GhostTownWaves.pressReadyForTest(level);
				GhostTownWaves.carrierToHatchForTest(level);
				wait = 2400;  // the bomb takes 3 s or more to deploy
				step = 41;
			});
			case 41 -> run(server, () -> {
				log("waves after the bomb was carried onto the hatch: {}", GhostTownWaves.state());
				wavesDone = true;
				step = 4;
			});
			case 5 -> {
				log("back in {} at {}", minecraft.level.dimension().identifier(), minecraft.player.position());
				step = 6;
				minecraft.stop();
			}
			case 99 -> {
				step = 100;
				minecraft.stop();
			}
			default -> {
			}
		}
	}

	private static void run(MinecraftServer server, Runnable task) {
		busy = true;
		server.execute(() -> {
			try {
				task.run();
			} catch (RuntimeException e) {
				FortCraft.LOG.error("FortCraft selftest: failed at step {}", step, e);
				step = 99;
			} finally {
				busy = false;
			}
		});
	}

	private static void log(String format, Object... args) {
		FortCraft.LOG.info("FortCraft selftest: " + format, args);
	}
}
