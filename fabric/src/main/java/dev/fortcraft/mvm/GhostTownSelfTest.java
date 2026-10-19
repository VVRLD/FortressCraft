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
				TeleportTransition back = GhostTown.destination(level, sp, feet);
				log("destination home: {}", back == null ? "none" : back.newLevel().dimension().identifier() + " " + back.position());
				if (back != null) sp.teleport(back);
				wait = 200;
				step = 5;
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
