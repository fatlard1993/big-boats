package justfatlard.big_boats.ship;

import com.mojang.serialization.Codec;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import justfatlard.big_boats.BigBoats;
import justfatlard.big_boats.util.PlayerInputStorage;
import net.minecraft.util.PlayerInput;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A multi-block ship entity that can be driven by players.
 * Contains multiple blocks detected via flood-fill from the helm.
 */
public class MultiBlockShipEntity extends Entity implements PolymerEntity {
	private static final Codec<List<ShipBlock>> BLOCKS_CODEC = ShipBlock.CODEC.listOf();
	private static final String BLOCKS_KEY = "ship_blocks";

	private List<ShipBlock> blocks = new ArrayList<>();
	private ShipElementHolder elementHolder;
	private EntityAttachment attachment;

	// Collision entities - Shulkers for each block position
	private List<ShulkerEntity> collisionShulkers = new ArrayList<>();

	// Helm interaction entity for mounting
	private InteractionEntity helmInteraction;

	// Seat entity - player rides this, we move it to handle rotation
	private Entity seatEntity;

	// The Y position where the ship was launched - this is the target float height
	private double targetFloatY;

	// The direction the helm faces (where the wheel is visible from)
	private Direction helmFacing = Direction.NORTH;

	// Visual rotation (separate from entity yaw to prevent pig body rotation drift)
	private float visualYaw = 0;

	// Logical base position (helm corner) - entity orbits around this
	private double baseX, baseZ;

	// Docking system: real blocks when stationary, virtual when moving
	private boolean docked = false;
	private List<BlockPos> dockedBlockPositions = new ArrayList<>();

	// Velocity-based movement for smooth physics
	private double velocityX = 0;
	private double velocityZ = 0;
	private static final double ACCELERATION = 0.008;  // How fast ship speeds up
	private static final double MAX_SPEED = 0.18;      // Maximum speed
	private static final double DRAG = 0.98;           // Friction/water drag (0.98 = 2% slowdown per tick)

	public MultiBlockShipEntity(EntityType<?> entityType, World world) {
		super(entityType, world);
	}

	public MultiBlockShipEntity(World world, double x, double y, double z, List<ShipBlock> blocks, Direction helmFacing) {
		this(BigBoats.MULTI_BLOCK_SHIP_ENTITY_TYPE, world);
		this.setPosition(x, y, z);
		this.blocks = new ArrayList<>(blocks);
		this.targetFloatY = y; // Stay at original position
		this.helmFacing = helmFacing;

		// Track logical base position (helm corner)
		this.baseX = x;
		this.baseZ = z;

		// Ship spawns with yaw=0 (no visual rotation) - blocks appear in original positions
		// helmFacing determines which direction is "forward" for movement
		// Entity yaw and visualYaw stay in sync for proper passenger positioning
		this.setYaw(0);
		this.visualYaw = 0;

		initializeElementHolder();
	}

	/**
	 * Initializes the ship after christening.
	 * Ship starts DOCKED with real blocks still in place - undocks when player mounts.
	 */
	public void initializeShip(BlockPos helmPos) {
		System.out.println("[Ship] initializeShip() called at " + helmPos + ", blocks=" + blocks.size());
		if (!(this.getEntityWorld() instanceof ServerWorld)) {
			System.out.println("[Ship] initializeShip() - not server world, returning");
			return;
		}

		// Ship starts DOCKED - real blocks remain in place
		// They will be removed when player mounts and undock() is called
		docked = true;
		System.out.println("[Ship] Set docked=true");

		// Record world positions of all blocks for undocking
		dockedBlockPositions.clear();
		for (ShipBlock block : blocks) {
			BlockPos worldPos = helmPos.add(
				block.relativePos().x(),
				block.relativePos().y(),
				block.relativePos().z()
			);
			dockedBlockPositions.add(worldPos);
		}

		// Hide virtual display (real blocks are visible)
		if (elementHolder != null) {
			elementHolder.setVisible(false);
		}

		// Hide collision shulkers (real blocks have collision)
		for (ShulkerEntity shulker : collisionShulkers) {
			if (shulker != null) {
				shulker.setInvisible(true);
				shulker.setNoGravity(true);
				shulker.setAiDisabled(true);
			}
		}
		System.out.println("[Ship] initializeShip() complete, dockedBlockPositions=" + dockedBlockPositions.size());
	}

	/**
	 * Docks the ship - places real blocks at current position for full interaction.
	 * Called when no one is driving. Snaps to nearest cardinal direction.
	 */
	public void dock() {
		if (docked || !(this.getEntityWorld() instanceof ServerWorld world)) {
			return;
		}

		// Reset velocity when docking
		velocityX = 0;
		velocityZ = 0;

		// Snap rotation to nearest 90 degrees (cardinal direction)
		float snappedYaw = Math.round(visualYaw / 90.0f) * 90.0f;
		visualYaw = snappedYaw;
		this.setYaw(snappedYaw);

		// Snap position to block grid
		baseX = Math.round(baseX);
		baseZ = Math.round(baseZ);

		float yawRadians = (float) Math.toRadians(snappedYaw);
		// For cardinal directions, cos/sin will be exactly 0, 1, or -1
		int cos = (int) Math.round(Math.cos(yawRadians));
		int sin = (int) Math.round(Math.sin(yawRadians));

		dockedBlockPositions.clear();

		// Calculate block rotation from ship yaw
		net.minecraft.util.BlockRotation blockRotation = yawToBlockRotation(snappedYaw);

		for (ShipBlock block : blocks) {
			// Calculate rotated world position (integer math for perfect grid alignment)
			int relX = block.relativePos().x();
			int relZ = block.relativePos().z();
			int rotatedX = relX * cos - relZ * sin;
			int rotatedZ = relX * sin + relZ * cos;

			BlockPos worldPos = new BlockPos(
				(int) baseX + rotatedX,
				(int) this.getY() + block.relativePos().y(),
				(int) baseZ + rotatedZ
			);

			// Rotate block state to match ship rotation (for directional blocks like helm)
			BlockState rotatedState = block.blockState().rotate(blockRotation);

			// Only place if space is air or water
			BlockState existing = world.getBlockState(worldPos);
			if (existing.isAir() || existing.isLiquid()) {
				world.setBlockState(worldPos, rotatedState);
				dockedBlockPositions.add(worldPos);

				// Restore block entity data if present (for chests, furnaces, signs, etc.)
				if (block.hasBlockEntityData()) {
					NbtCompound savedNbt = block.blockEntityData().get();

					// Get the block entity that was auto-created when we set the block state
					BlockEntity blockEntity = world.getBlockEntity(worldPos);

					if (blockEntity != null) {
						// For inventory blocks, copy the items directly
						if (blockEntity instanceof net.minecraft.inventory.Inventory inventory) {
							// Read items from saved NBT using 1.21.11 codec API
							var ops = net.minecraft.nbt.NbtOps.INSTANCE;
							var registryOps = world.getRegistryManager().getOps(ops);
							savedNbt.getList("Items").ifPresent(itemList -> {
								for (int slot = 0; slot < inventory.size(); slot++) {
									inventory.setStack(slot, ItemStack.EMPTY);
								}
								for (int i = 0; i < itemList.size(); i++) {
									itemList.getCompound(i).ifPresent(itemNbt -> {
										itemNbt.getByte("Slot").ifPresent(slotByte -> {
											int slot = slotByte & 255;
											if (slot < inventory.size()) {
												// Use codec to decode item stack from NBT
												ItemStack stack = ItemStack.OPTIONAL_CODEC.decode(registryOps, itemNbt)
													.result()
													.map(com.mojang.datafixers.util.Pair::getFirst)
													.orElse(ItemStack.EMPTY);
												inventory.setStack(slot, stack);
											}
										});
									});
								}
							});
						}
						blockEntity.markDirty();
					}
				}
			}
		}

		// Hide virtual display blocks
		if (elementHolder != null) {
			elementHolder.setVisible(false);
		}

		// Remove collision shulkers (real blocks have collision now)
		// Shulkers always have collision even when invisible, so we must discard them
		for (ShulkerEntity shulker : collisionShulkers) {
			if (shulker != null && !shulker.isRemoved()) {
				shulker.discard();
			}
		}
		collisionShulkers.clear();

		docked = true;
	}

	/**
	 * Undocks the ship - removes real blocks, enables virtual display for movement.
	 * Called when player starts driving. Saves block entity data first.
	 * Re-detects ship structure to include any blocks added while docked.
	 */
	public void undock() {
		System.out.println("[Ship] undock() called, docked=" + docked + ", dockedBlockPositions=" + dockedBlockPositions.size());
		if (!docked || !(this.getEntityWorld() instanceof ServerWorld world)) {
			System.out.println("[Ship] undock() returning early - not docked or not server world");
			return;
		}

		// Re-detect ship structure to include any blocks added while docked
		BlockPos helmWorldPos = new BlockPos((int) baseX, (int) this.getY(), (int) baseZ);
		rescanShipStructure(world, helmWorldPos);

		// Build mapping from world position to ShipBlock index for block entity data saving
		float snappedYaw = Math.round(visualYaw / 90.0f) * 90.0f;
		float yawRadians = (float) Math.toRadians(snappedYaw);
		int cos = (int) Math.round(Math.cos(yawRadians));
		int sin = (int) Math.round(Math.sin(yawRadians));

		java.util.Map<BlockPos, Integer> posToBlockIndex = new java.util.HashMap<>();
		for (int i = 0; i < blocks.size(); i++) {
			ShipBlock block = blocks.get(i);
			int relX = block.relativePos().x();
			int relZ = block.relativePos().z();
			int rotatedX = relX * cos - relZ * sin;
			int rotatedZ = relX * sin + relZ * cos;

			BlockPos worldPos = new BlockPos(
				(int) baseX + rotatedX,
				(int) this.getY() + block.relativePos().y(),
				(int) baseZ + rotatedZ
			);
			posToBlockIndex.put(worldPos, i);
		}

		// If dockedBlockPositions is empty (e.g., loaded from old save), recalculate from current position
		if (dockedBlockPositions.isEmpty()) {
			System.out.println("[Ship] dockedBlockPositions empty, recalculating from current position");
			for (BlockPos pos : posToBlockIndex.keySet()) {
				dockedBlockPositions.add(pos);
			}
			System.out.println("[Ship] Recalculated " + dockedBlockPositions.size() + " positions");
		}

		// Save block entity data and remove all placed blocks
		for (BlockPos pos : dockedBlockPositions) {
			BlockState state = world.getBlockState(pos);
			// Only process if it's still one of our blocks
			boolean isOurBlock = blocks.stream().anyMatch(b -> b.blockState().getBlock() == state.getBlock());
			if (isOurBlock) {
				// Save block entity data before removing (for chests, furnaces, etc.)
				BlockEntity blockEntity = world.getBlockEntity(pos);
				if (blockEntity != null) {
					Integer blockIndex = posToBlockIndex.get(pos);
					if (blockIndex != null) {
						NbtCompound nbt = blockEntity.createNbtWithIdentifyingData(world.getRegistryManager());
						ShipBlock oldBlock = blocks.get(blockIndex);
						blocks.set(blockIndex, oldBlock.withBlockEntityData(nbt));
					}
					// Clear container inventory before removal to prevent item drops
					if (blockEntity instanceof net.minecraft.inventory.Inventory inventory) {
						inventory.clear();
					}
					// Remove block entity first to prevent drops
					world.removeBlockEntity(pos);
				}

				// Use flags to skip drops: Block.NOTIFY_LISTENERS (2) without triggering drops
				world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);
			}
		}
		dockedBlockPositions.clear();

		// Show virtual display blocks and update their positions
		if (elementHolder != null) {
			elementHolder.setVisible(true);
			// Force position update for display elements
			float displayYawRadians = (float) Math.toRadians(visualYaw);
			double playerOffset = 0.5;
			double basePlayerOffsetX = 0, basePlayerOffsetZ = 0;
			switch (helmFacing) {
				case NORTH -> basePlayerOffsetZ = -playerOffset;
				case SOUTH -> basePlayerOffsetZ = playerOffset;
				case EAST -> basePlayerOffsetX = playerOffset;
				case WEST -> basePlayerOffsetX = -playerOffset;
				default -> {}
			}
			double rotatedPlayerX = basePlayerOffsetX * Math.cos(displayYawRadians) - basePlayerOffsetZ * Math.sin(displayYawRadians);
			double rotatedPlayerZ = basePlayerOffsetX * Math.sin(displayYawRadians) + basePlayerOffsetZ * Math.cos(displayYawRadians);
			elementHolder.updateRotationWithOffset(displayYawRadians, (float)(-rotatedPlayerX - 0.5), (float)(-rotatedPlayerZ - 0.5));
		}

		// Respawn collision shulkers (they were discarded when docked)
		spawnCollisionEntities(world);
		updateCollisionPositionsWithOffset(baseX, baseZ, (float) Math.toRadians(visualYaw));

		// Update entity position for proper passenger placement
		double playerOffset = 0.5;
		double basePlayerOffsetX = 0, basePlayerOffsetZ = 0;
		float entityYawRadians = (float) Math.toRadians(visualYaw);
		switch (helmFacing) {
			case NORTH -> basePlayerOffsetZ = -playerOffset;
			case SOUTH -> basePlayerOffsetZ = playerOffset;
			case EAST -> basePlayerOffsetX = playerOffset;
			case WEST -> basePlayerOffsetX = -playerOffset;
			default -> {}
		}
		double rotatedPlayerX = basePlayerOffsetX * Math.cos(entityYawRadians) - basePlayerOffsetZ * Math.sin(entityYawRadians);
		double rotatedPlayerZ = basePlayerOffsetX * Math.sin(entityYawRadians) + basePlayerOffsetZ * Math.cos(entityYawRadians);
		double newEntityX = baseX + 0.5 + rotatedPlayerX;
		double newEntityZ = baseZ + 0.5 + rotatedPlayerZ;
		this.setPosition(newEntityX, this.getY(), newEntityZ);

		// Update seat entity position (for player riding)
		if (seatEntity != null && !seatEntity.isRemoved()) {
			seatEntity.setPosition(newEntityX, this.getY(), newEntityZ);
		}

		// Update helm interaction position
		if (helmInteraction != null && !helmInteraction.isRemoved()) {
			helmInteraction.setPosition(baseX + 0.5, this.getY(), baseZ + 0.5);
		}

		docked = false;
	}

	/**
	 * Auto-dock on spawn so ship starts with real blocks.
	 */
	public void dockOnSpawn() {
		// Small delay to let the world settle, then dock
		dock();
	}

	public boolean isDocked() {
		return docked;
	}

	/**
	 * Absorbs additional blocks into the ship (e.g., small docks connected at undock time).
	 * Removes the blocks from the world and adds them to the ship's virtual display.
	 * Applies inverse rotation to convert world-relative positions to ship-local positions.
	 */
	private void absorbBlocks(ServerWorld world, List<ShipBlock> newBlocks) {
		// Get current rotation for inverse transformation
		float snappedYaw = Math.round(visualYaw / 90.0f) * 90.0f;
		float yawRadians = (float) Math.toRadians(snappedYaw);
		int cos = (int) Math.round(Math.cos(yawRadians));
		int sin = (int) Math.round(Math.sin(yawRadians));

		List<ShipBlock> localizedBlocks = new ArrayList<>();

		for (ShipBlock block : newBlocks) {
			// The block's relativePos is in WORLD space (relative to helm world position)
			int worldRelX = block.relativePos().x();
			int worldRelY = block.relativePos().y();
			int worldRelZ = block.relativePos().z();

			// Calculate actual world position for block removal
			BlockPos worldPos = new BlockPos(
				(int) baseX + worldRelX,
				(int) this.getY() + worldRelY,
				(int) baseZ + worldRelZ
			);

			// Apply inverse rotation to convert to SHIP-LOCAL space
			// Inverse of rotation by theta is rotation by -theta: (cos, -sin)
			int localX = worldRelX * cos + worldRelZ * sin;
			int localZ = -worldRelX * sin + worldRelZ * cos;

			justfatlard.big_boats.util.RelativeBlockPos localRelPos =
				new justfatlard.big_boats.util.RelativeBlockPos(localX, worldRelY, localZ);

			// Save block entity data before removing
			BlockEntity blockEntity = world.getBlockEntity(worldPos);
			ShipBlock blockToAdd = new ShipBlock(localRelPos, block.blockState(), block.blockEntityData());
			if (blockEntity != null) {
				NbtCompound nbt = blockEntity.createNbtWithIdentifyingData(world.getRegistryManager());
				blockToAdd = blockToAdd.withBlockEntityData(nbt);

				// Clear inventory to prevent drops
				if (blockEntity instanceof net.minecraft.inventory.Inventory inventory) {
					inventory.clear();
				}
				world.removeBlockEntity(worldPos);
			}

			// Remove block from world
			world.setBlockState(worldPos, net.minecraft.block.Blocks.AIR.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);

			// Add to ship's block list (with ship-local position)
			blocks.add(blockToAdd);
			localizedBlocks.add(blockToAdd);

			// Add to docked positions (will be placed when ship docks again)
			dockedBlockPositions.add(worldPos);

			// Spawn collision shulker for the new block (uses ship-local position)
			spawnCollisionShulkerForBlock(world, blockToAdd);
		}

		// Update element holder with new blocks (using ship-local positions)
		if (elementHolder != null) {
			elementHolder.addBlocks(localizedBlocks, yawRadians);
		}
	}

	/**
	 * Re-scans the ship structure from the helm position to detect any blocks added while docked.
	 * New blocks are added to the ship's block list and display holder.
	 */
	private void rescanShipStructure(ServerWorld world, BlockPos helmWorldPos) {
		// Get current rotation (snapped to cardinal)
		float snappedYaw = Math.round(visualYaw / 90.0f) * 90.0f;
		float yawRadians = (float) Math.toRadians(snappedYaw);
		int cos = (int) Math.round(Math.cos(yawRadians));
		int sin = (int) Math.round(Math.sin(yawRadians));

		// Build set of current ship block world positions
		Set<BlockPos> currentWorldPositions = new HashSet<>();
		for (ShipBlock block : blocks) {
			int relX = block.relativePos().x();
			int relZ = block.relativePos().z();
			// Apply rotation to get world position
			int rotatedX = relX * cos - relZ * sin;
			int rotatedZ = relX * sin + relZ * cos;

			BlockPos worldPos = new BlockPos(
				(int) baseX + rotatedX,
				(int) this.getY() + block.relativePos().y(),
				(int) baseZ + rotatedZ
			);
			currentWorldPositions.add(worldPos);
		}

		// Run flood-fill from helm to detect all connected blocks
		var detectionResult = justfatlard.big_boats.detection.FloodFillDetector.detect(world, helmWorldPos);
		if (!detectionResult.success()) {
			System.out.println("[Ship] rescanShipStructure failed: " + detectionResult.errorMessage());
			return;
		}

		// Find new blocks (in detection result but not in current ship)
		List<ShipBlock> newBlocks = new ArrayList<>();
		for (ShipBlock detectedBlock : detectionResult.blocks()) {
			// Detection result has positions relative to helm at yaw=0
			// But the world blocks are at rotated positions, so detection gives us
			// the ACTUAL relative position from current helm world position
			BlockPos detectedWorldPos = helmWorldPos.add(
				detectedBlock.relativePos().x(),
				detectedBlock.relativePos().y(),
				detectedBlock.relativePos().z()
			);

			if (!currentWorldPositions.contains(detectedWorldPos)) {
				// This is a new block - need to calculate its relative position
				// accounting for current ship rotation (inverse rotation)
				int worldDeltaX = detectedWorldPos.getX() - (int) baseX;
				int worldDeltaY = detectedWorldPos.getY() - (int) this.getY();
				int worldDeltaZ = detectedWorldPos.getZ() - (int) baseZ;

				// Apply inverse rotation to get original relative position
				// Inverse of rotation by theta is rotation by -theta
				// cos(-theta) = cos(theta), sin(-theta) = -sin(theta)
				int unrotatedX = worldDeltaX * cos + worldDeltaZ * sin;
				int unrotatedZ = -worldDeltaX * sin + worldDeltaZ * cos;

				justfatlard.big_boats.util.RelativeBlockPos newRelPos =
					new justfatlard.big_boats.util.RelativeBlockPos(unrotatedX, worldDeltaY, unrotatedZ);

				ShipBlock newBlock = new ShipBlock(newRelPos, detectedBlock.blockState(), detectedBlock.blockEntityData());
				newBlocks.add(newBlock);

				System.out.println("[Ship] Found new block at world " + detectedWorldPos +
					" -> relative " + newRelPos + " (type: " + detectedBlock.blockState().getBlock() + ")");
			}
		}

		if (!newBlocks.isEmpty()) {
			System.out.println("[Ship] rescanShipStructure found " + newBlocks.size() + " new blocks");

			// Add new blocks to ship
			blocks.addAll(newBlocks);

			// Update dockedBlockPositions with new block positions
			for (ShipBlock block : newBlocks) {
				int relX = block.relativePos().x();
				int relZ = block.relativePos().z();
				int rotatedX = relX * cos - relZ * sin;
				int rotatedZ = relX * sin + relZ * cos;

				BlockPos worldPos = new BlockPos(
					(int) baseX + rotatedX,
					(int) this.getY() + block.relativePos().y(),
					(int) baseZ + rotatedZ
				);
				dockedBlockPositions.add(worldPos);
			}

			// Update element holder with new blocks
			if (elementHolder != null) {
				elementHolder.addBlocks(newBlocks, yawRadians);
			}

			// Spawn collision shulkers for new blocks
			for (ShipBlock block : newBlocks) {
				spawnCollisionShulkerForBlock(world, block);
			}
		}
	}

	/**
	 * Spawns a collision shulker for a single block (used when absorbing blocks).
	 */
	private void spawnCollisionShulkerForBlock(ServerWorld world, ShipBlock block) {
		ShulkerEntity shulker = new ShulkerEntity(EntityType.SHULKER, world);

		Vec3d blockPos = block.relativePos().toVec3d();
		shulker.setPosition(
			baseX + blockPos.x,
			this.getY() + blockPos.y,
			baseZ + blockPos.z
		);

		shulker.addStatusEffect(new StatusEffectInstance(
			StatusEffects.INVISIBILITY,
			Integer.MAX_VALUE,
			0, false, false, false
		));

		shulker.setAiDisabled(true);
		shulker.setNoGravity(true);
		shulker.setSilent(true);
		shulker.setInvulnerable(true);

		world.spawnEntity(shulker);
		collisionShulkers.add(shulker);
	}

	private void initializeElementHolder() {
		if (this.getEntityWorld() instanceof ServerWorld serverWorld && !blocks.isEmpty()) {
			// Pass initial yaw so blocks are created with correct rotation from the start
			float yawRadians = (float) Math.toRadians(visualYaw);
			this.elementHolder = new ShipElementHolder(blocks, yawRadians);
			this.attachment = EntityAttachment.ofTicking(this.elementHolder, this);

			spawnCollisionEntities(serverWorld);
			spawnHelmInteraction(serverWorld);
		}
	}

	/**
	 * Spawns invisible Shulker entities at each block position for collision.
	 */
	private void spawnCollisionEntities(ServerWorld world) {
		for (ShipBlock block : blocks) {
			// Skip helm position (0,0,0) so armor stand is clickable there
			if (block.relativePos().x() == 0 && block.relativePos().y() == 0 && block.relativePos().z() == 0) {
				collisionShulkers.add(null); // Placeholder to keep index alignment
				continue;
			}

			ShulkerEntity shulker = new ShulkerEntity(EntityType.SHULKER, world);

			// Position at block location (centered on block)
			Vec3d blockPos = block.relativePos().toVec3d();
			shulker.setPosition(
				this.getX() + blockPos.x,
				this.getY() + blockPos.y,
				this.getZ() + blockPos.z
			);

			// Make truly invisible with status effect (infinite duration)
			shulker.addStatusEffect(new StatusEffectInstance(
				StatusEffects.INVISIBILITY,
				Integer.MAX_VALUE, // Infinite duration
				0, // Amplifier
				false, // Ambient
				false, // Show particles
				false  // Show icon
			));

			// Disable AI and physics
			shulker.setAiDisabled(true);
			shulker.setNoGravity(true);
			shulker.setSilent(true);
			shulker.setInvulnerable(true);

			world.spawnEntity(shulker);
			collisionShulkers.add(shulker);
		}
	}

	/**
	 * Spawns an Interaction entity at the helm (origin) for mounting.
	 */
	private void spawnHelmInteraction(ServerWorld world) {
		helmInteraction = new InteractionEntity(EntityType.INTERACTION, world);
		// Entity is at helm corner, helm center is +0.5
		helmInteraction.setPosition(this.getX() + 0.5, this.getY(), this.getZ() + 0.5);
		// Set interaction area size (1 block wide, 2 blocks tall)
		helmInteraction.setInteractionWidth(1.0f);
		helmInteraction.setInteractionHeight(2.0f);
		helmInteraction.setResponse(true); // Send response to client
		world.spawnEntity(helmInteraction);

		// Create seat entity - an invisible pig that the player actually rides
		// Moving this entity moves the player with it
		spawnSeatEntity(world);
	}

	/**
	 * Creates the seat entity that players ride. Moving this moves the player.
	 */
	private void spawnSeatEntity(ServerWorld world) {
		// Use a pig as the seat - it positions passengers correctly
		net.minecraft.entity.passive.PigEntity pig = new net.minecraft.entity.passive.PigEntity(EntityType.PIG, world);
		pig.setPosition(this.getX() + 0.5, this.getY(), this.getZ() + 0.5);
		pig.setInvulnerable(true);
		pig.setNoGravity(true);
		pig.setAiDisabled(true);
		pig.setSilent(true);
		// Make invisible AND give it invisibility effect (belt and suspenders)
		pig.setInvisible(true);
		pig.addStatusEffect(new StatusEffectInstance(
			StatusEffects.INVISIBILITY,
			Integer.MAX_VALUE,
			0,
			false,  // ambient
			false,  // show particles
			false   // show icon
		));
		world.spawnEntity(pig);
		this.seatEntity = pig;
	}

	/**
	 * Updates all collision entity positions based on current ship position and rotation.
	 * Collision entities orbit around ship center matching display blocks.
	 */
	private void updateCollisionPositions() {
		float yawRadians = (float) Math.toRadians(visualYaw);

		for (int i = 0; i < blocks.size() && i < collisionShulkers.size(); i++) {
			ShipBlock block = blocks.get(i);
			ShulkerEntity shulker = collisionShulkers.get(i);

			if (shulker == null || shulker.isRemoved()) continue;

			// Rotate position around Y axis to match ship rotation
			Vec3d rotatedPos = block.relativePos().rotateY(yawRadians);
			shulker.setPosition(
				this.getX() + rotatedPos.x,
				this.getY() + rotatedPos.y,
				this.getZ() + rotatedPos.z
			);
		}

		// Update helm interaction position (at helm center = entity + 0.5)
		if (helmInteraction != null && !helmInteraction.isRemoved()) {
			helmInteraction.setPosition(this.getX() + 0.5, this.getY(), this.getZ() + 0.5);
		}
	}

	/**
	 * Updates collision positions relative to a logical base position (for entity orbit mode).
	 */
	private void updateCollisionPositionsWithOffset(double baseX, double baseZ, float yawRadians) {
		for (int i = 0; i < blocks.size() && i < collisionShulkers.size(); i++) {
			ShipBlock block = blocks.get(i);
			ShulkerEntity shulker = collisionShulkers.get(i);

			if (shulker == null || shulker.isRemoved()) continue;

			// Rotate position around Y axis to match ship rotation
			Vec3d rotatedPos = block.relativePos().rotateY(yawRadians);
			shulker.setPosition(
				baseX + rotatedPos.x,
				this.getY() + rotatedPos.y,
				baseZ + rotatedPos.z
			);
		}

		// Update helm interaction position (at logical helm center)
		if (helmInteraction != null && !helmInteraction.isRemoved()) {
			helmInteraction.setPosition(baseX + 0.5, this.getY(), baseZ + 0.5);
		}
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		// No tracked data needed for now
	}

	@Override
	public EntityType<?> getPolymerEntityType(PacketContext context) {
		return EntityType.PIG;
	}

	@Override
	public void modifyRawTrackedData(List<DataTracker.SerializedEntry<?>> data, ServerPlayerEntity player, boolean initial) {
		data.clear();
		// Index 0: Entity flags - 0x20 = invisible
		data.add(new DataTracker.SerializedEntry<>(0, TrackedDataHandlerRegistry.BYTE, (byte) 0x20));
		// Index 17: Pig flags - 0x01 = saddled
		data.add(new DataTracker.SerializedEntry<>(17, TrackedDataHandlerRegistry.BYTE, (byte) 0x01));
		// Index 18: Pig boost time - we repurpose this to send ship block count to clients
		// Client can read this to calculate dynamic camera distance
		data.add(new DataTracker.SerializedEntry<>(18, TrackedDataHandlerRegistry.INTEGER, blocks.size()));
	}

	@Override
	public boolean canHit() {
		return !this.isRemoved();
	}

	@Override
	public boolean isPushable() {
		return false; // Ship is solid like a structure
	}

	@Override
	public void pushAwayFrom(Entity entity) {
		// Don't get pushed by anything
	}

	@Override
	public boolean damage(ServerWorld world, DamageSource source, float amount) {
		if (this.isInvulnerable()) {
			return false;
		}

		if (!this.isRemoved()) {
			// Drop all blocks when destroyed
			for (ShipBlock block : blocks) {
				Item blockItem = block.blockState().getBlock().asItem();
				if (blockItem != null) {
					this.dropItem(world, blockItem);
				}
			}
			this.discard();
		}
		return true;
	}

	@Override
	public ActionResult interact(PlayerEntity player, Hand hand) {
		if (!this.getEntityWorld().isClient() && player instanceof ServerPlayerEntity) {
			System.out.println("[Ship] interact() called, docked=" + docked + ", passengers=" + this.getPassengerList().size());
			// Clicking the ship entity (helm area) starts driving
			if (this.getPassengerList().isEmpty()) {
				// Undock to start driving
				System.out.println("[Ship] Calling undock()...");
				undock();
				System.out.println("[Ship] After undock(), docked=" + docked);
				// Force mounting to bypass sneak check
				boolean mounted = player.startRiding(this, true, true);
				System.out.println("[Ship] startRiding result=" + mounted);
				return ActionResult.CONSUME;
			}
		}
		return ActionResult.PASS;
	}

	@Override
	protected void addPassenger(Entity passenger) {
		super.addPassenger(passenger);
		// Auto-undock when someone mounts (handles both helm click and direct pig mounting)
		if (docked && !this.getEntityWorld().isClient() && this.getEntityWorld() instanceof ServerWorld world) {
			System.out.println("[Ship] addPassenger() - checking grounding before undock");

			// Check for grounding - is the ship connected to land?
			Set<BlockPos> shipPositions = new HashSet<>(dockedBlockPositions);
			BlockPos helmPos = new BlockPos((int) baseX, (int) this.getY(), (int) baseZ);

			var groundingResult = justfatlard.big_boats.detection.FloodFillDetector.detectGrounding(
				world, shipPositions, blocks.size(), helmPos);

			if (groundingResult.isGrounded()) {
				// Ship is grounded - can't undock
				System.out.println("[Ship] GROUNDED - " + groundingResult.message());
				if (passenger instanceof ServerPlayerEntity player) {
					player.sendMessage(net.minecraft.text.Text.literal("§cShip is grounded! Disconnect from land to sail."), true);
				}
				// Kick the passenger off since we can't undock
				passenger.stopRiding();
				return;
			}

			// Check if there are blocks to absorb
			if (!groundingResult.connectedBlocks().isEmpty()) {
				System.out.println("[Ship] Absorbing " + groundingResult.connectedBlocks().size() + " connected blocks into ship");
				absorbBlocks(world, groundingResult.connectedBlocks());
				if (passenger instanceof ServerPlayerEntity player) {
					player.sendMessage(net.minecraft.text.Text.literal("§aAbsorbed " + groundingResult.connectedBlocks().size() + " blocks into ship!"), true);
				}
			}

			System.out.println("[Ship] addPassenger() - auto-undocking");
			undock();
		}
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		// Auto-dock when driver dismounts
		if (this.getPassengerList().isEmpty() && !this.getEntityWorld().isClient()) {
			dock();
		}
	}

	/**
	 * Finds which ship block the player is looking at.
	 * Returns the block index, or -1 if not looking at any ship block.
	 */
	private int findLookedAtBlock(PlayerEntity player) {
		Vec3d eyePos = player.getEyePos();
		Vec3d lookVec = player.getRotationVec(1.0f);
		double reach = 4.5; // Block interaction reach

		float yawRadians = (float) Math.toRadians(visualYaw);
		double cos = Math.cos(-yawRadians);
		double sin = Math.sin(-yawRadians);

		int closestIndex = -1;
		double closestDist = reach;

		for (int i = 0; i < blocks.size(); i++) {
			ShipBlock block = blocks.get(i);

			// Calculate world position of this block
			double relX = block.relativePos().x();
			double relY = block.relativePos().y();
			double relZ = block.relativePos().z();

			// Rotate relative position by ship yaw
			double rotatedX = relX * cos - relZ * sin;
			double rotatedZ = relX * sin + relZ * cos;

			double worldX = baseX + rotatedX;
			double worldY = this.getY() + relY;
			double worldZ = baseZ + rotatedZ;

			// Create bounding box for this block
			Box blockBox = new Box(worldX, worldY, worldZ, worldX + 1, worldY + 1, worldZ + 1);

			// Raycast to check if player is looking at this block
			java.util.Optional<Vec3d> hit = blockBox.raycast(eyePos, eyePos.add(lookVec.multiply(reach)));
			if (hit.isPresent()) {
				double dist = hit.get().distanceTo(eyePos);
				if (dist < closestDist) {
					closestDist = dist;
					closestIndex = i;
				}
			}
		}

		return closestIndex;
	}

	/**
	 * Tries to interact with a ship block (doors, trapdoors, fence gates).
	 */
	private ActionResult tryInteractWithBlock(PlayerEntity player, int blockIndex) {
		if (blockIndex < 0 || blockIndex >= blocks.size()) {
			return ActionResult.PASS;
		}

		ShipBlock shipBlock = blocks.get(blockIndex);
		BlockState state = shipBlock.blockState();

		// Handle doors
		if (state.getBlock() instanceof DoorBlock) {
			BlockState newState = state.cycle(Properties.OPEN);
			updateShipBlock(blockIndex, newState);

			// Play door sound
			World world = this.getEntityWorld();
			boolean isOpen = newState.get(Properties.OPEN);
			world.playSound(null, this.getX(), this.getY(), this.getZ(),
				isOpen ? SoundEvents.BLOCK_WOODEN_DOOR_OPEN : SoundEvents.BLOCK_WOODEN_DOOR_CLOSE,
				SoundCategory.BLOCKS, 1.0f, 1.0f);

			// Also toggle the other half of the door
			toggleDoorOtherHalf(blockIndex, state, isOpen);

			return ActionResult.SUCCESS;
		}

		// Handle trapdoors
		if (state.getBlock() instanceof TrapdoorBlock) {
			BlockState newState = state.cycle(Properties.OPEN);
			updateShipBlock(blockIndex, newState);

			World world = this.getEntityWorld();
			boolean isOpen = newState.get(Properties.OPEN);
			world.playSound(null, this.getX(), this.getY(), this.getZ(),
				isOpen ? SoundEvents.BLOCK_WOODEN_TRAPDOOR_OPEN : SoundEvents.BLOCK_WOODEN_TRAPDOOR_CLOSE,
				SoundCategory.BLOCKS, 1.0f, 1.0f);

			return ActionResult.SUCCESS;
		}

		// Handle fence gates
		if (state.getBlock() instanceof FenceGateBlock) {
			BlockState newState = state.cycle(Properties.OPEN);
			updateShipBlock(blockIndex, newState);

			World world = this.getEntityWorld();
			boolean isOpen = newState.get(Properties.OPEN);
			world.playSound(null, this.getX(), this.getY(), this.getZ(),
				isOpen ? SoundEvents.BLOCK_FENCE_GATE_OPEN : SoundEvents.BLOCK_FENCE_GATE_CLOSE,
				SoundCategory.BLOCKS, 1.0f, 1.0f);

			return ActionResult.SUCCESS;
		}

		return ActionResult.PASS;
	}

	/**
	 * Updates a ship block's state and refreshes the display.
	 */
	private void updateShipBlock(int index, BlockState newState) {
		if (index >= 0 && index < blocks.size()) {
			ShipBlock oldBlock = blocks.get(index);
			blocks.set(index, new ShipBlock(oldBlock.relativePos(), newState));

			if (elementHolder != null) {
				elementHolder.updateBlockState(index, newState);
			}
		}
	}

	/**
	 * Toggles the other half of a door to match.
	 */
	private void toggleDoorOtherHalf(int doorIndex, BlockState doorState, boolean isOpen) {
		DoubleBlockHalf half = doorState.get(Properties.DOUBLE_BLOCK_HALF);
		int yOffset = (half == DoubleBlockHalf.LOWER) ? 1 : -1;

		ShipBlock doorBlock = blocks.get(doorIndex);
		int targetY = doorBlock.relativePos().y() + yOffset;

		// Find the other half
		for (int i = 0; i < blocks.size(); i++) {
			if (i == doorIndex) continue;

			ShipBlock block = blocks.get(i);
			if (block.relativePos().x() == doorBlock.relativePos().x()
				&& block.relativePos().y() == targetY
				&& block.relativePos().z() == doorBlock.relativePos().z()
				&& block.blockState().getBlock() instanceof DoorBlock) {

				BlockState otherState = block.blockState().with(Properties.OPEN, isOpen);
				updateShipBlock(i, otherState);
				break;
			}
		}
	}

	@Override
	public void tick() {
		super.tick();

		// When docked, skip all physics and position updates
		if (docked) {
			return;
		}

		// Check if helm interaction was clicked
		checkHelmInteraction();

		// Simple floating physics - maintain target height
		double currentY = this.getY();
		double yDiff = targetFloatY - currentY;
		double yVelocity = 0;

		if (Math.abs(yDiff) > 0.01) {
			yVelocity = yDiff * 0.1;
			yVelocity = Math.max(-0.1, Math.min(0.1, yVelocity));
		}

		// Handle player controls - use direct position changes
		double moveX = 0;
		double moveZ = 0;

		// Get input from controlling passenger
		ServerPlayerEntity controller = null;
		if (this.hasPassengers() && this.getFirstPassenger() instanceof ServerPlayerEntity passenger) {
			controller = passenger;
		}

		if (controller != null) {
			// Get player movement input from mixin-captured storage
			PlayerInput input = PlayerInputStorage.getInput(controller);

			// Convert boolean input to directional values
			float forward = 0;
			float sideways = 0;
			if (input.forward()) forward += 1.0f;
			if (input.backward()) forward -= 1.0f;
			if (input.left()) sideways += 1.0f;
			if (input.right()) sideways -= 1.0f;

			// A/D rotates the ship - update both visual yaw and entity yaw
			// Entity yaw is needed so client positions passenger correctly
			float turnSpeed = 2.0f; // Degrees per tick
			if (sideways != 0) {
				visualYaw -= sideways * turnSpeed;
				// Also set entity yaw so client rotates passenger position
				this.setYaw(visualYaw);
			}

			// W/S applies acceleration in the forward/backward direction
			if (forward != 0) {
				float baseYaw = directionToYaw(helmFacing);
				float totalYawRadians = (float) Math.toRadians(baseYaw + visualYaw);
				// Apply acceleration in the direction the ship is facing
				velocityX += -Math.sin(totalYawRadians) * forward * ACCELERATION;
				velocityZ += Math.cos(totalYawRadians) * forward * ACCELERATION;
			}
		}

		// Apply drag (water resistance) - ship gradually slows down
		velocityX *= DRAG;
		velocityZ *= DRAG;

		// Clamp velocity to max speed
		double currentSpeed = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
		if (currentSpeed > MAX_SPEED) {
			double scale = MAX_SPEED / currentSpeed;
			velocityX *= scale;
			velocityZ *= scale;
		}

		// Stop completely if very slow (prevents endless tiny drifting)
		if (currentSpeed < 0.001) {
			velocityX = 0;
			velocityZ = 0;
		}

		// Apply velocity to position
		float yawRadians = (float) Math.toRadians(visualYaw);
		double newY = this.getY();

		// Check collision and apply movement
		if (velocityX != 0 && !checkShipCollision(velocityX, 0, 0, yawRadians)) {
			baseX += velocityX;
		} else if (velocityX != 0) {
			velocityX = 0; // Stop on collision
		}
		if (velocityZ != 0 && !checkShipCollision(0, 0, velocityZ, yawRadians)) {
			baseZ += velocityZ;
		} else if (velocityZ != 0) {
			velocityZ = 0; // Stop on collision
		}
		if (yVelocity != 0 && !checkShipCollision(0, yVelocity, 0, yawRadians)) {
			newY += yVelocity;
		}

		// Calculate entity orbit offset - entity orbits around the logical helm center
		// This makes the rider (attached to entity) orbit naturally
		// Player stands IN FRONT of the wheel (same direction as helm facing)
		double basePlayerOffsetX = 0;
		double basePlayerOffsetZ = 0;
		double playerOffset = 0.5; // Player offset from helm center

		switch (helmFacing) {
			case NORTH -> basePlayerOffsetZ = -playerOffset;  // Player north of helm (in front of wheel)
			case SOUTH -> basePlayerOffsetZ = playerOffset;   // Player south of helm
			case EAST -> basePlayerOffsetX = playerOffset;    // Player east of helm
			case WEST -> basePlayerOffsetX = -playerOffset;   // Player west of helm
			default -> {}
		}

		// Rotate the player offset by visualYaw
		double rotatedPlayerX = basePlayerOffsetX * Math.cos(yawRadians) - basePlayerOffsetZ * Math.sin(yawRadians);
		double rotatedPlayerZ = basePlayerOffsetX * Math.sin(yawRadians) + basePlayerOffsetZ * Math.cos(yawRadians);

		// Entity position = logical base + helm center offset + orbit offset
		double targetEntityX = baseX + 0.5 + rotatedPlayerX;
		double targetEntityZ = baseZ + 0.5 + rotatedPlayerZ;

		// Use velocity-based movement for smooth player interpolation
		double deltaX = targetEntityX - this.getX();
		double deltaY = newY - this.getY();
		double deltaZ = targetEntityZ - this.getZ();

		// Set velocity so client can interpolate smoothly
		this.setVelocity(deltaX, deltaY, deltaZ);
		// Apply movement using Minecraft's system for proper interpolation
		this.move(MovementType.SELF, this.getVelocity());

		// Update display entity positions - blocks need to compensate for entity orbit
		// Pass the orbit offset so blocks can adjust
		if (elementHolder != null) {
			elementHolder.updateRotationWithOffset(yawRadians, -rotatedPlayerX - 0.5, -rotatedPlayerZ - 0.5);
		}

		// Update collision entity positions (relative to logical helm position)
		updateCollisionPositionsWithOffset(baseX, baseZ, yawRadians);

		// Keep seat entity synced using velocity-based movement too
		if (seatEntity != null && !seatEntity.isRemoved()) {
			double seatDeltaX = targetEntityX - seatEntity.getX();
			double seatDeltaY = newY - seatEntity.getY();
			double seatDeltaZ = targetEntityZ - seatEntity.getZ();
			seatEntity.setVelocity(seatDeltaX, seatDeltaY, seatDeltaZ);
			seatEntity.move(MovementType.SELF, seatEntity.getVelocity());
		}
	}

	/**
	 * Calculates where the passenger should stand at the helm.
	 */
	private Vec3d getPassengerHelmPosition() {
		// Player stands BEHIND the wheel (opposite of helm facing)
		double baseOffsetX = 0;
		double baseOffsetZ = 0;
		double offset = 0.6; // Distance behind the wheel

		if (helmFacing != null) {
			switch (helmFacing) {
				case NORTH -> baseOffsetZ = offset;  // Wheel faces north, player stands south
				case SOUTH -> baseOffsetZ = -offset;
				case EAST -> baseOffsetX = -offset;
				case WEST -> baseOffsetX = offset;
				default -> {}
			}
		}

		// Rotate by ship's visual yaw
		float yawRadians = (float) Math.toRadians(visualYaw);
		double cos = Math.cos(yawRadians);
		double sin = Math.sin(yawRadians);
		double rotatedX = baseOffsetX * cos - baseOffsetZ * sin;
		double rotatedZ = baseOffsetX * sin + baseOffsetZ * cos;

		return new Vec3d(this.getX() + rotatedX, this.getY(), this.getZ() + rotatedZ);
	}

	/**
	 * Check if anyone interacted with the helm and mount them.
	 * InteractionEntity tracks interaction via age - we check if it was recently interacted with.
	 */
	private void checkHelmInteraction() {
		// The helm interaction will be handled via the boat entity's interact method
		// which checks if the player clicked near the helm position
	}

	@Override
	public void onPassengerLookAround(Entity passenger) {
		// Ship rotation is controlled by A/D, not player look direction
		// Player can look around freely without affecting ship heading
	}

	@Override
	protected void updatePassengerPosition(Entity passenger, PositionUpdater positionUpdater) {
		if (this.hasPassenger(passenger)) {
			// Position passenger at helm center
			double finalX = this.getX() + 0.5;
			double finalY = this.getY();
			double finalZ = this.getZ() + 0.5;

			positionUpdater.accept(passenger, finalX, finalY, finalZ);
		}
	}

	@Override
	public Vec3d updatePassengerForDismount(LivingEntity passenger) {
		Vec3d[] offsets = {
			new Vec3d(2, 0, 0),
			new Vec3d(-2, 0, 0),
			new Vec3d(0, 0, 2),
			new Vec3d(0, 0, -2)
		};

		World world = this.getEntityWorld();
		Vec3d thisPos = new Vec3d(this.getX(), this.getY(), this.getZ());
		Vec3d passengerPos = new Vec3d(passenger.getX(), passenger.getY(), passenger.getZ());

		for (Vec3d offset : offsets) {
			Vec3d pos = thisPos.add(offset);
			if (world.isSpaceEmpty(passenger, passenger.getBoundingBox().offset(pos.subtract(passengerPos)))) {
				return pos;
			}
		}

		return thisPos;
	}

	@Override
	public void readCustomData(ReadView view) {
		// Load ship blocks using codec
		this.blocks = view.read(BLOCKS_KEY, BLOCKS_CODEC).orElse(new ArrayList<>());

		// Load helm facing direction
		int facingOrdinal = view.getInt("helm_facing", Direction.NORTH.ordinal());
		this.helmFacing = Direction.values()[facingOrdinal];

		// Load target float height
		this.targetFloatY = view.getDouble("target_y", this.getY());

		// Load visual yaw (default to 0 for no rotation)
		this.visualYaw = view.getFloat("ship_yaw", 0f);
		// Sync entity yaw for proper passenger positioning
		this.setYaw(this.visualYaw);

		// Load logical base position
		this.baseX = view.getDouble("base_x", this.getX());
		this.baseZ = view.getDouble("base_z", this.getZ());

		// Load docked state (default to true - ships load with real blocks in world)
		this.docked = view.getBoolean("docked", true);

		// Re-initialize display entities after loading (yaw is already set, so they'll be created correctly)
		if (!blocks.isEmpty()) {
			initializeElementHolder();
			// Configure visibility based on docked state
			if (docked) {
				// Hide virtual display (real blocks are visible)
				if (elementHolder != null) {
					elementHolder.setVisible(false);
				}
				// Hide collision shulkers (real blocks have collision)
				for (ShulkerEntity shulker : collisionShulkers) {
					if (shulker != null) {
						shulker.setInvisible(true);
						shulker.setNoGravity(true);
						shulker.setAiDisabled(true);
					}
				}
			}
		}
	}

	@Override
	public void writeCustomData(WriteView view) {
		// Save ship blocks using codec
		view.put(BLOCKS_KEY, BLOCKS_CODEC, blocks);

		// Save helm facing direction
		view.putInt("helm_facing", helmFacing.ordinal());

		// Save target float height
		view.putDouble("target_y", targetFloatY);

		// Save visual yaw for rotation persistence
		view.putFloat("ship_yaw", visualYaw);

		// Save logical base position
		view.putDouble("base_x", baseX);
		view.putDouble("base_z", baseZ);

		// Save docked state
		view.putBoolean("docked", docked);
	}

	@Override
	public ItemStack getPickBlockStack() {
		if (!blocks.isEmpty()) {
			return new ItemStack(blocks.get(0).blockState().getBlock().asItem());
		}
		return ItemStack.EMPTY;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengerList().isEmpty();
	}

	@Override
	public LivingEntity getControllingPassenger() {
		Entity entity = this.getFirstPassenger();
		return entity instanceof LivingEntity living ? living : null;
	}

	/**
	 * Converts a Direction to yaw degrees for ship movement.
	 * W moves toward the helm's facing direction.
	 */
	private static float directionToYaw(Direction dir) {
		return switch (dir) {
			case SOUTH -> 180f;
			case WEST -> -90f;  // Swapped for E/W fix
			case NORTH -> 0f;
			case EAST -> 90f;   // Swapped for E/W fix
			default -> 0f;
		};
	}

	/**
	 * Converts ship yaw (in degrees) to a BlockRotation for rotating block states.
	 */
	private static net.minecraft.util.BlockRotation yawToBlockRotation(float yaw) {
		// Normalize yaw to 0-360 range
		float normalizedYaw = ((yaw % 360) + 360) % 360;
		// Round to nearest 90 degrees
		int rotation = Math.round(normalizedYaw / 90) % 4;
		return switch (rotation) {
			case 0 -> net.minecraft.util.BlockRotation.NONE;
			case 1 -> net.minecraft.util.BlockRotation.CLOCKWISE_90;
			case 2 -> net.minecraft.util.BlockRotation.CLOCKWISE_180;
			case 3 -> net.minecraft.util.BlockRotation.COUNTERCLOCKWISE_90;
			default -> net.minecraft.util.BlockRotation.NONE;
		};
	}

	/**
	 * Checks if moving the ship would cause any block to collide with world terrain.
	 * Returns true if collision detected. Breaks certain fragile blocks instead of colliding.
	 * Uses baseX/baseZ (logical helm position) since blocks are positioned relative to helm, not entity.
	 */
	private boolean checkShipCollision(double deltaX, double deltaY, double deltaZ, float yawRadians) {
		World world = this.getEntityWorld();
		// Use baseX/baseZ as reference - blocks are relative to helm corner, not entity position
		double newX = baseX + deltaX;
		double newY = this.getY() + deltaY;
		double newZ = baseZ + deltaZ;

		// Check corners of each ship block to prevent overlap
		double[] offsets = {-0.49, 0.49}; // Check near edges of each block

		for (ShipBlock block : blocks) {
			// Calculate rotated block position
			Vec3d rotatedPos = block.relativePos().rotateY(yawRadians);

			// Check multiple points around the block (corners)
			for (double ox : offsets) {
				for (double oy : offsets) {
					for (double oz : offsets) {
						int blockX = (int) Math.floor(newX + rotatedPos.x + ox);
						int blockY = (int) Math.floor(newY + rotatedPos.y + oy);
						int blockZ = (int) Math.floor(newZ + rotatedPos.z + oz);

						net.minecraft.util.math.BlockPos worldPos = new net.minecraft.util.math.BlockPos(blockX, blockY, blockZ);
						net.minecraft.block.BlockState worldBlock = world.getBlockState(worldPos);

						// Skip air and liquids
						if (worldBlock.isAir() || worldBlock.isLiquid()) {
							continue;
						}

						// Check if this is a breakable block (seagrass, kelp, lily pads, etc.)
						if (isBreakableByShip(worldBlock) && world instanceof ServerWorld serverWorld) {
							serverWorld.breakBlock(worldPos, true); // Break and drop items
							continue;
						}

						// Solid block - collision detected
						return true;
					}
				}
			}
		}
		return false; // No collision
	}

	/**
	 * Checks if a block should be broken by ship collision instead of blocking movement.
	 */
	private boolean isBreakableByShip(net.minecraft.block.BlockState state) {
		net.minecraft.block.Block block = state.getBlock();

		// Check for common fragile/plant blocks ships should break through
		if (block instanceof net.minecraft.block.SeagrassBlock
			|| block instanceof net.minecraft.block.TallSeagrassBlock
			|| block instanceof net.minecraft.block.KelpBlock
			|| block instanceof net.minecraft.block.KelpPlantBlock
			|| block instanceof net.minecraft.block.LilyPadBlock
			|| block instanceof net.minecraft.block.TallPlantBlock
			|| block instanceof net.minecraft.block.FlowerBlock
			|| block instanceof net.minecraft.block.TallFlowerBlock
			|| block instanceof net.minecraft.block.SugarCaneBlock
			|| block instanceof net.minecraft.block.VineBlock
			|| block instanceof net.minecraft.block.SnowBlock
			|| block instanceof net.minecraft.block.CobwebBlock) {
			return true;
		}

		// Also break blocks that are instantly breakable (hardness 0)
		return state.getHardness(null, null) == 0.0f && !state.isAir();
	}

	public List<ShipBlock> getBlocks() {
		return blocks;
	}

	public int getBlockCount() {
		return blocks.size();
	}

	public double getTargetFloatY() {
		return targetFloatY;
	}

	/**
	 * Checks if the given entity is this ship's helm interaction entity.
	 */
	public boolean isHelmInteraction(Entity entity) {
		return helmInteraction != null && helmInteraction.equals(entity);
	}

	@Override
	public void remove(RemovalReason reason) {
		super.remove(reason);
		if (attachment != null) {
			attachment.destroy();
			attachment = null;
		}

		// Clean up collision shulkers
		for (ShulkerEntity shulker : collisionShulkers) {
			if (shulker != null && !shulker.isRemoved()) {
				shulker.discard();
			}
		}
		collisionShulkers.clear();

		// Clean up helm interaction
		if (helmInteraction != null && !helmInteraction.isRemoved()) {
			helmInteraction.discard();
			helmInteraction = null;
		}

		// Clean up seat entity
		if (seatEntity != null && !seatEntity.isRemoved()) {
			seatEntity.discard();
			seatEntity = null;
		}
	}
}
