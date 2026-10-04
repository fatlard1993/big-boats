package justfatlard.big_boats.ship.sea;

import justfatlard.big_boats.BigBoats;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipBlock;
import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One ship's blocks, standing in her stretch of {@link AtSea} while she sails, with a live block
 * entity for each that has one: one shared by everyone who opens it, so two players at one chest
 * see one chest. What changes in them is written back to the ship's blocks at the end of each tick,
 * so a save sees it, and everything is written back before she docks.
 */
public final class Hold {
	private static final Logger LOGGER = LoggerFactory.getLogger(Hold.class);

	/** What may be used at sea; also what a Pandorical client sends a use for. */
	public static final TagKey<Block> USABLE = TagKey.create(Registries.BLOCK,
		Identifier.fromNamespaceAndPath("pandorical", "usable_on_structures"));
	/** What keeps working at sea: a furnace burns, a hopper passes things on. */
	public static final TagKey<Block> TICKS = TagKey.create(Registries.BLOCK,
		Identifier.fromNamespaceAndPath(BigBoats.MOD_ID, "ticks_at_sea"));

	/** How far shape updates spread from a change up here: the other half of a door, and no further. */
	private static final int SHAPE_DEPTH = 2;

	private final MultiBlockShipEntity ship;
	private final ServerLevel level;
	private final int baseX;
	private final int baseZ;
	private final Map<RelativeBlockPos, BlockEntity> live = new HashMap<>();
	private final Set<RelativeBlockPos> dirty = new HashSet<>();
	private Map<RelativeBlockPos, Integer> index = Map.of();
	private List<ShipBlock> indexed = null;
	private boolean released;

	Hold(MultiBlockShipEntity ship, ServerLevel level, int baseX, int baseZ) {
		this.ship = ship;
		this.level = level;
		this.baseX = baseX;
		this.baseZ = baseZ;
		// Locked chests stay locked at sea: each lock on the chest's place up here, where Chest
		// Utils looks for it when the chest is opened. Every chest's place is set, locked or not, so
		// a lock a crash left up here from another ship holds nothing of hers.
		for (ShipBlock block : ship.getBlocks()) {
			if (!block.blockState().hasBlockEntity()) continue;
			justfatlard.big_boats.integration.ChestLockCarry.lay(level, virtual(block.relativePos()), block.lock().orElse(null));
		}
	}

	public ServerLevel level() {
		return level;
	}

	public MultiBlockShipEntity ship() {
		return ship;
	}

	public BlockPos virtual(RelativeBlockPos rel) {
		return new BlockPos(baseX + rel.x(), AtSea.HELM_Y + rel.y(), baseZ + rel.z());
	}

	RelativeBlockPos rel(BlockPos pos) {
		return new RelativeBlockPos(pos.getX() - baseX, pos.getY() - AtSea.HELM_Y, pos.getZ() - baseZ);
	}

	private int indexOf(RelativeBlockPos rel) {
		List<ShipBlock> blocks = ship.getBlocks();
		if (blocks != indexed) {
			Map<RelativeBlockPos, Integer> fresh = new HashMap<>(blocks.size() * 2);
			for (int i = 0; i < blocks.size(); i++) fresh.put(blocks.get(i).relativePos(), i);
			index = fresh;
			indexed = blocks;
		}
		Integer i = index.get(rel);
		return i == null ? -1 : i;
	}

	BlockState stateAt(BlockPos pos) {
		int i = indexOf(rel(pos));
		return i < 0 ? Blocks.AIR.defaultBlockState() : ship.getBlocks().get(i).blockState();
	}

	@Nullable BlockEntity blockEntityAt(BlockPos pos) {
		if (released) return null;
		RelativeBlockPos rel = rel(pos);
		BlockEntity existing = live.get(rel);
		if (existing != null) return existing;

		int i = indexOf(rel);
		if (i < 0) return null;
		ShipBlock block = ship.getBlocks().get(i);
		BlockState state = block.blockState();
		if (!state.hasBlockEntity()) return null;

		BlockPos at = virtual(rel);
		BlockEntity made = block.blockEntityData()
			.map(nbt -> BlockEntity.loadStatic(at, state, nbt, level.registryAccess()))
			.orElseGet(() -> ((EntityBlock) state.getBlock()).newBlockEntity(at, state));
		if (made == null) return null;
		made.setLevel(level);
		live.put(rel, made);
		return made;
	}

	/**
	 * The game's own code setting a block up here: a furnace lighting, a door swinging, a barrel's
	 * lid, an anvil wearing through. Answered on the ship, and what that change does to the blocks
	 * next to it - the other half of a door - followed as the game would. A block that is not
	 * already the ship's is refused: nothing new is built at sea.
	 */
	boolean setBlock(BlockPos pos, BlockState state, int flags, int limit) {
		if (released) return false;
		RelativeBlockPos rel = rel(pos);
		int i = indexOf(rel);
		if (i < 0) return false;

		BlockState before = ship.getBlocks().get(i).blockState();
		if (before == state) return true;

		if (state.isAir()) {
			live.remove(rel);
			dirty.remove(rel);
			ship.removeShipBlock(i);
		} else {
			ship.updateShipBlock(i, state);
			BlockEntity blockEntity = live.get(rel);
			if (blockEntity != null && blockEntity.getType().isValid(state)) blockEntity.setBlockState(state);
			else if (blockEntity != null) live.remove(rel);
		}
		if (before.getLightEmission() != state.getLightEmission()) ship.refreshLights();

		if ((flags & Block.UPDATE_KNOWN_SHAPE) == 0 && limit > 0) {
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction);
				BlockState neighbour = stateAt(next);
				if (neighbour.isAir()) continue;
				BlockState shaped = neighbour.updateShape(level, level, next, direction.getOpposite(), pos, state, level.getRandom());
				if (shaped != neighbour) setBlock(next, shaped, flags, Math.min(limit, SHAPE_DEPTH) - 1);
			}
		}
		return true;
	}

	void markDirty(BlockPos pos) {
		if (!released) dirty.add(rel(pos));
	}

	/** Each tick at sea: what keeps working at sea works, then what changed is written back. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void tick() {
		if (released) return;
		List<ShipBlock> blocks = ship.getBlocks();
		for (ShipBlock block : blocks) {
			BlockState state = block.blockState();
			if (!state.hasBlockEntity() || !state.is(TICKS)) continue;
			BlockPos at = virtual(block.relativePos());
			BlockEntity blockEntity = blockEntityAt(at);
			if (blockEntity == null) continue;
			BlockState current = stateAt(at);
			BlockEntityTicker ticker = current.getTicker(level, blockEntity.getType());
			if (ticker == null) continue;
			try {
				ticker.tick(level, at, current, blockEntity);
			} catch (RuntimeException e) {
				LOGGER.warn("A {} aboard a ship failed to tick at sea", current.getBlock(), e);
			}
		}
		flush();
	}

	/** Write every changed block entity back into the ship's blocks. */
	void flush() {
		if (dirty.isEmpty()) return;
		Map<Integer, CompoundTag> saved = new HashMap<>();
		for (RelativeBlockPos rel : dirty) {
			BlockEntity blockEntity = live.get(rel);
			int i = indexOf(rel);
			if (blockEntity == null || i < 0) continue;
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			blockEntity.saveWithId(output);
			saved.put(i, output.buildResult());
		}
		dirty.clear();
		if (saved.isEmpty()) return;
		// One copy of her blocks for the lot, not one a block entity.
		ship.editBlocks(blocks -> saved.forEach((i, nbt) -> blocks.set(i, blocks.get(i).withBlockEntityData(nbt))));
	}

	/**
	 * Before she docks: shut every screen open on her, while the stretch still answers so each one
	 * closes as the game closes it, then write every block entity back. Nothing is left in a chest
	 * that is about to stop existing.
	 */
	void release() {
		if (released) return;
		for (ServerPlayer player : level.players()) {
			if (player.containerMenu != player.inventoryMenu && (showsOurs(player) || openHere(player))) player.closeContainer();
		}
		dirty.addAll(live.keySet());
		flush();
		// Each lock back off its place up here and onto the block it belongs to, as it stands now:
		// one locked at sea goes ashore locked, and one unlocked at sea goes ashore open.
		ship.editBlocks(blocks -> {
			for (int i = 0; i < blocks.size(); i++) {
				ShipBlock block = blocks.get(i);
				if (!block.blockState().hasBlockEntity()) continue;
				blocks.set(i, block.withLock(justfatlard.big_boats.integration.ChestLockCarry.lift(level, virtual(block.relativePos()))));
			}
		});
		released = true;
		live.clear();
	}

	/**
	 * Whether this player's open screen holds one of her containers in its slots, whatever screen it
	 * is: a mod's own chest screen answers for itself whether it is still open, and would stay open
	 * on a chest that has just been written back to the ship.
	 */
	private boolean showsOurs(ServerPlayer player) {
		for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
			net.minecraft.world.Container container = slot.container;
			for (BlockEntity blockEntity : live.values()) {
				if (container == blockEntity) return true;
				if (container instanceof net.minecraft.world.CompoundContainer pair
						&& blockEntity instanceof net.minecraft.world.Container part && pair.contains(part)) return true;
			}
		}
		return false;
	}

	/** Whether this player's open screen belongs up here: it stops being valid without the hold. */
	private boolean openHere(ServerPlayer player) {
		released = true;
		try {
			return !player.containerMenu.stillValid(player);
		} finally {
			released = false;
		}
	}

	boolean inReach(Player player, BlockPos pos, double buffer) {
		Vec3 centre = toWorld(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		double reach = player.blockInteractionRange() + buffer + 0.75;
		return player.getEyePosition().distanceToSqr(centre) <= reach * reach;
	}

	Vec3 toWorld(double x, double y, double z) {
		return ship.pose().toWorldPoint(new Vec3(x - baseX, y - AtSea.HELM_Y, z - baseZ));
	}

	/**
	 * A player used a block of this ship at sea: the block's own handling, as if they had clicked it
	 * in the world - what is in their hand first, then the bare hand - at its place in the stretch.
	 */
	public InteractionResult use(ServerPlayer player, RelativeBlockPos rel) {
		int i = indexOf(rel);
		if (i < 0 || released) return InteractionResult.PASS;
		BlockState state = ship.getBlocks().get(i).blockState();
		if (!state.is(USABLE)) return InteractionResult.PASS;
		// Who may use a block in the world may use one at sea: not a spectator or the dead, and not
		// inside spawn protection, measured where she really is.
		if (player.isSpectator() || !player.isAlive()) return InteractionResult.PASS;
		if (!level.mayInteract(player, BlockPos.containing(toWorld(virtual(rel).getX() + 0.5, virtual(rel).getY() + 0.5, virtual(rel).getZ() + 0.5)))) {
			return InteractionResult.PASS;
		}

		// Struck on the face toward the player, as a click in the world would be: a bell rings only
		// when hit on a side it can swing from.
		BlockPos at = virtual(rel);
		Vec3 eye = ship.pose().toLocalPoint(player.getEyePosition());
		Direction face = Direction.getApproximateNearest(eye.x - (rel.x() + 0.5), eye.y - (rel.y() + 0.5), eye.z - (rel.z() + 0.5));
		Vec3 struck = Vec3.atCenterOf(at).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		BlockHitResult hit = new BlockHitResult(struck, face, at, false);
		try {
			ItemStack held = player.getMainHandItem();
			InteractionResult result = state.useItemOn(held, level, player, InteractionHand.MAIN_HAND, hit);
			if (result instanceof InteractionResult.TryEmptyHandInteraction || result == InteractionResult.PASS) {
				result = stateAt(at).useWithoutItem(level, player, hit);
			}
			flush();
			return result;
		} catch (RuntimeException e) {
			LOGGER.warn("Using a {} aboard a ship at sea failed", state.getBlock(), e);
			return InteractionResult.FAIL;
		}
	}
}
