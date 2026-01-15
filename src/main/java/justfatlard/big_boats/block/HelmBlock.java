package justfatlard.big_boats.block;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import justfatlard.big_boats.BigBoats;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * The helm block serves as the anchor point for ship construction.
 * Players must place this block on their ship structure and use
 * a Christening Bottle on it to convert the structure into a ship entity.
 */
public class HelmBlock extends HorizontalFacingBlock implements PolymerTexturedBlock {
	public static final MapCodec<HelmBlock> CODEC = createCodec(HelmBlock::new);
	public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

	// Cached polymer block states for each facing direction
	private BlockState polymerStateNorth;
	private BlockState polymerStateSouth;
	private BlockState polymerStateEast;
	private BlockState polymerStateWest;

	public HelmBlock(Settings settings) {
		super(settings);
		setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
	}

	/**
	 * Called during initialization to request polymer block states for our custom model.
	 * Must be called after mod initialization but before the game uses these blocks.
	 */
	public void registerPolymerModels() {
		// Request polymer block states for each rotation (x rotation, y rotation)
		polymerStateNorth = PolymerBlockResourceUtils.requestBlock(
			BlockModelType.FULL_BLOCK,
			PolymerBlockModel.of(BigBoats.id("block/helm"), 0, 0)
		);
		polymerStateSouth = PolymerBlockResourceUtils.requestBlock(
			BlockModelType.FULL_BLOCK,
			PolymerBlockModel.of(BigBoats.id("block/helm"), 0, 180)
		);
		polymerStateEast = PolymerBlockResourceUtils.requestBlock(
			BlockModelType.FULL_BLOCK,
			PolymerBlockModel.of(BigBoats.id("block/helm"), 0, 90)
		);
		polymerStateWest = PolymerBlockResourceUtils.requestBlock(
			BlockModelType.FULL_BLOCK,
			PolymerBlockModel.of(BigBoats.id("block/helm"), 0, 270)
		);
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	@Override
	protected BlockState rotate(BlockState state, BlockRotation rotation) {
		return state.with(FACING, rotation.rotate(state.get(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, BlockMirror mirror) {
		return state.rotate(mirror.getRotation(state.get(FACING)));
	}

	@Override
	protected MapCodec<? extends HorizontalFacingBlock> getCodec() {
		return CODEC;
	}

	/**
	 * Returns a full cube shape for culling purposes.
	 * This ensures adjacent blocks cull their faces touching the helm.
	 */
	@Override
	protected VoxelShape getCullingShape(BlockState state) {
		return VoxelShapes.fullCube();
	}

	/**
	 * Makes helm's faces invisible when touching any solid block.
	 */
	@Override
	protected boolean isSideInvisible(BlockState state, BlockState stateFrom, Direction direction) {
		// Hide helm's face when adjacent to any solid block
		if (!stateFrom.isAir() && !stateFrom.isLiquid()) {
			return true;
		}
		return super.isSideInvisible(state, stateFrom, direction);
	}

	@Override
	public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
		Direction facing = state.get(FACING);

		// Return the pre-registered polymer state for this facing
		BlockState polymerState = switch (facing) {
			case NORTH -> polymerStateNorth;
			case SOUTH -> polymerStateSouth;
			case EAST -> polymerStateEast;
			case WEST -> polymerStateWest;
			default -> polymerStateNorth;
		};

		// Fallback to dark oak stairs if polymer model request failed
		if (polymerState == null) {
			return Blocks.DARK_OAK_STAIRS.getDefaultState()
				.with(Properties.HORIZONTAL_FACING, facing);
		}

		return polymerState;
	}

	/**
	 * Custom block item for the helm that uses Polymer.
	 */
	public static class HelmBlockItem extends BlockItem implements PolymerItem {
		private final Identifier modelId;

		public HelmBlockItem(Block block, Settings settings) {
			super(block, settings);
			this.modelId = BigBoats.id("helm");
		}

		@Override
		public Item getPolymerItem(ItemStack itemStack, PacketContext context) {
			return Items.DARK_OAK_STAIRS;
		}

		@Override
		public Identifier getPolymerItemModel(ItemStack itemStack, PacketContext context) {
			return this.modelId;
		}
	}
}
