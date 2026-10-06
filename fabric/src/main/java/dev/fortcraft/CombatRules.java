package dev.fortcraft;

/** Pure rules shared by gameplay and the standalone numeric checks. */
public final class CombatRules {
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

	public static boolean backstab(double positionDot, double aimDot, double facingDot) {
		return positionDot > 0 && aimDot > 0.5 && facingDot > -0.3;
	}
}
