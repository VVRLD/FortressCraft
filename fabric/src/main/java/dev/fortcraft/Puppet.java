package dev.fortcraft;

import com.mojang.blaze3d.platform.InputConstants;
import dev.fortcraft.link.FortLink;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft's player is a puppet: every render frame, just before the frame is drawn, it is
 * moved to the position TF2 last sent. Every value received is written to logs/mc_received.log.
 */
public final class Puppet {
	private static final String WORLD_NAME = "FortCraft Test";
	private static final String NORMAL_WORLD_NAME = "FortCraft Normal";

	private static final int TARGET_FPS = 240;

	private static long frame;
	private static boolean fpsSet;
	private static boolean lastCursorFree;
	private static float eyeHeight = -1;

	/** TF2's eye height above the feet (blocks), or -1 if not known. CameraMixin puts Minecraft's camera there. */
	public static float tf2EyeHeight() {
		return Overlay.active() ? eyeHeight : -1;
	}
	private static boolean worldRequested;
	private static boolean inWorldLogged;
	private static boolean guestWasAlive;
	private static PrintWriter log;

	private Puppet() {
	}

	/** Called from MinecraftMixin right before GameRenderer.render(). */
	public static void beforeRender(Minecraft minecraft) {
		frame++;
		FortLink.heartbeat(frame);
		if (!fpsSet) {
			// Alex asked for both games at 240 fps; TF2 follows whatever Minecraft's limit is.
			fpsSet = true;
			minecraft.options.framerateLimit().set(TARGET_FPS);
			FortCraft.LOG.info("FortCraft: Minecraft frame rate limit set to {}", TARGET_FPS);
		}
		// While paced to TF2's frames (Overlay.pace), Minecraft's own frame limiter would add its
		// own uneven waits on top, so it is switched off; TF2 still gets the 240 fps target.
		int wantLimit = Overlay.pacing(minecraft) ? Options.UNLIMITED_FRAMERATE_CUTOFF : TARGET_FPS;
		if (minecraft.options.framerateLimit().get() != wantLimit) {
			minecraft.options.framerateLimit().set(wantLimit);
			FortCraft.LOG.info("FortCraft: Minecraft frame limiter {}", wantLimit == TARGET_FPS ? "back at " + TARGET_FPS : "off (paced to TF2)");
		}
		int fpsLimit = TARGET_FPS;
		boolean cursorFree = !minecraft.mouseHandler.isMouseGrabbed() || !minecraft.isWindowActive();
		if (cursorFree != lastCursorFree) {
			lastCursorFree = cursorFree;
			FortCraft.LOG.info("FortCraft: Minecraft {} the mouse", cursorFree ? "let go of" : "grabbed");
		}
		int winW = minecraft.getWindow().getWidth(), winH = Math.max(1, minecraft.getWindow().getHeight());
		boolean creative = minecraft.player != null && minecraft.player.isCreative();
		FortLink.writeDisplay(winW, winH, fpsLimit >= Options.UNLIMITED_FRAMERATE_CUTOFF ? 0 : fpsLimit, cursorFree, creative, GpuOverlay.failed(),
			minecraft.gameRenderer.mainCamera().getFov(), (float) winW / winH, WorldLight.at(minecraft));

		boolean guestAlive = FortLink.guestAlive();
		if (guestAlive != guestWasAlive) {
			guestWasAlive = guestAlive;
			FortCraft.LOG.info(guestAlive ? "FortCraft: TF2 connected (pid {})" : "FortCraft: TF2 gone (pid {})", FortLink.guestPid());
		}

		if (minecraft.level == null) {
			openTestWorld(minecraft);
			return;
		}
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		if (!inWorldLogged) {
			inWorldLogged = true;
			FortCraft.LOG.info("FortCraft: in world, waiting for TF2 positions");
		}
		// Forward keys and look to TF2. Minecraft's mouse still turns the camera, so Minecraft owns
		// the look direction for now and TF2 moves relative to it.
		int buttons = 0;
		if (minecraft.gui.screen() == null) {
			var o = minecraft.options;
			buttons |= o.keyUp.isDown() ? FortLink.IN_FORWARD : 0;
			buttons |= o.keyDown.isDown() ? FortLink.IN_BACK : 0;
			buttons |= o.keyLeft.isDown() ? FortLink.IN_LEFT : 0;
			buttons |= o.keyRight.isDown() ? FortLink.IN_RIGHT : 0;
			buttons |= o.keyJump.isDown() ? FortLink.IN_JUMP : 0;
			buttons |= o.keyShift.isDown() ? FortLink.IN_CROUCH : 0;
			buttons |= o.keyAttack.isDown() ? FortLink.IN_ATTACK : 0;
			buttons |= guestAlive && RightClick.tf2Attack2(minecraft) ? FortLink.IN_ATTACK2 : 0;
			buttons |= Tf2Keys.RELOAD.isDown() ? FortLink.IN_RELOAD : 0;
		}
		FortLink.writeInput(frame, buttons, player.getYRot(), player.getXRot(), WeaponSelection.selectedSlot(minecraft, guestAlive));
		BlockScanner.tick(minecraft.level, player.getX(), player.getY(), player.getZ());
		Overlay.pace(minecraft, guestAlive);
		Overlay.update(minecraft, guestAlive);
		Combat.linked = guestAlive;
		if (guestAlive) {
			Taunts.tick(minecraft);
			Tf2Menus.tick(minecraft);
			VoiceCommands.tick(minecraft);
			Combat.tick(minecraft, buttons);
			Backpack.tick(minecraft);
			Hand.tick(minecraft);
			Crafting.tick(minecraft);
			Fire.tick(minecraft);
			MobsVsBuildings.tick(minecraft);
			FarOcclusion.tick(minecraft);
		}

		if (!guestAlive) {
			return;
		}
		FortLink.PlayerState s = FortLink.readPlayer();
		if (s == null) {
			return;
		}

		// Minecraft moved its player itself since the last frame (a portal to the Nether or End, an
		// ender pearl, /tp): TF2 follows, and until TF2's player is there, Minecraft's stays put.
		Vec3 now = player.position();
		if (ownMove && minecraft.level != lastLevel) {
			ownMove = false;  // our own trip back to the overworld (returnToOverworld): TF2 is already there
			lastLevel = minecraft.level;
			lastSet = null;
		}
		if (lastSet != null && (minecraft.level != lastLevel || now.distanceToSqr(lastSet) > TELEPORT_BLOCKS * TELEPORT_BLOCKS)) {
			FortLink.writeMcTeleport((float) now.x, (float) now.y, (float) now.z);
			teleportTarget = now;
			teleportSince = System.nanoTime();
			FortCraft.LOG.info("FortCraft: Minecraft moved the player to {} {} {} ({}); TF2 follows",
				String.format("%.1f", now.x), String.format("%.1f", now.y), String.format("%.1f", now.z),
				minecraft.level != lastLevel ? "new dimension " + minecraft.level.dimension().identifier() : "teleport");
		}
		lastLevel = minecraft.level;
		if (teleportTarget != null) {
			boolean arrived = new Vec3(s.x(), s.y(), s.z()).distanceToSqr(teleportTarget) < 4.0;
			if (!arrived && System.nanoTime() - teleportSince < 3_000_000_000L) {
				lastSet = player.position();
				return;
			}
			if (!arrived) {
				FortCraft.LOG.warn("FortCraft: TF2 didn't follow the teleport within 3 s; using TF2's position again");
			}
			teleportTarget = null;
		}
		returnToOverworld(minecraft, s);

		// Put the player exactly there. Setting the "previous" position too means Minecraft's own
		// between-ticks smoothing draws this exact point instead of blending with an old one.
		player.getAbilities().flying = true;
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(s.x(), s.y(), s.z());
		eyeHeight = s.eyeHeight();
		player.xo = s.x();
		player.yo = s.y();
		player.zo = s.z();
		player.resetFallDistance();
		lastSet = new Vec3(s.x(), s.y(), s.z());

		// What will actually be drawn this frame.
		Vec3 drawn = player.getPosition(minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		double[] cam = Overlay.lastCameraPosition();  // where the previous frame's camera really was, and when
		writeLog(String.format("frame=%d sample=%d received=(%.4f,%.4f,%.4f) drawn=(%.4f,%.4f,%.4f) yaw=%.2f t=%d newOverlay=%d cam=(%.4f,%.4f,%.4f) camT=%d",
			frame, s.sample(), s.x(), s.y(), s.z(), drawn.x, drawn.y, drawn.z, s.yaw(), System.nanoTime() / 1000L,
			Overlay.newThisFrame() ? 1 : 0, cam != null ? cam[0] : drawn.x, cam != null ? cam[1] : drawn.y, cam != null ? cam[2] : drawn.z,
			Overlay.lastCameraNanos() / 1000L));
	}

	/**
	 * TF2 respawned (its player jumped to the spawn point) while Minecraft's player is in the
	 * Nether or the End: spawn points are in the overworld, so take Minecraft's player there.
	 */
	private static void returnToOverworld(Minecraft minecraft, FortLink.PlayerState s) {
		var server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level.dimension() == net.minecraft.world.level.Level.OVERWORLD || lastTf2 == null) {
			lastTf2 = new Vec3(s.x(), s.y(), s.z());
			return;
		}
		Vec3 tf2 = new Vec3(s.x(), s.y(), s.z());
		Vec3 spawn = Combat.spawnPoint();
		boolean jumped = tf2.distanceToSqr(lastTf2) > 8.0 * 8.0;
		lastTf2 = tf2;
		if (!jumped || spawn == null || tf2.distanceToSqr(spawn) > 3.0 * 3.0) {
			return;
		}
		java.util.UUID id = minecraft.player.getUUID();
		server.execute(() -> {
			var sp = server.getPlayerList().getPlayer(id);
			if (sp != null) {
				sp.teleportTo(server.overworld(), tf2.x, tf2.y, tf2.z, java.util.Set.of(), sp.getYRot(), sp.getXRot(), true);
			}
		});
		ownMove = true;  // the dimension change that follows is ours, not a Minecraft teleport
		FortCraft.LOG.info("FortCraft: TF2 respawned; taking Minecraft's player back to the overworld");
	}

	private static final double TELEPORT_BLOCKS = 2.0;
	private static Vec3 lastSet;
	private static Vec3 lastTf2;
	private static net.minecraft.world.level.Level lastLevel;
	private static boolean ownMove;
	private static Vec3 teleportTarget;
	private static long teleportSince;

	/**
	 * Open (or first create) the world to play in, once the title screen is up. Which world comes
	 * from FORTCRAFT_WORLD (set by tools\play_normal.bat or by hand): unset = the creative flat test
	 * world; "normal" = a creative world with normal terrain, caves and mobs ("FortCraft Normal");
	 * anything else = an existing world with that folder name (one of your own saves).
	 */
	private static void openTestWorld(Minecraft minecraft) {
		if (worldRequested || minecraft.gui.overlay() != null) {
			return;
		}
		if (!(minecraft.gui.screen() instanceof TitleScreen title)) {
			// Skip first-launch prompts in front of the title screen.
			if (minecraft.gui.screen() == null || minecraft.gui.screen().getClass().getName().contains("Onboarding")) {
				minecraft.gui.setScreen(new TitleScreen());
			}
			return;
		}
		worldRequested = true;
		String choice = System.getenv("FORTCRAFT_WORLD");
		if (choice != null && !choice.isBlank()) {
			choice = choice.trim();
			if (choice.equalsIgnoreCase("normal")) {
				openOrCreate(minecraft, title, NORMAL_WORLD_NAME, WorldPresets.NORMAL, Difficulty.NORMAL, new WorldOptions(new java.util.Random().nextLong(), true, false));
				return;
			}
			if (minecraft.getLevelSource().levelExists(choice)) {
				FortCraft.LOG.info("FortCraft: opening your world '{}'", choice);
				minecraft.createWorldOpenFlows().openWorld(choice, () -> minecraft.gui.setScreen(title));
				return;
			}
			FortCraft.LOG.warn("FortCraft: no world folder named '{}'; opening the flat test world", choice);
		}
		openOrCreate(minecraft, title, WORLD_NAME, WorldPresets.FLAT, Difficulty.PEACEFUL, new WorldOptions(0L, false, false));
	}

	private static void openOrCreate(Minecraft minecraft, TitleScreen title, String name,
		net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.presets.WorldPreset> preset, Difficulty difficulty, WorldOptions options) {
		if (minecraft.getLevelSource().levelExists(name)) {
			FortCraft.LOG.info("FortCraft: opening world '{}'", name);
			minecraft.createWorldOpenFlows().openWorld(name, () -> minecraft.gui.setScreen(title));
			return;
		}
		FortCraft.LOG.info("FortCraft: creating world '{}' ({})", name, preset.identifier());
		LevelSettings settings = new LevelSettings(
			name,
			GameType.CREATIVE,
			new LevelSettings.DifficultySettings(difficulty, false, false),
			true,
			WorldDataConfiguration.DEFAULT
		);
		minecraft.createWorldOpenFlows().createFreshLevel(
			name,
			settings,
			options,
			registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(preset).value().createWorldDimensions(),
			title
		);
	}

	private static void writeLog(String line) {
		if (log == null) {
			try {
				Path dir = Path.of(System.getProperty("fortcraft.logDir", "logs"));
				Files.createDirectories(dir);
				log = new PrintWriter(Files.newBufferedWriter(dir.resolve("mc_received.log")), true);
			} catch (IOException e) {
				FortCraft.LOG.error("FortCraft: can't open mc_received.log", e);
				return;
			}
		}
		log.println(line);
	}
}
