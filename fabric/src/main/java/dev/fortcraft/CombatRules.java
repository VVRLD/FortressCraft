package dev.fortcraft;

/** Pure rules shared by gameplay and the standalone numeric checks. */
public final class CombatRules {
	/** The player's sentry fired it (TF2 counts its mob kills). */
	public static final int SENTRY = 32;
	public static final int BLEED_SHIFT = 8;

	/** Seconds of bleeding the weapon causes (Boston Basher...), 0 for none. */
	public static int bleedSeconds(int flags) {
		return (flags >>> BLEED_SHIFT) & 0xFF;
	}

	public static final int MELEE = 1, CRIT = 2, MINI = 4, HEADSHOT = 8, KNIFE = 16;
	public static final int GENERIC = 0, SHOVEL = 1, PICKAXE = 2, AXE = 3, BLADE = 4;
	private CombatRules() { }

	public static int miningHits(float hardness, int tool, boolean shovelBlock, boolean pickaxeBlock, boolean axeBlock) {
		int base = hardness <= 1.0f ? 1 : Math.min(8, 1 + (int) Math.ceil(hardness));
		if (tool == SHOVEL && shovelBlock) return 1;
		if (tool == PICKAXE && pickaxeBlock || tool == AXE && axeBlock) {
			return Math.max(2, (base + 1) / 2);
		}
		if (tool == BLADE && (shovelBlock || pickaxeBlock || axeBlock)) return Math.min(16, Math.max(2, base * 2));
		if (tool != GENERIC && (pickaxeBlock || axeBlock)) return Math.min(16, base + 2);
		return base;
	}

	public static float multiplier(int flags, boolean headshot) {
		return (flags & CRIT) != 0 || headshot ? 3.0f : (flags & MINI) != 0 ? 1.35f : 1.0f;
	}

	/**
	 * TF2's distance modifier for bullets and pellets: 150% point blank, 100% at 512 units (about
	 * 11 blocks), 50% from 1024 units (21 blocks). Crits ignore distance; mini-crits keep the
	 * close-range bonus but never fall below 100%. Melee has none.
	 */
	public static float rangeModifier(double blocks, int flags, boolean headshot) {
		if ((flags & MELEE) != 0 || (flags & CRIT) != 0 || headshot) {
			return 1.0f;
		}
		double units = blocks * 48.0;
		float mod = (float) Math.max(0.5, Math.min(1.5, 1.5 - units / 1024.0));
		return (flags & MINI) != 0 ? Math.max(1.0f, mod) : mod;
	}

	/**
	 * Minecraft damage a backstab does: kills any normal mob, but a boss (dragon, wither, warden,
	 * elder guardian) takes a fifth of its health per stab instead of dying to one.
	 */
	public static float backstabDamage(float normal, float health, float maxHealth, boolean boss) {
		return boss ? Math.max(normal, maxHealth * 0.2f) : Math.max(normal, health * 6.0f);
	}

	public static boolean backstab(double positionDot, double aimDot, double facingDot) {
		return positionDot > 0 && aimDot > 0.5 && facingDot > -0.3;
	}
}
