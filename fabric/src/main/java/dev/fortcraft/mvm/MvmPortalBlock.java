package dev.fortcraft.mvm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;

/**
 * The white portal in an iron frame. It behaves like a Nether portal (stand in it to travel), but
 * leads to the Ghost Town and back, keeps to iron frames, and never spawns zombified piglins.
 */
public final class MvmPortalBlock extends NetherPortalBlock {
	private static final DustParticleOptions WHITE = new DustParticleOptions(0xF4F4FF, 1.0F);

	public MvmPortalBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
	}

	/** Break when a neighbour in the portal's own plane is no longer portal or iron frame. */
	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
			Direction direction, BlockPos neighborPos, BlockState neighbor, RandomSource random) {
		Direction.Axis axis = state.getValue(AXIS);
		boolean inPlane = direction.getAxis() == axis || direction.getAxis() == Direction.Axis.Y;
		if (inPlane && !neighbor.is(this) && !neighbor.is(Blocks.IRON_BLOCK)) {
			return Blocks.AIR.defaultBlockState();
		}
		return state;
	}

	@Override
	public TeleportTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
		return GhostTown.destination(level, entity, pos);
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		for (int i = 0; i < 3; i++) {
			double x = pos.getX() + random.nextDouble();
			double y = pos.getY() + random.nextDouble();
			double z = pos.getZ() + random.nextDouble();
			level.addParticle(WHITE, x, y, z, 0, 0.02, 0);
		}
	}
}
