package dev.fortcraft;

import com.mojang.blaze3d.platform.InputConstants;
import dev.fortcraft.link.FortLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * TF2's own menus, used from Minecraft: class select (,), team select (.), loadout and items (M),
 * scoreboard (hold Tab) and TF2's main menu (P). Esc stays Minecraft's pause menu.
 *
 * TF2 draws its menus into the overlay. While one that needs the mouse is open (TF2 reports it),
 * Minecraft opens an invisible screen of its own: that frees the mouse cursor, and every mouse
 * move, click, scroll and key press on it goes to TF2's menu instead. When TF2's menu closes, the
 * invisible screen closes too. Esc on it closes TF2's menu.
 *
 * Key clashes with Minecraft: P is Minecraft's "Social Interactions" key; while TF2 is linked it
 * opens TF2's menu instead (MinecraftMixin drops Minecraft's own use). Tab is Minecraft's player
 * list, which only shows in multiplayer anyway; both show then. , . M are unbound in Minecraft.
 */
public final class Tf2Menus {
	// Protocol UiCommands.
	private static final int CLASS_MENU = 20, TEAM_MENU = 21, LOADOUT = 22, SCORES_DOWN = 23, SCORES_UP = 24,
		MAIN_MENU = 25, CLOSE_ALL = 26;

	private static final long OPEN_GRACE_NANOS = 1_500_000_000L;   // TF2 may take a moment to show a menu
	private static final long CLOSE_DELAY_NANOS = 300_000_000L;

	private static boolean commaWas, periodWas, mWas, pWas, scoresDown;
	private static long closedSince;

	private Tf2Menus() {
	}

	/** Once per Minecraft frame while TF2 is linked. */
	public static void tick(Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		boolean free = screen == null;

		// Menu keys (only while playing; on TF2's menu screen the keys go to TF2 itself).
		if (edge(InputConstants.isKeyDown(InputConstants.KEY_COMMA), 0) && free) {
			open(minecraft, CLASS_MENU, ", -> TF2 class menu");
		}
		if (edge(InputConstants.isKeyDown(InputConstants.KEY_PERIOD), 1) && free) {
			open(minecraft, TEAM_MENU, ". -> TF2 team menu");
		}
		if (edge(InputConstants.isKeyDown(InputConstants.KEY_M), 2) && free) {
			open(minecraft, LOADOUT, "M -> TF2 loadout");
		}
		if (edge(InputConstants.isKeyDown(InputConstants.KEY_P), 3) && free) {
			open(minecraft, MAIN_MENU, "P -> TF2 main menu");
		}

		// Scoreboard: shown while Minecraft's player-list key (Tab) is held.
		boolean scores = free && minecraft.options.keyPlayerList.isDown();
		if (scores != scoresDown) {
			scoresDown = scores;
			FortLink.sendUiCommand(scores ? SCORES_DOWN : SCORES_UP);
		}

		// Follow TF2: open the passthrough screen when a TF2 menu is open, close it after.
		FortLink.Tf2Camera cam = FortLink.readCamera();
		if (cam == null) {
			return;
		}
		if (cam.uiOpen() && free) {
			minecraft.gui.setScreen(new PassthroughScreen());
			FortCraft.LOG.info("FortCraft: TF2 menu open; mouse and keys go to TF2");
		} else if (screen instanceof PassthroughScreen s) {
			long now = System.nanoTime();
			if (cam.uiOpen() || now - s.openedAt < OPEN_GRACE_NANOS) {
				closedSince = 0;
			} else if (closedSince == 0) {
				closedSince = now;
			} else if (now - closedSince > CLOSE_DELAY_NANOS) {
				closedSince = 0;
				s.closedByTf2 = true;
				minecraft.gui.setScreen(null);
				FortCraft.LOG.info("FortCraft: TF2 menu closed; back to playing");
			}
		}
	}

	private static final boolean[] wasDown = new boolean[4];

	private static boolean edge(boolean down, int key) {
		boolean pressed = down && !wasDown[key];
		wasDown[key] = down;
		return pressed;
	}

	private static void open(Minecraft minecraft, int command, String log) {
		FortLink.sendUiCommand(command);
		FortCraft.LOG.info("FortCraft: {}", log);
		// Free the mouse straight away; the screen closes again if TF2 shows nothing.
		if (minecraft.gui.screen() == null) {
			minecraft.gui.setScreen(new PassthroughScreen());
		}
	}

	/**
	 * Minecraft's key handling, each tick: Minecraft's own use of P (Social Interactions) is
	 * dropped while TF2 is linked, since P opens TF2's menu.
	 */
	public static void beforeKeybinds(Minecraft minecraft) {
		while (minecraft.options.keySocialInteractions.consumeClick()) {
			// TF2's main menu instead (see tick)
		}
	}

	/** Invisible screen that frees the mouse and hands all input to TF2's open menu. */
	public static final class PassthroughScreen extends Screen {
		final long openedAt = System.nanoTime();
		boolean closedByTf2;
		private float lastX = -1, lastY = -1;
		private double wheel;

		PassthroughScreen() {
			super(Component.literal("TF2"));
		}

		@Override
		public boolean isPauseScreen() {
			return false;  // TF2 keeps running; so does Minecraft
		}

		@Override
		public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			// Nothing: no blur or darkening, TF2's menu (in the overlay) is what you see.
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			float[] p = cursor();
			if (p[0] != lastX || p[1] != lastY) {
				lastX = p[0];
				lastY = p[1];
				FortLink.sendUiEvent(FortLink.UI_MOUSE_MOVE, 0, p[0], p[1]);
			}
		}

		/** Mouse position, 0..1 across the window (TF2's frame fills the whole window). */
		private float[] cursor() {
			Minecraft mc = Minecraft.getInstance();
			var window = mc.getWindow();
			float x = (float) (mc.mouseHandler.xpos() / Math.max(1, window.getScreenWidth()));
			float y = (float) (mc.mouseHandler.ypos() / Math.max(1, window.getScreenHeight()));
			return new float[] {Math.clamp(x, 0f, 1f), Math.clamp(y, 0f, 1f)};
		}

		private static int tf2Button(int button) {
			if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
				return 1;
			}
			return button == InputConstants.MOUSE_BUTTON_MIDDLE ? 2 : 0;
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			float[] p = cursor();
			FortLink.sendUiEvent(FortLink.UI_MOUSE_DOWN, tf2Button(event.button()), p[0], p[1]);
			return true;
		}

		@Override
		public boolean mouseReleased(MouseButtonEvent event) {
			float[] p = cursor();
			FortLink.sendUiEvent(FortLink.UI_MOUSE_UP, tf2Button(event.button()), p[0], p[1]);
			return true;
		}

		@Override
		public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
			wheel += scrollY;
			while (wheel >= 1) {
				wheel -= 1;
				FortLink.sendUiEvent(FortLink.UI_WHEEL, 1, 0, 0);
			}
			while (wheel <= -1) {
				wheel += 1;
				FortLink.sendUiEvent(FortLink.UI_WHEEL, -1, 0, 0);
			}
			return true;
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (event.isEscape()) {
				onClose();
				return true;
			}
			if (event.input() == InputConstants.KEY_P) {
				FortLink.sendUiCommand(MAIN_MENU);  // P again: TF2 closes (or opens) its main menu
				return true;
			}
			FortLink.sendUiEvent(FortLink.UI_KEY_DOWN, event.input(), 0, 0);
			return true;
		}

		@Override
		public boolean keyReleased(KeyEvent event) {
			FortLink.sendUiEvent(FortLink.UI_KEY_UP, event.input(), 0, 0);
			return true;
		}

		@Override
		public boolean charTyped(CharacterEvent event) {
			FortLink.sendUiEvent(FortLink.UI_CHAR, event.codepoint(), 0, 0);
			return true;
		}

		@Override
		public void onClose() {
			if (!closedByTf2) {
				FortLink.sendUiCommand(CLOSE_ALL);  // Esc: close TF2's menu too
				FortCraft.LOG.info("FortCraft: Esc -> close TF2's menus");
			}
			super.onClose();
		}
	}
}
