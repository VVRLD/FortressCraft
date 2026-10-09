package dev.fortcraft;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * FortCraft's TF2 keys, listed in Minecraft's Options > Controls > Key Binds under "FortCraft
 * (TF2)", so they can be rebound there like any Minecraft key (saved in options.txt). The
 * defaults are the keys FortCraft always used. OptionsMixin adds them to Minecraft's list
 * before options.txt is read.
 */
public final class Tf2Keys {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("fortcraft", "tf2"));

	public static final KeyMapping RELOAD = key("reload", InputConstants.KEY_R);
	public static final KeyMapping TAUNT = key("taunt", InputConstants.KEY_G);
	public static final KeyMapping CLASS_MENU = key("class_menu", InputConstants.KEY_COMMA);
	public static final KeyMapping TEAM_MENU = key("team_menu", InputConstants.KEY_PERIOD);
	public static final KeyMapping LOADOUT = key("loadout", InputConstants.KEY_M);
	/** TF2's backpack, opened at the page with the Minecraft items. Same key as Minecraft's inventory (E) by default. */
	public static final KeyMapping BACKPACK = key("backpack", InputConstants.KEY_E);
	public static final KeyMapping MAIN_MENU = key("main_menu", InputConstants.KEY_P);
	public static final KeyMapping CONSOLE = key("console", InputConstants.KEY_GRAVE);
	public static final KeyMapping VOICE_1 = key("voice_menu_1", InputConstants.KEY_Z);
	public static final KeyMapping VOICE_2 = key("voice_menu_2", InputConstants.KEY_X);
	public static final KeyMapping VOICE_3 = key("voice_menu_3", InputConstants.KEY_C);
	public static final KeyMapping VOICE_CANCEL = key("voice_cancel", InputConstants.KEY_0);

	private static final KeyMapping[] ALL = { RELOAD, TAUNT, CLASS_MENU, TEAM_MENU, LOADOUT, BACKPACK, MAIN_MENU, CONSOLE,
		VOICE_1, VOICE_2, VOICE_3, VOICE_CANCEL };

	private Tf2Keys() {
	}

	private static KeyMapping key(String name, int defaultKey) {
		return new KeyMapping("key.fortcraft." + name, defaultKey, CATEGORY);
	}

	/** Minecraft's key list with FortCraft's added (once). */
	public static KeyMapping[] addTo(KeyMapping[] minecraftKeys) {
		for (KeyMapping k : minecraftKeys) {
			if (k == RELOAD) {
				return minecraftKeys;
			}
		}
		KeyMapping[] all = Arrays.copyOf(minecraftKeys, minecraftKeys.length + ALL.length);
		System.arraycopy(ALL, 0, all, minecraftKeys.length, ALL.length);
		return all;
	}
}
