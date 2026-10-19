package dev.fortcraft.mvm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An upright rectangle of iron blocks around empty space, like a Nether portal's obsidian frame:
 * inside 2-21 wide and 3-21 tall, corners optional.
 */
public record PortalFrame(BlockPos bottomLeft, Direction.Axis axis, int width, int height) {
	public static final int MIN_W = 2, MIN_H = 3, MAX = 21;

	/** The frame around this empty spot, trying both upright orientations, or null. */
	public static PortalFrame find(LevelAccessor level, BlockPos inside) {
		PortalFrame frame = find(level, inside, Direction.Axis.X);
		return frame != null ? frame : find(level, inside, Direction.Axis.Z);
	}

	private static PortalFrame find(LevelAccessor level, BlockPos start, Direction.Axis axis) {
		if (!empty(level.getBlockState(start))) return null;
		Direction right = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
		Direction left = right.getOpposite();
		// Drop to the bottom, then slide to the left edge.
		BlockPos pos = start;
		for (int i = 0; i < MAX && empty(level.getBlockState(pos.below())); i++) pos = pos.below();
		if (!frame(level, pos.below())) return null;
		for (int i = 0; i < MAX && empty(level.getBlockState(pos.relative(left))); i++) pos = pos.relative(left);
		if (!frame(level, pos.relative(left))) return null;
		int width = 0;
		while (width <= MAX && empty(level.getBlockState(pos.relative(right, width)))) width++;
		if (width < MIN_W || width > MAX || !frame(level, pos.relative(right, width))) return null;
		int height = 0;
		while (height <= MAX && empty(level.getBlockState(pos.above(height)))) height++;
		if (height < MIN_H || height > MAX) return null;
		// Every inside block empty, every edge iron.
		for (int x = 0; x < width; x++) {
			if (!frame(level, pos.relative(right, x).below()) || !frame(level, pos.relative(right, x).above(height))) return null;
			for (int y = 0; y < height; y++) {
				if (!empty(level.getBlockState(pos.relative(right, x).above(y)))) return null;
			}
		}
		for (int y = 0; y < height; y++) {
			if (!frame(level, pos.relative(left).above(y)) || !frame(level, pos.relative(right, width).above(y))) return null;
		}
		return new PortalFrame(pos, axis, width, height);
	}

	/** Fill the inside with white portal blocks. */
	public void light(Level level) {
		Direction right = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
		BlockState portal = MvmBlocks.PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis);
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				level.setBlock(bottomLeft.relative(right, x).above(y), portal, 2 | 16);
			}
		}
	}

	/** Build an iron frame with a lit portal in it (the Ghost Town's way home). */
	public static void build(Level level, BlockPos bottomLeft, Direction.Axis axis, int width, int height) {
		Direction right = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
		for (int x = -1; x <= width; x++) {
			for (int y = -1; y <= height; y++) {
				BlockPos p = bottomLeft.relative(right, x).above(y);
				boolean edge = x < 0 || x == width || y < 0 || y == height;
				level.setBlock(p, edge ? Blocks.IRON_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2 | 16);
			}
		}
		new PortalFrame(bottomLeft, axis, width, height).light(level);
	}

	private static boolean empty(BlockState state) {
		return state.isAir() || state.is(Blocks.FIRE) || state.is(MvmBlocks.PORTAL);
	}

	private static boolean frame(LevelAccessor level, BlockPos pos) {
		return level.getBlockState(pos).is(Blocks.IRON_BLOCK);
	}
}
