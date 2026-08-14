package justfatlard.big_boats.block;

import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The helm block is the anchor point for ship construction.
 * Players place this block on their ship structure. Throwing a Christening
 * Bottle at any part of the ship converts the connected structure into a ship entity.
 *
 * <p>Rendering (per-facing rotated model) is driven entirely by the vanilla-format
 * blockstate/model JSON synced to Pandorical clients via {@code registerModAssets};
 * see {@link justfatlard.big_boats.BigBoats#onInitialize}. Pandorical clients bake
 * the real custom block's model like any other resource pack entry.</p>
 */
public class HelmBlock extends HorizontalDirectionalBlock {
	private static final VoxelShape OUTLINE_SHAPE = Block.box(2, 0, 2, 14, 16, 14);

	public HelmBlock(Properties settings) {
		super(settings);
		this.registerDefaultState(this.getStateDefinition().any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
		if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer) {
			// Find docked ship whose helm position matches this block
			var nearbyShips = world.getEntities(
				net.minecraft.world.level.entity.EntityTypeTest.forClass(MultiBlockShipEntity.class),
				new AABB(pos).inflate(ShipConfig.DOCKED_HELM_SEARCH_RANGE),
				MultiBlockShipEntity::isDocked
			);

			for (var ship : nearbyShips) {
				// Verify this ship's helm is at the clicked block position
				BlockPos helmPos = ship.getHelmBlockPos();
				if (helmPos != null && helmPos.equals(pos)) {
					ship.tryMount(serverPlayer);
					return InteractionResult.SUCCESS;
				}
			}
		}
		return InteractionResult.PASS;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		Direction facing = ctx.getPlayer() != null ? ctx.getPlayer().getDirection().getOpposite() : Direction.NORTH;
		return defaultBlockState().setValue(FACING, facing);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return OUTLINE_SHAPE;
	}

	/**
	 * Returns an empty shape for culling purposes.
	 * The helm is a decorative ship wheel with many gaps, so adjacent blocks
	 * should NOT cull their faces; otherwise you'd see through to missing faces.
	 */
	@Override
	protected VoxelShape getOcclusionShape(BlockState state) {
		return Shapes.empty();
	}

	/**
	 * Only hide helm's faces when touching another helm block (same type optimization).
	 */
	@Override
	protected boolean skipRendering(BlockState state, BlockState adjacentState, Direction direction) {
		if (adjacentState.getBlock() == this) {
			return true;
		}
		return super.skipRendering(state, adjacentState, direction);
	}

	/**
	 * Plain {@link BlockItem} for the helm; client appearance is declared via Pandorical's
	 * content API in {@link justfatlard.big_boats.BigBoats#onInitialize}.
	 */
	public static class HelmBlockItem extends BlockItem {
		public HelmBlockItem(Block block, Properties settings) {
			super(block, settings);
		}
	}
}
