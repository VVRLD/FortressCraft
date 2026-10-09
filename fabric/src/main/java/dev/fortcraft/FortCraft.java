package dev.fortcraft;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FortCraft implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("FortCraft");

	@Override
	public void onInitialize() {
		LOG.info("FortCraft plugin loaded");
		// Supply packs are registered from ItemsMixin, before Minecraft freezes its item registry.
		dev.fortcraft.link.FortLink.create();
	}
}
