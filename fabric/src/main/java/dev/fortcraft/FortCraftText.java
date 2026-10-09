package dev.fortcraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * FortCraft's English text (assets/fortcraft/lang/en_us.json), read straight from the mod's own
 * files. Minecraft only loads mod language files through Fabric API, which this mod doesn't use;
 * ClientLanguageMixin asks here first.
 */
public final class FortCraftText {
	private static Map<String, String> text;

	private FortCraftText() {
	}

	/** The text for a fortcraft key, or null for anything else. */
	public static String get(String key) {
		if (key == null || !key.contains("fortcraft")) {
			return null;
		}
		if (text == null) {
			load();
		}
		return text.get(key);
	}

	private static synchronized void load() {
		if (text != null) {
			return;
		}
		Map<String, String> map = new HashMap<>();
		try (InputStream in = FortCraftText.class.getResourceAsStream("/assets/fortcraft/lang/en_us.json")) {
			if (in != null) {
				JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				json.entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsString()));
			}
		} catch (Exception e) {
			FortCraft.LOG.warn("FortCraft: couldn't read its language file", e);
		}
		text = map;
	}
}
