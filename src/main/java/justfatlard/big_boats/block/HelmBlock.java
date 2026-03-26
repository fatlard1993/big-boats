package justfatlard.big_boats.block;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import justfatlard.big_boats.BigBoats;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * The helm block serves as the anchor point for ship construction.
 * Players place this block on their ship structure. Throwing a Christening
 * Bottle at any part of the ship converts the connected structure into a ship entity.
 */
public class HelmBlock extends HorizontalFacingBlock implements PolymerTexturedBlock {
	public static final MapCodec<HelmBlock> CODEC = createCodec(HelmBlock::new);
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
		// Use TRANSPARENT_BLOCK to prevent adjacent face culling (helm has gaps)
		polymerStateNorth = PolymerBlockResourceUtils.requestBlock(
			BlockModelType.TRANSPARENT_BLOCK,
			PolymerBlockModel.of(BigBoats.id("block/helm"), 0, 0)
		);
		polymerStateSouth = PolymerBlockResourceUtils.requestBlock(
			BlockModelType.TRANSPARENT_BLOCK,
			PolymerBlockModel.of(BigBoats.id("block/helm"), 0, 180)
		);
		polymerStateEast = PolymerBlockResourceUtils.requestBlock(
			BlockModelType.TRANSPARENT_BLOCK,
			PolymerBlockModel.of(BigBoats.id("block/helm"), 0, 90)
		);
		polymerStateWest = PolymerBlockResourceUtils.requestBlock(
			BlockModelType.TRANSPARENT_BLOCK,
			PolymerBlockModel.of(BigBoats.id("block/helm"), 0, 270)
		);

	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer) {
			// Find docked ship whose helm position matches this block
			var nearbyShips = world.getEntitiesByClass(
				MultiBlockShipEntity.class,
				new net.minecraft.util.math.Box(pos).expand(ShipConfig.DOCKED_HELM_SEARCH_RANGE),
				MultiBlockShipEntity::isDocked
			);

			for (var ship : nearbyShips) {
				// Verify this ship's helm is at the clicked block position
				BlockPos helmPos = ship.getHelmBlockPos();
				if (helmPos != null && helmPos.equals(pos)) {
					ship.tryMount(serverPlayer);
					return ActionResult.SUCCESS;
				}
			}
		}
		return ActionResult.PASS;
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

	private static final VoxelShape OUTLINE_SHAPE = Block.createCuboidShape(2, 0, 2, 14, 16, 14);

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
		return OUTLINE_SHAPE;
	}

	/**
	 * Returns an empty shape for culling purposes.
	 * The helm is a decorative ship wheel with many gaps, so adjacent blocks
	 * should NOT cull their faces - otherwise you'd see through to missing faces.
	 */
	@Override
	protected VoxelShape getCullingShape(BlockState state) {
		return VoxelShapes.empty();
	}

	/**
	 * Only hide helm's faces when touching another helm block (same type optimization).
	 */
	@Override
	protected boolean isSideInvisible(BlockState state, BlockState stateFrom, Direction direction) {
		// Only hide faces when touching another helm block
		if (stateFrom.isOf(this)) {
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
