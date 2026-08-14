package justfatlard.big_boats.ship;

import justfatlard.big_boats.mixin.BlockAttachedEntityAccessor;
import justfatlard.big_boats.mixin.HangingEntityAccessor;
import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.big_boats.util.ShipBlockNbtUtil;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles world-mutation operations for the ship dock/undock lifecycle.
 *
 * <p>Owns {@code dockedBlockPositions} and {@code decorations} state.
 * Places/removes real blocks in the world, captures/restores decoration entities
 * (item frames, paintings), and salvages obstructed blocks as items.</p>
 *
 * <p>MultiBlockShipEntity orchestrates the state machine and coordinates other
 * delegates (physics, collision, lighting, display). This class handles the
 * block-level world mutations that those delegates don't own.</p>
 */
public class ShipDocking {
	private static final Logger LOGGER = LoggerFactory.getLogger(ShipDocking.class);
	static final Codec<List<ShipDecoration>> DECORATIONS_CODEC = ShipDecoration.CODEC.listOf();
	static final Codec<List<BlockPos>> BLOCK_POS_LIST_CODEC = BlockPos.CODEC.listOf();

	// IMMUTABLE SNAPSHOTS: every assignment uses List.copyOf().
	// Volatile: read by writeCustomData on chunk-saving thread.
	private volatile List<BlockPos> dockedBlockPositions = List.of();
	private volatile List<ShipDecoration> decorations = List.of();

	/**
	 * Records world positions of all blocks after christening.
	 */
	public void recordPositions(List<ShipBlock> blocks, BlockPos helmPos) {
		List<BlockPos> positions = new ArrayList<>();
		for (ShipBlock block : blocks) {
			positions.add(helmPos.offset(
				block.relativePos().x(),
				block.relativePos().y(),
				block.relativePos().z()
			));
		}
		dockedBlockPositions = List.copyOf(positions);
	}

	/**
	 * Places ship blocks back into the world. Returns dock statistics.
	 */
	public DockStats placeBlocks(ServerLevel world, List<ShipBlock> blocks,
								  double helmX, double helmY, double helmZ,
								  ShipBlockUtils.SnappedRotation snap) {
		int cos = snap.cos();
		int sin = snap.sin();
		net.minecraft.world.level.block.Rotation blockRotation = ShipBlockUtils.yawToBlockRotation(snap.yawDegrees());

		List<BlockPos> newDockedPositions = new ArrayList<>();
		int blockedCount = 0;
		int lostBlockEntities = 0;

		for (ShipBlock block : blocks) {
			BlockPos worldPos = ShipBlockUtils.relativeToWorld(block.relativePos(), helmX, helmY, helmZ, cos, sin);
			BlockState rotatedState = block.blockState().rotate(blockRotation);
			BlockState existing = world.getBlockState(worldPos);

			if (existing.isAir() || existing.liquid()) {
				world.setBlock(worldPos, rotatedState, Block.UPDATE_ALL);
				newDockedPositions.add(worldPos);

				if (block.hasBlockEntityData()) {
					CompoundTag savedNbt = block.blockEntityData().get();
					BlockEntity restored = BlockEntity.loadStatic(worldPos, rotatedState, savedNbt, world.registryAccess());
					if (restored != null) {
						world.setBlockEntity(restored);
						// Verify the block entity was actually added: setBlockEntity can fail
						// silently if a block entity already exists at that position
						if (world.getBlockEntity(worldPos) == null) {
							LOGGER.warn("Block entity at {} was not added to world — salvaging contents", worldPos);
							salvageBlockEntityContents(world, savedNbt, worldPos);
							lostBlockEntities++;
						}
					} else {
						LOGGER.warn("Failed to restore block entity at {} for state {} — salvaging contents", worldPos, rotatedState);
						salvageBlockEntityContents(world, savedNbt, worldPos);
						lostBlockEntities++;
					}
				}
			} else {
				salvageObstructedBlock(world, block, worldPos);
				blockedCount++;
			}
		}

		dockedBlockPositions = List.copyOf(newDockedPositions);
		LOGGER.debug("Dock placed {} blocks, {} obstructed, {} lost block entities",
			newDockedPositions.size(), blockedCount, lostBlockEntities);

		return new DockStats(newDockedPositions.size(), blockedCount, lostBlockEntities);
	}

	/**
	 * Restores decoration entities (item frames, paintings) into the world.
	 */
	public void restoreDecorations(ServerLevel world, double helmX, double helmY, double helmZ,
									ShipBlockUtils.SnappedRotation snap) {
		if (decorations.isEmpty()) return;

		int cos = snap.cos();
		int sin = snap.sin();
		net.minecraft.world.level.block.Rotation blockRotation = ShipBlockUtils.yawToBlockRotation(snap.yawDegrees());

		for (ShipDecoration decoration : decorations) {
			BlockPos worldAttachPos = ShipBlockUtils.relativeToWorld(
				decoration.attachmentPos(), helmX, helmY, helmZ, cos, sin);
			Direction worldFacing = blockRotation.rotate(decoration.facing());

			CompoundTag nbt = decoration.entityNbt().copy();
			nbt.remove("UUID");

			// Reconstruct the entity at its OLD (docked) attachment position/facing exactly as
			// captured, then reposition it via accessor mixins rather than patching raw NBT
			// tags: the item frame/painting NBT format (tag names, facing encoding) isn't
			// stable across mapping/version changes, but the public entity object always is.
			Entity loaded = EntityType.loadEntityRecursive(nbt, world,
				new EntitySpawnRequest(EntitySpawnReason.LOAD, false), entity -> {
					entity.setUUID(UUID.randomUUID());
					if (entity instanceof HangingEntity hanging) {
						((BlockAttachedEntityAccessor) hanging).setAttachedPos(worldAttachPos);
						((HangingEntityAccessor) hanging).invokeSetDirection(worldFacing);
						((HangingEntityAccessor) hanging).invokeRecalculateBoundingBox();
					}
					return entity;
				});
			if (loaded != null) {
				world.addFreshEntity(loaded);
			}
		}
		LOGGER.debug("Restored {} decoration entities", decorations.size());
		decorations = List.of();
	}

	/**
	 * Captures decoration entities and removes ship blocks from the world.
	 * Returns updated block list with fresh block entity data.
	 *
	 * @param posToBlockIndex mapping from world position to block index (caller computes this)
	 */
	public List<ShipBlock> removeBlocks(ServerLevel world, List<ShipBlock> blocks,
										 double helmX, double helmY, double helmZ,
										 ShipBlockUtils.SnappedRotation snap,
										 Map<BlockPos, Integer> posToBlockIndex) {
		int cos = snap.cos();
		int sin = snap.sin();
		net.minecraft.world.level.block.Rotation inverseRotation = ShipBlockUtils.yawToBlockRotation(-snap.yawDegrees());

		if (dockedBlockPositions.isEmpty()) {
			dockedBlockPositions = List.copyOf(posToBlockIndex.keySet());
		}

		captureDecorations(world, posToBlockIndex, helmX, helmY, helmZ, cos, sin, inverseRotation);

		// Save block entity data and remove all placed blocks.
		// Build mutable working copy, then return as immutable snapshot.
		List<ShipBlock> updatedBlocks = new ArrayList<>(blocks);
		for (BlockPos pos : dockedBlockPositions) {
			if (posToBlockIndex.containsKey(pos)) {
				BlockEntity blockEntity = world.getBlockEntity(pos);
				if (blockEntity != null) {
					Integer blockIndex = posToBlockIndex.get(pos);
					if (blockIndex != null) {
						TagValueOutput output = ShipBlockNbtUtil.newOutput(world);
						blockEntity.saveWithId(output);
						CompoundTag nbt = output.buildResult();
						ShipBlock oldBlock = updatedBlocks.get(blockIndex);
						updatedBlocks.set(blockIndex, oldBlock.withBlockEntityData(nbt));
					}
					if (blockEntity instanceof Container container) {
						container.clearContent();
					}
					world.removeBlockEntity(pos);
				}
				world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}

		dockedBlockPositions = List.of();
		return List.copyOf(updatedBlocks);
	}

	private void captureDecorations(ServerLevel world, Map<BlockPos, Integer> posToBlockIndex,
									 double helmX, double helmY, double helmZ,
									 int cos, int sin, net.minecraft.world.level.block.Rotation inverseRotation) {
		// Build bounding box from block positions
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (BlockPos pos : posToBlockIndex.keySet()) {
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX() + 1);
			maxY = Math.max(maxY, pos.getY() + 1);
			maxZ = Math.max(maxZ, pos.getZ() + 1);
		}
		AABB searchBox = new AABB(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1);

		List<ShipDecoration> capturedDecorations = new ArrayList<>();

		for (BlockAttachedEntity attachedEntity : world.getEntities(
				net.minecraft.world.level.entity.EntityTypeTest.forClass(BlockAttachedEntity.class),
				searchBox, e -> !e.isRemoved())) {
			BlockPos attachedPos = attachedEntity.getPos();
			// Check attached pos AND all neighbors: getPos() returns the
			// air block the entity occupies, not the support block behind it
			boolean attachedToShip = posToBlockIndex.containsKey(attachedPos);
			if (!attachedToShip) {
				for (Direction dir : Direction.values()) {
					if (posToBlockIndex.containsKey(attachedPos.relative(dir))) {
						attachedToShip = true;
						break;
					}
				}
			}
			if (attachedToShip) {
				int worldDeltaX = attachedPos.getX() - (int) Math.floor(helmX);
				int worldDeltaY = attachedPos.getY() - (int) Math.floor(helmY);
				int worldDeltaZ = attachedPos.getZ() - (int) Math.floor(helmZ);
				var localPos = ShipBlockUtils.worldToRelative(worldDeltaX, worldDeltaY, worldDeltaZ, cos, sin);
				Direction localFacing = inverseRotation.rotate(attachedEntity.getDirection());

				TagValueOutput writeView = ShipBlockNbtUtil.newOutput(world);
				attachedEntity.save(writeView);
				CompoundTag nbt = writeView.buildResult();
				capturedDecorations.add(new ShipDecoration(localPos, localFacing, nbt));
				attachedEntity.discard();
			}
		}
		decorations = List.copyOf(capturedDecorations);
		if (!decorations.isEmpty()) {
			LOGGER.debug("Captured {} decoration entities", decorations.size());
		}
	}

	/**
	 * Adds newly absorbed block positions to the docked positions list.
	 * Called by rescan when new blocks are detected adjacent to the ship.
	 */
	public void addDockedPositions(List<BlockPos> newPositions) {
		List<BlockPos> updated = new ArrayList<>(dockedBlockPositions);
		updated.addAll(newPositions);
		dockedBlockPositions = List.copyOf(updated);
	}

	// --- Salvage helpers ---

	private void salvageObstructedBlock(ServerLevel world, ShipBlock block, BlockPos worldPos) {
		Item blockItem = block.blockState().getBlock().asItem();
		if (blockItem != null && blockItem != Items.AIR) {
			ItemStack stack = new ItemStack(blockItem);
			if (block.hasBlockEntityData()) {
				if (!applyBlockEntityToStack(stack, block.blockEntityData().get())) {
					salvageBlockEntityContents(world, block.blockEntityData().get(), worldPos);
				}
			}
			world.addFreshEntity(new ItemEntity(world,
				worldPos.getX() + 0.5, worldPos.getY() + 1, worldPos.getZ() + 0.5, stack));
		} else if (block.hasBlockEntityData()) {
			salvageBlockEntityContents(world, block.blockEntityData().get(), worldPos);
		}
	}

	private boolean applyBlockEntityToStack(ItemStack stack, CompoundTag nbt) {
		return nbt.getString("id").map(idStr ->
			BuiltInRegistries.BLOCK_ENTITY_TYPE.get(Identifier.parse(idStr))
				.map(beTypeRef -> {
					CompoundTag dataNbt = nbt.copy();
					dataNbt.remove("id");
					dataNbt.remove("x");
					dataNbt.remove("y");
					dataNbt.remove("z");
					stack.set(DataComponents.BLOCK_ENTITY_DATA,
						TypedEntityData.of(beTypeRef.value(), dataNbt));
					return true;
				}).orElse(false)
		).orElse(false);
	}

	private void salvageBlockEntityContents(ServerLevel world, CompoundTag nbt, BlockPos worldPos) {
		nbt.getList("Items").ifPresent(itemsNbt -> {
			for (int j = 0; j < itemsNbt.size(); j++) {
				itemsNbt.getCompound(j).ifPresent(itemNbt -> {
					ItemStack.CODEC.parse(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), itemNbt)
						.result().ifPresent(stack -> {
							if (!stack.isEmpty()) {
								world.addFreshEntity(new ItemEntity(world,
									worldPos.getX() + 0.5, worldPos.getY() + 1, worldPos.getZ() + 0.5, stack));
							}
						});
				});
			}
		});
	}

	// --- Getters ---

	public List<BlockPos> getDockedBlockPositions() {
		return dockedBlockPositions;
	}

	public List<ShipDecoration> getDecorations() {
		return decorations;
	}

	public boolean hasDockedPositions() {
		return !dockedBlockPositions.isEmpty();
	}

	// --- Serialization ---

	public void loadState(List<BlockPos> positions, List<ShipDecoration> decos) {
		this.dockedBlockPositions = List.copyOf(positions);
		this.decorations = List.copyOf(decos);
	}

	// --- Result record ---

	public record DockStats(int placed, int obstructed, int lostBlockEntities) {}
}
