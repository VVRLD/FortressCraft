package dev.fortcraft;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FortCraft implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("FortCraft");

	@Override
	public void onInitialize() {
		LOG.info("FortCraft plugin loaded");
		dev.fortcraft.link.FortLink.create();
	}
}
