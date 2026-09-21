package justfatlard.big_boats.block;

import justfatlard.big_boats.ship.MultiBlockShipEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import java.util.List;
import justfatlard.big_boats.BigBoats;
import justfatlard.big_boats.ship.ShipConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
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
public class HelmBlock extends HorizontalDirectionalBlock implements EntityBlock {
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

	/**
	 * Taking the helm out disbands the ship it steered.
	 *
	 * <p>Handled at removal rather than on a break event so that it holds however the block goes -
	 * mined, exploded, washed away. The ship is only found while DOCKED, and undocking flips that
	 * state before it lifts its own blocks, so the mod pulling its helm up to set sail never reads
	 * as the helm being destroyed.
	 */
	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel world, BlockPos pos,
			boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, world, pos, movedByPiston);

		// A piston is moving it, not breaking it: the flag exists to tell those apart, and
		// treating a push as a destruction let any redstone contraption beside a moored ship
		// delete the ship, its name and its rating while leaving the hull standing.
		if (movedByPiston) return;

		for (MultiBlockShipEntity ship : world.getEntities(
				net.minecraft.world.level.entity.EntityTypeTest.forClass(MultiBlockShipEntity.class),
				new AABB(pos).inflate(ShipConfig.DOCKED_HELM_SEARCH_RANGE),
				MultiBlockShipEntity::isDocked)) {
			BlockPos helmPos = ship.getHelmBlockPos();
			if (helmPos != null && helmPos.equals(pos)) ship.discard();
		}
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new HelmBlockEntity(pos, state);
	}

	/**
	 * Carry the helm's Tonnage rating from the item onto the block.
	 *
	 * <p>This is the one moment the two exist together. After it the stack is gone, and a block
	 * that did not take a note here has no way to find out later what it was rated for.
	 */
	@Override
	public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity placer,
			ItemStack stack) {
		super.setPlacedBy(world, pos, state, placer, stack);

		if (world.getBlockEntity(pos) instanceof HelmBlockEntity helm) {
			helm.setTonnage(tonnageOf(stack, world));
		}
	}

	/**
	 * Give the rating back when the helm comes up.
	 *
	 * <p>Otherwise enchanting a helm would be a one-way trade: place it once and the Tonnage is
	 * spent, because the block drops the plain item its loot table names and the enchantment is
	 * simply gone. Breaking your own helm to move it is an ordinary thing to do.
	 */
	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
		List<ItemStack> drops = super.getDrops(state, params);

		if (!(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof HelmBlockEntity helm)
				|| helm.getTonnage() <= 0) {
			return drops;
		}

		Holder<Enchantment> tonnage = tonnageHolder(params.getLevel());
		if (tonnage == null) return drops;

		for (ItemStack drop : drops) {
			if (drop.is(BigBoats.HELM_ITEM)) drop.enchant(tonnage, helm.getTonnage());
		}
		return drops;
	}

	/** The Tonnage level on a helm stack, or 0 when it carries none. */
	public static int tonnageOf(ItemStack stack, Level world) {
		Holder<Enchantment> tonnage = tonnageHolder(world);
		if (tonnage == null) return 0;

		return stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
			.getLevel(tonnage);
	}

	private static Holder<Enchantment> tonnageHolder(Level world) {
		return world.registryAccess()
			.lookupOrThrow(Registries.ENCHANTMENT)
			.get(BigBoats.TONNAGE)
			.orElse(null);
	}

	/** How large a ship the helm standing here can command. */
	public static int capacityAt(Level world, BlockPos pos) {
		return world.getBlockEntity(pos) instanceof HelmBlockEntity helm
			? helm.capacity()
			: ShipConfig.capacityForTonnage(0);
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
