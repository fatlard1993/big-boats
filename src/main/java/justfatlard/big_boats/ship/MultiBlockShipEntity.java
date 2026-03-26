package justfatlard.big_boats.ship;

import com.mojang.serialization.Codec;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import justfatlard.big_boats.BigBoats;
import justfatlard.big_boats.detection.FloodFillDetector;
import justfatlard.big_boats.util.PlayerInputStorage;
import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A multi-block ship entity that can be driven by players.
 * Contains multiple blocks detected via flood-fill from the helm.
 *
 * <h2>Dual coordinate system</h2>
 * This entity maintains two separate positions:
 * <ul>
 *   <li><b>helmX/helmZ</b> — the world position of the helm block's corner. All ship blocks,
 *       collision, display, and lighting are positioned relative to this. This is the logical
 *       center of the ship.</li>
 *   <li><b>Entity position (getX/getZ)</b> — orbits around helmX/helmZ based on helm facing
 *       and current yaw. This is where the passenger sits. Computed each tick as:
 *       {@code entityX = helmX + 0.5 + rotateXZ(helmSeatOffset, yawRadians).x}</li>
 * </ul>
 * Use helmX/helmZ for anything that positions ship elements. Use entity position only
 * for passenger placement and Minecraft's entity system.
 *
 * <h2>Polymer disguise</h2>
 * Ships appear to vanilla clients as invisible saddled pigs. Block count is transmitted
 * via the pig's boost time tracked data (index 16). The client reads this in CameraMixin
 * via PigEntityAccessor to scale camera distance. See {@link #modifyRawTrackedData} for
 * the encoding, and CameraMixin for the decoding.
 *
 * <h2>Delegates</h2>
 * All delegates receive a {@link ShipPose} to transform ship-local coordinates
 * to world coordinates, eliminating loose parameter passing.
 * <ul>
 *   <li>{@link ShipPhysics} — velocity and movement calculations</li>
 *   <li>{@link ShipCollision} — hull detection and collision checking</li>
 *   <li>{@link ShipCollisionEntities} — collision shulkers and helm interaction entity</li>
 *   <li>{@link ShipLighting} — light source management</li>
 *   <li>{@link ShipElementHolder} — virtual display entities for block rendering</li>
 *   <li>{@link ShipInteraction} — stateless helper for door/trapdoor/fence gate interaction</li>
 *   <li>{@link ShipDecoration} — item frame/painting snapshots for dock/undock lifecycle</li>
 * </ul>
 */
public class MultiBlockShipEntity extends Entity implements PolymerEntity {
	private static final Logger LOGGER = LoggerFactory.getLogger(MultiBlockShipEntity.class);
	private static final Codec<List<ShipBlock>> BLOCKS_CODEC = ShipBlock.CODEC.listOf();
	private static final Codec<List<BlockPos>> BLOCK_POS_LIST_CODEC = BlockPos.CODEC.listOf();
	private static final Codec<List<UUID>> UUID_LIST_CODEC = net.minecraft.util.Uuids.CODEC.listOf();
	private static final String BLOCKS_KEY = "ship_blocks";

	// PigEntity tracked data indices (MC 1.21.x layout)
	// See class javadoc for the Polymer disguise explanation
	private static final int ENTITY_FLAGS_INDEX = 0;
	private static final byte INVISIBLE_FLAG = 0x20;
	private static final int PIG_BOOST_TIME_INDEX = 16;
	private static final int PIG_SADDLED_INDEX = 17;
	private static final byte SADDLED_FLAG = 0x01;

	/**
	 * Ship lifecycle states. Transitions:
	 * <pre>
	 *   DOCKED → UNDOCKING → SAILING   (player mounts, blocks removed from world)
	 *   SAILING → DOCKING → DOCKED     (player dismounts, blocks placed back)
	 * </pre>
	 *
	 * UNDOCKING and DOCKING are reentry guards, not durations. They exist because
	 * Minecraft's passenger system calls addPassenger/removePassenger during state
	 * transitions, which would re-trigger undock/dock without these locks.
	 * dock() and undock() use try-finally to guarantee the ship never gets stuck
	 * in a transient state.
	 */
	public enum ShipState {
		DOCKED, UNDOCKING, SAILING, DOCKING
	}

	// IMMUTABLE SNAPSHOTS: writeCustomData reads from the chunk-saving thread while tick/rescan
	// writes on the server thread. Every assignment creates a new unmodifiable list via List.copyOf().
	// Build the complete new list in a local ArrayList, then assign blocks = List.copyOf(local).
	// List.copyOf() structurally prevents in-place mutation — no discipline required.
	private volatile List<ShipBlock> blocks = List.of();
	private ShipElementHolder elementHolder;
	private EntityAttachment attachment;

	// Helper components
	private final ShipPhysics physics = new ShipPhysics();
	private final ShipCollision collision = new ShipCollision();
	private final ShipLighting lighting = new ShipLighting();

	// Collision and interaction entity lifecycle
	private final ShipCollisionEntities collisionEntities = new ShipCollisionEntities();

	// Dock/undock world mutations (block placement/removal, decorations)
	private final ShipDocking docking = new ShipDocking();

	// Float height target — tracks water surface when over water, holds last-known value over land.
	// Volatile: read by writeCustomData on chunk-saving thread.
	private volatile double floatTargetY;

	// The direction the helm faces (where the wheel is visible from)
	private Direction helmFacing = Direction.NORTH;

	// Ship rotation in radians (converted to/from degrees only at serialization boundaries).
	// Volatile: read by writeCustomData on chunk-saving thread.
	private volatile float yawRadians = 0;

	// See class javadoc for the dual coordinate system explanation.
	// Volatile: read by writeCustomData on chunk-saving thread.
	private volatile double helmX, helmZ;

	// Ship lifecycle state
	// Volatile: read by writeCustomData on chunk-saving thread.
	private volatile ShipState state = ShipState.DOCKED;


	// Tick-spreading: display element updates
	private float lastDisplayYaw = 0;

	// Tracks blocks rejected by capacity cap during last rescan (for player feedback)
	private int lastRescanRejectedBlocks = 0;

	// Cached hull world positions for ship-to-ship collision.
	// Computed once per tick when sailing, read by other ships' gatherNearbyShipHullPositions.
	// Eliminates O(n) recomputation per querying ship.
	private Set<BlockPos> cachedHullPositions = Set.of();
	private int cachedHullTick = -1;

	// Water surface tracking
	private int ticksSinceWaterCheck = 0;

	// Ship name (set via named christening bottle)
	private String shipName = null;

	// Decorations and docked positions are managed by ShipDocking.

	public MultiBlockShipEntity(EntityType<?> entityType, World world) {
		super(entityType, world);
		this.setInvulnerable(true);
	}

	public MultiBlockShipEntity(World world, double x, double y, double z, List<ShipBlock> blocks, Direction helmFacing) {
		this(BigBoats.MULTI_BLOCK_SHIP_ENTITY_TYPE, world);
		this.setPosition(x, y, z);
		this.blocks = List.copyOf(blocks);
		this.floatTargetY = y;
		this.helmFacing = helmFacing;

		// Track logical base position (helm corner)
		this.helmX = x;
		this.helmZ = z;

		// Compute collision optimization data
		collision.computeHullBlocks(blocks);

		// Ship spawns with yaw=0 (no rotation)
		this.setYaw(0);
		this.yawRadians = 0;

		LOGGER.debug("Ship created at ({}, {}, {}) with {} blocks, facing {}", x, y, z, blocks.size(), helmFacing);

		initializeElementHolder();
	}

	/**
	 * Initializes the ship after christening.
	 * Ship starts DOCKED with real blocks still in place - undocks when player mounts.
	 */
	public void initializeShip(BlockPos helmPos) {
		if (!(this.getEntityWorld() instanceof ServerWorld)) {
			return;
		}

		state = ShipState.DOCKED;
		docking.recordPositions(blocks, helmPos);

		// Hide virtual display (real blocks are visible)
		if (elementHolder != null) {
			elementHolder.setVisible(false);
		}
	}

	/**
	 * Docks the ship - places real blocks at current position for full interaction.
	 * Called when no one is driving. Snaps to nearest cardinal direction.
	 */
	public void dock() {
		if (state == ShipState.DOCKED || state == ShipState.DOCKING
				|| !(this.getEntityWorld() instanceof ServerWorld world)) {
			return;
		}

		state = ShipState.DOCKING;

		try {
			dockInner(world);
		} catch (RuntimeException e) {
			// Broad catch intentional: dock involves world mutations (block placement, entity spawning,
			// NBT restoration) that can fail in many ways. The ship MUST reach DOCKED state regardless
			// — a stuck DOCKING state strands the entity permanently.
			LOGGER.error("Exception during dock — forcing DOCKED state to prevent stranding", e);
		} finally {
			state = ShipState.DOCKED;
		}
	}

	private void dockInner(ServerWorld world) {
		LOGGER.debug("Docking ship ({} blocks) at ({}, {}, {})", blocks.size(), helmX, this.getY(), helmZ);

		lighting.remove(world);
		physics.reset();

		// Snap rotation and position to block grid
		float yawDegrees = (float) Math.toDegrees(yawRadians);
		ShipBlockUtils.SnappedRotation snap = ShipBlockUtils.snappedRotation(yawDegrees);
		yawRadians = snap.yawRadians();
		this.setYaw(snap.yawDegrees());
		helmX = Math.round(helmX);
		helmZ = Math.round(helmZ);

		// Place blocks and restore decorations
		ShipDocking.DockStats stats = docking.placeBlocks(world, blocks, helmX, this.getY(), helmZ, snap);
		docking.restoreDecorations(world, helmX, this.getY(), helmZ, snap);

		// Notify nearby players of docking issues
		if (stats.obstructed() > 0 || stats.lostBlockEntities() > 0) {
			Box notifyArea = new Box(helmX - 10, this.getY() - 5, helmZ - 10,
				helmX + 10, this.getY() + 10, helmZ + 10);
			for (PlayerEntity player : world.getEntitiesByClass(PlayerEntity.class, notifyArea, p -> true)) {
				if (player instanceof ServerPlayerEntity serverPlayer) {
					if (stats.obstructed() > 0) {
						serverPlayer.sendMessage(
							Text.translatable("big-boats.ship.obstructed", stats.obstructed())
								.formatted(Formatting.YELLOW), true);
					}
					if (stats.lostBlockEntities() > 0) {
						serverPlayer.sendMessage(
							Text.translatable("big-boats.ship.lost_contents", stats.lostBlockEntities())
								.formatted(Formatting.RED), true);
					}
				}
			}
		}

		if (elementHolder != null) {
			elementHolder.setVisible(false);
		}
		collisionEntities.discardAll();
	}

	/**
	 * Undocks the ship - removes real blocks, enables virtual display for movement.
	 * Called when player starts driving.
	 *
	 * <p>Two failure modes with different recovery:
	 * <ul>
	 *   <li>Pre-mutation failure (rescan): blocks are still in the world → abort to DOCKED</li>
	 *   <li>Post-mutation failure (after removeBlocks): blocks are gone → force-dock to restore</li>
	 * </ul>
	 */
	public void undock() {
		if (state != ShipState.DOCKED || !(this.getEntityWorld() instanceof ServerWorld world)) {
			return;
		}

		state = ShipState.UNDOCKING;

		try {
			if (!undockInner(world)) {
				// Rescan or validation failed — blocks are still in the world, just abort.
				state = ShipState.DOCKED;
				return;
			}
		} catch (RuntimeException e) {
			// Exception after removeBlocks — blocks have been removed from the world.
			// Force-dock to place them back. Losing blocks is worse than catching broadly.
			LOGGER.error("Exception during undock — force-docking to restore blocks", e);
			state = ShipState.SAILING;
			dock();
			return;
		}

		state = ShipState.SAILING;
	}

	/**
	 * @return true if undock completed, false if aborted safely (blocks still in world)
	 * @throws RuntimeException if failure occurs after blocks were removed from world
	 */
	private boolean undockInner(ServerWorld world) {
		LOGGER.debug("Undocking ship ({} blocks)", blocks.size());

		// Re-detect ship structure to include/remove blocks changed while docked.
		// If rescan fails, return false — blocks are still in the world, safe to abort.
		BlockPos helmWorldPos = BlockPos.ofFloored(helmX, this.getY(), helmZ);
		if (!rescanShipStructure(world, helmWorldPos)) {
			LOGGER.warn("Aborting undock — rescan failed, ship structure may be missing");
			for (Entity p : this.getPassengerList()) {
				if (p instanceof ServerPlayerEntity sp) {
					sp.sendMessage(
						Text.translatable("big-boats.ship.structure_damaged").formatted(Formatting.RED), true);
				}
				p.stopRiding();
			}
			return false;
		}

		collision.computeHullBlocks(blocks);

		// Build mapping from world position to ShipBlock index
		float yawDegrees = (float) Math.toDegrees(yawRadians);
		ShipBlockUtils.SnappedRotation snap = ShipBlockUtils.snappedRotation(yawDegrees);
		Map<BlockPos, Integer> posToBlockIndex = new HashMap<>();
		for (int i = 0; i < blocks.size(); i++) {
			BlockPos worldPos = ShipBlockUtils.relativeToWorld(
				blocks.get(i).relativePos(), helmX, this.getY(), helmZ, snap.cos(), snap.sin());
			posToBlockIndex.put(worldPos, i);
		}

		// Capture decorations, snapshot block entities, remove blocks from world
		blocks = docking.removeBlocks(world, blocks, helmX, this.getY(), helmZ, snap, posToBlockIndex);

		// Position entity at passenger seat and sync display
		Vec3d seatWorld = computeSeatWorldPos();
		this.setPosition(seatWorld.x, this.getY(), seatWorld.z);

		if (elementHolder != null) {
			elementHolder.setVisible(true);
			Vec3d displayOffset = computeDisplayOrbitOffset();
			elementHolder.updateRotationWithOffset(yawRadians, (float) displayOffset.x, (float) displayOffset.z);
		}

		ShipPose currentPose = pose();
		collisionEntities.spawnAll(world, blocks, currentPose, collision.getHullBlocks());
		collisionEntities.updatePositions(currentPose);
		collisionEntities.syncTrackingState(helmX, helmZ, yawRadians);
		lastDisplayYaw = yawRadians;

		lighting.detectFromBlocks(blocks);
		lighting.spawnLightBlocks(world, currentPose);

		LOGGER.debug("Undock complete: {} blocks, lighting={}", blocks.size(), lighting.hasLightSources());
		return true;
	}

	/**
	 * Returns the current ship pose (helm position + rotation).
	 * Used by delegates to transform ship-local coordinates to world coordinates.
	 */
	public ShipPose pose() {
		return new ShipPose(helmX, this.getY(), helmZ, yawRadians);
	}

	/**
	 * Computes the world position of the passenger seat.
	 * The entity orbits the helm based on helm facing and current yaw:
	 * seat = helmCenter + rotateXZ(helmSeatOffset, yawRadians)
	 */
	private Vec3d computeSeatWorldPos() {
		Vec3d seatOffset = ShipBlockUtils.helmSeatOffset(helmFacing);
		Vec3d rotated = ShipBlockUtils.rotateXZ(seatOffset.x, seatOffset.z, yawRadians);
		return new Vec3d(helmX + 0.5 + rotated.x, this.getY(), helmZ + 0.5 + rotated.z);
	}

	/**
	 * Computes the display offset that compensates for entity orbit.
	 * The entity sits at (helmX + 0.5 + rotatedOffset), but display elements are
	 * positioned relative to the entity. To keep blocks anchored to the helm,
	 * the display needs the inverse of the seat offset.
	 */
	private Vec3d computeDisplayOrbitOffset() {
		Vec3d seatOffset = ShipBlockUtils.helmSeatOffset(helmFacing);
		Vec3d rotated = ShipBlockUtils.rotateXZ(seatOffset.x, seatOffset.z, yawRadians);
		return new Vec3d(-rotated.x - 0.5, 0, -rotated.z - 0.5);
	}

	public boolean isDocked() {
		return state == ShipState.DOCKED;
	}

	public ShipState getShipState() {
		return state;
	}

	/**
	 * Re-scans the ship structure from the helm position.
	 * @return true if rescan succeeded, false if detection failed (ship structure may be corrupted)
	 */
	private boolean rescanShipStructure(ServerWorld world, BlockPos helmWorldPos) {
		float yawDegrees = (float) Math.toDegrees(yawRadians);
		ShipBlockUtils.SnappedRotation snap = ShipBlockUtils.snappedRotation(yawDegrees);
		int cos = snap.cos();
		int sin = snap.sin();

		// Build set of world positions for current blocks and reverse lookup
		Set<BlockPos> currentWorldPositions = new HashSet<>();
		Map<BlockPos, ShipBlock> worldPosToBlock = new HashMap<>();
		Map<RelativeBlockPos, BlockPos> relToWorldPos = new HashMap<>();
		for (ShipBlock block : blocks) {
			BlockPos worldPos = ShipBlockUtils.relativeToWorld(block.relativePos(), helmX, this.getY(), helmZ, cos, sin);
			currentWorldPositions.add(worldPos);
			worldPosToBlock.put(worldPos, block);
			relToWorldPos.put(block.relativePos(), worldPos);
		}

		var detectionResult = FloodFillDetector.detect(world, helmWorldPos);
		if (!(detectionResult instanceof justfatlard.big_boats.detection.DetectionResult.Success successResult)) {
			String reason = detectionResult instanceof justfatlard.big_boats.detection.DetectionResult.Failure failure
				? failure.message() : "unknown";
			LOGGER.warn("Rescan detection failed: {} — ship structure may be corrupt", reason);
			return false;
		}

		// Build set of detected world positions
		Set<BlockPos> detectedWorldPositions = new HashSet<>();
		for (ShipBlock detectedBlock : successResult.blocks()) {
			detectedWorldPositions.add(helmWorldPos.add(
				detectedBlock.relativePos().x(),
				detectedBlock.relativePos().y(),
				detectedBlock.relativePos().z()
			));
		}

		// Remove blocks that are no longer in the detected structure (broken while docked)
		int removedCount = 0;
		List<ShipBlock> survivingBlocks = new ArrayList<>();
		for (ShipBlock block : blocks) {
			BlockPos worldPos = relToWorldPos.get(block.relativePos());

			if (worldPos != null && detectedWorldPositions.contains(worldPos)) {
				survivingBlocks.add(block);
			} else if (block.isHelm()) {
				survivingBlocks.add(block);
			} else {
				removedCount++;
			}
		}
		// Guard: if only the helm survived, the ship is too damaged to sail
		if (survivingBlocks.size() < ShipConfig.MIN_BLOCKS) {
			LOGGER.warn("Ship reduced to {} blocks (minimum {}) — aborting rescan",
				survivingBlocks.size(), ShipConfig.MIN_BLOCKS);
			return false;
		}

		if (removedCount > 0) {
			Set<RelativeBlockPos> survivingPositions = new HashSet<>();
			for (ShipBlock b : survivingBlocks) survivingPositions.add(b.relativePos());
			collisionEntities.removeStaleShulkers(survivingPositions);
			LOGGER.debug("Removed {} missing blocks during rescan", removedCount);
		}

		// Find newly added blocks
		List<ShipBlock> newBlocks = new ArrayList<>();
		for (ShipBlock detectedBlock : successResult.blocks()) {
			BlockPos detectedWorldPos = helmWorldPos.add(
				detectedBlock.relativePos().x(),
				detectedBlock.relativePos().y(),
				detectedBlock.relativePos().z()
			);

			if (!currentWorldPositions.contains(detectedWorldPos)) {
				int worldDeltaX = detectedWorldPos.getX() - (int) Math.floor(helmX);
				int worldDeltaY = detectedWorldPos.getY() - (int) Math.floor(this.getY());
				int worldDeltaZ = detectedWorldPos.getZ() - (int) Math.floor(helmZ);

				RelativeBlockPos newRelPos =
					ShipBlockUtils.worldToRelative(worldDeltaX, worldDeltaY, worldDeltaZ, cos, sin);

				ShipBlock newBlock = new ShipBlock(newRelPos, detectedBlock.blockState(), detectedBlock.blockEntityData());
				newBlocks.add(newBlock);
			}
		}

		// Cap absorbed blocks to prevent exceeding MAX_BLOCKS
		int availableCapacity = ShipConfig.MAX_BLOCKS - survivingBlocks.size();
		lastRescanRejectedBlocks = 0;
		if (newBlocks.size() > availableCapacity) {
			lastRescanRejectedBlocks = newBlocks.size() - availableCapacity;
			LOGGER.debug("Capping absorbed blocks: {} available, {} detected, {} rejected",
				availableCapacity, newBlocks.size(), lastRescanRejectedBlocks);
			newBlocks = newBlocks.subList(0, availableCapacity);
		}

		if (!newBlocks.isEmpty()) {
			List<BlockPos> newPositions = new ArrayList<>();
			for (ShipBlock block : newBlocks) {
				newPositions.add(ShipBlockUtils.relativeToWorld(
					block.relativePos(), helmX, this.getY(), helmZ, cos, sin));
			}
			docking.addDockedPositions(newPositions);
			LOGGER.debug("Added {} new blocks during rescan", newBlocks.size());
		}

		// Assign as immutable snapshot — safe for concurrent read by chunk-saving thread
		if (removedCount > 0 || !newBlocks.isEmpty()) {
			List<ShipBlock> updatedBlocks = new ArrayList<>(survivingBlocks);
			updatedBlocks.addAll(newBlocks);
			blocks = List.copyOf(updatedBlocks);

			if (elementHolder != null) {
				elementHolder.rebuildFromBlocks(blocks, yawRadians);
			}
		}

		return true;
	}

	private void initializeElementHolder() {
		if (this.elementHolder != null) {
			return;
		}

		if (this.getEntityWorld() instanceof ServerWorld && !blocks.isEmpty()) {
			this.elementHolder = new ShipElementHolder(new ArrayList<>(blocks), yawRadians);
			this.attachment = EntityAttachment.ofTicking(this.elementHolder, this);
			// Collision entities are spawned in undock(), not here.
			// Docked ships use real blocks for collision; spawning shulkers here
			// would waste server entity budget on idle docked ships.
		}
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
	}

	@Override
	public EntityType<?> getPolymerEntityType(PacketContext context) {
		return EntityType.PIG;
	}

	/**
	 * Overrides Polymer's tracked data to make this entity appear as an invisible saddled pig.
	 * The pig disguise is necessary because Polymer maps server entities to vanilla entity types.
	 *
	 * Index layout (PigEntity tracked data, MC 1.21.x):
	 *   0  = Entity flags byte — 0x20 sets the invisible flag
	 *   16 = PigEntity BOOST_TIME — repurposed to transmit ship block count to client
	 *   17 = PigEntity SADDLED byte — 0x01 so the pig appears saddled (rideable)
	 *
	 * The client reads index 16 via PigEntityAccessor in CameraMixin to scale camera distance.
	 * If Minecraft changes PigEntity's tracked data layout, these indices MUST be updated
	 * along with PigEntityAccessor and CameraMixin.
	 */
	@Override
	public void modifyRawTrackedData(List<DataTracker.SerializedEntry<?>> data, ServerPlayerEntity player, boolean initial) {
		data.clear();
		data.add(new DataTracker.SerializedEntry<>(ENTITY_FLAGS_INDEX, TrackedDataHandlerRegistry.BYTE, INVISIBLE_FLAG));
		data.add(new DataTracker.SerializedEntry<>(PIG_BOOST_TIME_INDEX, TrackedDataHandlerRegistry.INTEGER, blocks.size()));
		data.add(new DataTracker.SerializedEntry<>(PIG_SADDLED_INDEX, TrackedDataHandlerRegistry.BYTE, SADDLED_FLAG));
	}

	@Override
	public boolean canHit() {
		return !this.isRemoved();
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public void pushAwayFrom(Entity entity) {
		// Don't get pushed
	}

	@Override
	public boolean damage(ServerWorld world, DamageSource source, float amount) {
		// Ships are invulnerable — can only be removed via /kill which triggers remove() → dock()
		return false;
	}

	@Override
	public ActionResult interact(PlayerEntity player, Hand hand) {
		if (!this.getEntityWorld().isClient() && player instanceof ServerPlayerEntity serverPlayer) {
			return tryMount(serverPlayer) ? ActionResult.CONSUME : ActionResult.PASS;
		}
		return ActionResult.PASS;
	}

	/**
	 * Single entry point for mounting the ship. All mount paths converge here.
	 * Returns true if the player was successfully mounted.
	 *
	 * Grounding check runs HERE (before startRiding) rather than in addPassenger,
	 * to avoid calling stopRiding() inside addPassenger() which is recursive and
	 * can cause inconsistent passenger state.
	 */
	public boolean tryMount(ServerPlayerEntity player) {
		if (!this.getPassengerList().isEmpty()) {
			player.sendMessage(
				Text.translatable("big-boats.ship.occupied").formatted(Formatting.YELLOW), true);
			return false;
		}

		if (state == ShipState.DOCKED && this.getEntityWorld() instanceof ServerWorld world) {
			Set<BlockPos> shipPositions = new HashSet<>(docking.getDockedBlockPositions());
			BlockPos helmPos = BlockPos.ofFloored(helmX, this.getY(), helmZ);

			var groundingResult = FloodFillDetector.detectGrounding(
				world, shipPositions, blocks.size(), helmPos);

			if (!groundingResult.canUndock()) {
				player.sendMessage(
					Text.translatable("big-boats.ship.grounded").formatted(Formatting.RED), true);
				return false;
			}
		}

		return player.startRiding(this);
	}

	@Override
	protected void addPassenger(Entity passenger) {
		super.addPassenger(passenger);

		if (state == ShipState.DOCKED && !this.getEntityWorld().isClient()
				&& this.getEntityWorld() instanceof ServerWorld) {
			// Grounding already checked in tryMount(). Proceed with undock.
			int blocksBefore = blocks.size();
			undock();
			int blocksAfter = blocks.size();

			if (passenger instanceof ServerPlayerEntity player) {
				int absorbed = blocksAfter - blocksBefore;
				if (absorbed > 0) {
					player.sendMessage(
						Text.translatable("big-boats.ship.absorbed", absorbed).formatted(Formatting.GREEN), true);
				}
				if (lastRescanRejectedBlocks > 0) {
					player.sendMessage(
						Text.translatable("big-boats.ship.absorption_capped", lastRescanRejectedBlocks, ShipConfig.MAX_BLOCKS)
							.formatted(Formatting.YELLOW), true);
				}
				Text pilotMessage = hasShipName()
					? Text.translatable("big-boats.ship.piloting_named", shipName).formatted(Formatting.GOLD)
					: Text.translatable("big-boats.ship.piloting").formatted(Formatting.GOLD);
				player.sendMessage(pilotMessage, true);
			}
		}
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		if (state == ShipState.SAILING && this.getPassengerList().isEmpty() && !this.getEntityWorld().isClient()) {
			dock();
		}
	}

	/**
	 * Updates a block's state in the ship's block list and display.
	 * Builds a new immutable list — safe for concurrent read by chunk-saving thread.
	 */
	public void updateShipBlock(int index, BlockState newState) {
		List<ShipBlock> current = blocks;
		if (index >= 0 && index < current.size()) {
			ShipBlock oldBlock = current.get(index);
			List<ShipBlock> updated = new ArrayList<>(current);
			updated.set(index, new ShipBlock(oldBlock.relativePos(), newState));
			blocks = List.copyOf(updated);

			if (elementHolder != null) {
				elementHolder.updateBlockState(oldBlock.relativePos(), newState);
			}
		}
	}

	@Override
	public void tick() {
		super.tick();

		if (state != ShipState.SAILING) {
			return;
		}

		// Guard: discard phantom entities with no blocks
		if (blocks.isEmpty()) {
			LOGGER.warn("Ship entity has no blocks, discarding phantom entity");
			this.discard();
			return;
		}

		// Periodically adapt float height to water surface below the ship.
		// Samples multiple columns (helm + extremes) so long ships don't embed
		// one end in terrain when approaching shore.
		ticksSinceWaterCheck++;
		if (ticksSinceWaterCheck >= ShipConfig.WATER_SURFACE_CHECK_INTERVAL) {
			ticksSinceWaterCheck = 0;
			sampleWaterSurface();
		}

		// Floating physics — ease toward water surface
		double currentY = this.getY();
		double yDiff = floatTargetY - currentY;
		double yVelocity = 0;

		if (Math.abs(yDiff) > ShipConfig.FLOAT_SNAP_THRESHOLD) {
			yVelocity = yDiff * ShipConfig.FLOAT_LERP_FACTOR;
			yVelocity = Math.max(-ShipConfig.FLOAT_MAX_Y_SPEED, Math.min(ShipConfig.FLOAT_MAX_Y_SPEED, yVelocity));
		}

		// Handle player controls
		ServerPlayerEntity controller = null;
		if (this.hasPassengers() && this.getFirstPassenger() instanceof ServerPlayerEntity passenger) {
			controller = passenger;
		}

		// Gather hull positions of other sailing ships for ship-to-ship collision.
		// Computed once per tick and reused for rotation, X, Z, and Y checks.
		Set<BlockPos> otherShipHullPositions = gatherNearbyShipHullPositions();

		if (controller != null) {
			PlayerInput input = PlayerInputStorage.getInput(controller);

			float forward = 0;
			float sideways = 0;
			if (input.forward()) forward += 1.0f;
			if (input.backward()) forward -= 1.0f;
			if (input.left()) sideways += 1.0f;
			if (input.right()) sideways -= 1.0f;

			// A/D rotates the ship — blocked by terrain OR other ships
			if (sideways != 0) {
				float newYaw = yawRadians - sideways * ShipConfig.TURN_SPEED;
				ShipPose rotatedPose = new ShipPose(helmX, this.getY(), helmZ, newYaw);
				if (!collision.checkCollisionAtRotation(this.getEntityWorld(), rotatedPose)
						&& !collision.checkShipCollision(rotatedPose, otherShipHullPositions)) {
					yawRadians = newYaw;
					this.setYaw((float) Math.toDegrees(yawRadians));
				}
			}

			// W/S applies acceleration
			physics.applyAcceleration(forward, helmFacing, yawRadians);
		}

		// Apply physics
		physics.applyDrag();
		physics.clampToMaxSpeed();
		physics.stopIfSlow();

		// Apply velocity with collision checks (terrain + ship-to-ship)
		double newY = this.getY();

		double velX = physics.getVelocityX();
		double velZ = physics.getVelocityZ();
		ShipPose currentPose = pose();

		// World border: Minecraft world limit is ±30,000,000 blocks
		double worldLimit = 29_999_984;

		if (velX != 0 && Math.abs(helmX + velX) < worldLimit) {
			ShipPose movedPose = new ShipPose(helmX + velX, this.getY(), helmZ, yawRadians);
			if (!collision.checkCollisionAndBreakFragile(this.getEntityWorld(), currentPose, velX, 0, 0)
					&& !collision.checkShipCollision(movedPose, otherShipHullPositions)) {
				helmX += velX;
			} else {
				physics.stopX();
			}
		} else if (velX != 0) {
			physics.stopX();
		}

		// Recompute pose after X movement (helmX may have changed)
		currentPose = pose();

		if (velZ != 0 && Math.abs(helmZ + velZ) < worldLimit) {
			ShipPose movedPose = new ShipPose(helmX, this.getY(), helmZ + velZ, yawRadians);
			if (!collision.checkCollisionAndBreakFragile(this.getEntityWorld(), currentPose, 0, 0, velZ)
					&& !collision.checkShipCollision(movedPose, otherShipHullPositions)) {
				helmZ += velZ;
			} else {
				physics.stopZ();
			}
		} else if (velZ != 0) {
			physics.stopZ();
		}

		// Recompute pose after Z movement (helmZ may have changed)
		currentPose = pose();

		if (yVelocity != 0) {
			ShipPose movedPose = new ShipPose(helmX, this.getY() + yVelocity, helmZ, yawRadians);
			if (!collision.checkCollisionAndBreakFragile(this.getEntityWorld(), currentPose, 0, yVelocity, 0)
					&& !collision.checkShipCollision(movedPose, otherShipHullPositions)) {
				newY += yVelocity;
			}
		}

		// Move entity to passenger seat position
		Vec3d seatWorld = computeSeatWorldPos();
		this.setVelocity(seatWorld.x - this.getX(), newY - this.getY(), seatWorld.z - this.getZ());
		this.move(MovementType.SELF, this.getVelocity());

		// Sync display rotation with orbit compensation
		if (elementHolder != null && Math.abs(yawRadians - lastDisplayYaw) > 0.001f) {
			Vec3d displayOffset = computeDisplayOrbitOffset();
			elementHolder.updateRotationWithOffset(yawRadians, displayOffset.x, displayOffset.z);
			lastDisplayYaw = yawRadians;
		}

		ShipPose tickPose = pose();
		collisionEntities.tickUpdate(tickPose);

		// Update light block positions as ship moves/rotates
		if (lighting.hasLightSources() && this.getEntityWorld() instanceof ServerWorld serverWorld) {
			if (lighting.needsUpdate(tickPose)) {
				lighting.updatePositions(serverWorld, tickPose);
			}
		}

	}

	@Override
	public void onPassengerLookAround(Entity passenger) {
		// Ship rotation controlled by A/D, not player look
	}

	@Override
	protected void updatePassengerPosition(Entity passenger, PositionUpdater positionUpdater) {
		if (this.hasPassenger(passenger)) {
			// Entity position is already at the player seat (helmX + 0.5 + rotatedHelmOffset)
			// calculated in tick(). No additional offset needed.
			positionUpdater.accept(passenger, this.getX(), this.getY(), this.getZ());
		}
	}

	@Override
	public Vec3d updatePassengerForDismount(LivingEntity passenger) {
		Vec3d[] offsets = {
			// Cardinals at distance 2
			new Vec3d(2, 0, 0),
			new Vec3d(-2, 0, 0),
			new Vec3d(0, 0, 2),
			new Vec3d(0, 0, -2),
			// Diagonals
			new Vec3d(2, 0, 2),
			new Vec3d(-2, 0, 2),
			new Vec3d(2, 0, -2),
			new Vec3d(-2, 0, -2),
			// Cardinals at distance 3
			new Vec3d(3, 0, 0),
			new Vec3d(-3, 0, 0),
			new Vec3d(0, 0, 3),
			new Vec3d(0, 0, -3),
			// Above
			new Vec3d(0, 2, 0),
			new Vec3d(0, 3, 0),
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

		// Last resort: place above the ship's highest block to avoid suffocation after dock
		int maxRelY = 0;
		for (ShipBlock block : blocks) {
			maxRelY = Math.max(maxRelY, block.relativePos().y());
		}
		return thisPos.add(0, maxRelY + 2, 0);
	}

	@Override
	public void readCustomData(ReadView view) {
		this.blocks = List.copyOf(view.read(BLOCKS_KEY, BLOCKS_CODEC).orElse(List.of()));

		collision.computeHullBlocks(blocks);

		int facingOrdinal = view.getInt("helm_facing", Direction.NORTH.ordinal());
		Direction[] directions = Direction.values();
		Direction loaded = (facingOrdinal >= 0 && facingOrdinal < directions.length)
			? directions[facingOrdinal]
			: Direction.NORTH;
		// Guard against corrupt data — only horizontal directions are valid for helms
		this.helmFacing = loaded.getAxis().isHorizontal() ? loaded : Direction.NORTH;

		this.floatTargetY = view.getDouble("target_y", this.getY());

		float savedYawDegrees = view.getFloat("ship_yaw", 0f);
		this.yawRadians = (float) Math.toRadians(savedYawDegrees);
		this.setYaw(savedYawDegrees);

		// Legacy fallback: "base_x"/"base_z" from pre-rename saves. Safe to remove once
		// all existing worlds have been loaded at least once under the current version.
		this.helmX = view.getDouble("helm_x", view.getDouble("base_x", this.getX()));
		this.helmZ = view.getDouble("helm_z", view.getDouble("base_z", this.getZ()));

		boolean savedDocked = view.getBoolean("docked", true);
		this.state = savedDocked ? ShipState.DOCKED : ShipState.SAILING;
		List<BlockPos> loadedPositions = List.copyOf(view.read("docked_positions", ShipDocking.BLOCK_POS_LIST_CODEC).orElse(List.of()));
		boolean needsForceDock = !savedDocked;

		String savedName = view.getString("ship_name", "");
		if (!savedName.isEmpty()) {
			setShipName(savedName);
		}

		List<UUID> oldUUIDs = new ArrayList<>(view.read("child_uuids", UUID_LIST_CODEC).orElse(List.of()));
		if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
			collisionEntities.cleanupOrphanedEntities(serverWorld, oldUUIDs);
		}

		List<BlockPos> savedLightPositions = new ArrayList<>(view.read("light_pos", BLOCK_POS_LIST_CODEC).orElse(List.of()));
		if (!savedLightPositions.isEmpty() && this.getEntityWorld() instanceof ServerWorld serverWorld) {
			ShipLighting.cleanupLightPositions(serverWorld, savedLightPositions);
		}

		List<ShipDecoration> loadedDecorations = List.copyOf(view.read("decorations", ShipDocking.DECORATIONS_CODEC).orElse(List.of()));
		docking.loadState(loadedPositions, loadedDecorations);

		if (!blocks.isEmpty()) {
			initializeElementHolder();
			if (needsForceDock && this.getEntityWorld() instanceof ServerWorld) {
				// Ship was undocked when saved (server crash/restart during sailing)
				// Force dock to place blocks back in the world
				this.state = ShipState.SAILING; // Set to SAILING so dock() can transition
				dock();
				LOGGER.debug("Force-docked ship that was undocked when saved");
			} else if (state == ShipState.DOCKED) {
				if (elementHolder != null) {
					elementHolder.setVisible(false);
				}
			}
		}

		LOGGER.debug("Loaded ship: {} blocks, state={}, yaw={} deg", blocks.size(), state, Math.toDegrees(yawRadians));
	}

	@Override
	public void writeCustomData(WriteView view) {
		// blocks is already an immutable snapshot (List.copyOf at every assignment).
		// List.copyOf on an unmodifiable list is a no-op — safe and cheap.
		List<ShipBlock> blocksSnapshot = List.copyOf(blocks);
		view.put(BLOCKS_KEY, BLOCKS_CODEC, blocksSnapshot);
		// Ordinal encoding: fragile if Mojang reorders Direction enum (unlikely but possible).
		// Kept for backward compatibility; readCustomData has bounds check + horizontal guard.
		view.putInt("helm_facing", helmFacing.ordinal());
		view.putDouble("target_y", floatTargetY);
		view.putFloat("ship_yaw", (float) Math.toDegrees(yawRadians));
		view.putDouble("helm_x", helmX);
		view.putDouble("helm_z", helmZ);
		// Treat transient states (UNDOCKING/DOCKING) as docked to prevent
		// force-dock on reload from duplicating blocks already in the world
		view.putBoolean("docked", state != ShipState.SAILING);

		if (state == ShipState.DOCKED && !docking.getDockedBlockPositions().isEmpty()) {
			view.put("docked_positions", ShipDocking.BLOCK_POS_LIST_CODEC, docking.getDockedBlockPositions());
		}

		if (shipName != null && !shipName.isEmpty()) {
			view.putString("ship_name", shipName);
		}

		List<UUID> childUUIDs = collisionEntities.getTrackedChildEntityUUIDs();
		if (!childUUIDs.isEmpty()) {
			view.put("child_uuids", UUID_LIST_CODEC, childUUIDs);
		}

		List<BlockPos> lightPositions = lighting.getPlacedLightPositions();
		if (!lightPositions.isEmpty()) {
			view.put("light_pos", BLOCK_POS_LIST_CODEC, lightPositions);
		}

		if (!docking.getDecorations().isEmpty()) {
			view.put("decorations", ShipDocking.DECORATIONS_CODEC, docking.getDecorations());
		}
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

	public List<ShipBlock> getBlocks() {
		return Collections.unmodifiableList(blocks);
	}

	public int getBlockCount() {
		return blocks.size();
	}

	public List<BlockPos> getDockedBlockPositions() {
		return docking.getDockedBlockPositions();
	}

	public boolean isHelmInteraction(Entity entity) {
		return collisionEntities.isHelmInteraction(entity);
	}

	public boolean isCollisionShulker(Entity entity) {
		return collisionEntities.isCollisionShulker(entity);
	}

	public double getHelmX() { return helmX; }
	public double getHelmZ() { return helmZ; }
	public float getYawRadians() { return yawRadians; }

	/**
	 * Returns the world BlockPos of this ship's helm, based on helmX/Y/helmZ.
	 */
	public BlockPos getHelmBlockPos() {
		return BlockPos.ofFloored(helmX, this.getY(), helmZ);
	}

	public void setShipName(String name) {
		this.shipName = name;
		if (name != null && !name.isEmpty()) {
			this.setCustomName(Text.literal(name));
			this.setCustomNameVisible(true);
		}
	}

	public String getShipName() {
		return shipName;
	}

	public boolean hasShipName() {
		return shipName != null && !shipName.isEmpty();
	}

	/**
	 * Gathers the combined hull world positions of all other sailing ships nearby.
	 * Used for ship-to-ship collision — ships should stop when they meet, same as terrain.
	 * Computed once per tick and reused for all axis checks.
	 */
	/**
	 * Returns this ship's hull world positions, computing once per tick.
	 * Other ships call this to check for overlap — caching avoids recomputing
	 * the same positions for every querying ship.
	 */
	private Set<BlockPos> getOrComputeHullPositions() {
		int currentTick = this.age;
		if (currentTick != cachedHullTick) {
			cachedHullPositions = collision.getWorldHullPositions(pose());
			cachedHullTick = currentTick;
		}
		return cachedHullPositions;
	}

	private Set<BlockPos> gatherNearbyShipHullPositions() {
		World world = this.getEntityWorld();
		if (!(world instanceof ServerWorld)) return Set.of();

		double searchRange = ShipConfig.SHIP_OVERLAP_SEARCH_RANGE;
		Box searchBox = new Box(
			helmX - searchRange, this.getY() - 20, helmZ - searchRange,
			helmX + searchRange, this.getY() + 20, helmZ + searchRange);

		List<MultiBlockShipEntity> nearbyShips = world.getEntitiesByClass(
			MultiBlockShipEntity.class, searchBox,
			other -> other != this && other.state == ShipState.SAILING);

		if (nearbyShips.isEmpty()) return Set.of();

		Set<BlockPos> positions = new HashSet<>();
		for (MultiBlockShipEntity other : nearbyShips) {
			positions.addAll(other.getOrComputeHullPositions());
		}
		return positions;
	}

	/**
	 * Samples water surface at multiple points along the ship's extent.
	 * Uses the maximum surface height so the ship floats above the shallowest water,
	 * preventing the far end from embedding in terrain when approaching shore.
	 */
	private void sampleWaterSurface() {
		World world = this.getEntityWorld();
		int startY = (int) Math.floor(this.getY());
		ShipPose currentPose = pose();

		// Find ship's extreme blocks along the forward axis
		int minRelX = 0, maxRelX = 0, minRelZ = 0, maxRelZ = 0;
		for (ShipBlock block : blocks) {
			minRelX = Math.min(minRelX, block.relativePos().x());
			maxRelX = Math.max(maxRelX, block.relativePos().x());
			minRelZ = Math.min(minRelZ, block.relativePos().z());
			maxRelZ = Math.max(maxRelZ, block.relativePos().z());
		}

		// Sample at helm (center), bow (max extent), and stern (min extent)
		RelativeBlockPos[] samplePoints = {
			RelativeBlockPos.ORIGIN,
			new RelativeBlockPos(minRelX, 0, minRelZ),
			new RelativeBlockPos(maxRelX, 0, maxRelZ),
		};

		double maxSurface = Double.NEGATIVE_INFINITY;
		boolean foundWater = false;
		for (RelativeBlockPos sample : samplePoints) {
			Vec3d worldPos = currentPose.toWorld(sample);
			OptionalDouble surface = findWaterSurface(world,
				(int) Math.floor(worldPos.x), startY, (int) Math.floor(worldPos.z));
			if (surface.isPresent()) {
				maxSurface = Math.max(maxSurface, surface.getAsDouble());
				foundWater = true;
			}
		}

		if (foundWater) {
			floatTargetY = maxSurface;
		}
	}

	/**
	 * Finds the water surface Y at the given column.
	 * Scans down from startY to find water, then returns the top of the water column.
	 * Returns empty if no water found (ship is over land).
	 */
	private static OptionalDouble findWaterSurface(World world, int x, int startY, int z) {
		int scanBottom = startY - ShipConfig.WATER_SURFACE_SCAN_DEPTH;
		int waterTop = Integer.MIN_VALUE;

		BlockPos.Mutable scanPos = new BlockPos.Mutable();

		// Scan from 2 above current Y (ship may be rising) down to scan depth
		for (int y = startY + 2; y >= scanBottom; y--) {
			BlockState stateAtY = world.getBlockState(scanPos.set(x, y, z));
			if (stateAtY.isLiquid()) {
				waterTop = y;
				break;
			}
		}

		if (waterTop == Integer.MIN_VALUE) {
			return OptionalDouble.empty();
		}

		// Scan up from the water to find the surface (up to 4 above start for deep water)
		for (int y = waterTop + 1; y <= startY + 4; y++) {
			BlockState stateAtY = world.getBlockState(scanPos.set(x, y, z));
			if (!stateAtY.isLiquid()) {
				return OptionalDouble.of(y);
			}
			waterTop = y;
		}

		return OptionalDouble.of(waterTop + 1);
	}

	@Override
	public void remove(RemovalReason reason) {
		// If ship is not fully docked, place blocks back in the world.
		// SAILING: blocks are virtual, need dock to restore them.
		// UNDOCKING: blocks are being removed from the world mid-transition.
		// DOCKING: dock is on the call stack (same thread) and will finish via try-finally.
		//   The dock() call here hits the reentry guard and is a no-op, but it's
		//   included for explicit coverage of all non-DOCKED states.
		// Without this, /kill or entity removal permanently destroys all ship blocks.
		if (state != ShipState.DOCKED
				&& !blocks.isEmpty() && this.getEntityWorld() instanceof ServerWorld) {
			LOGGER.info("Ship removed while {} — force-docking to preserve {} blocks", state, blocks.size());
			dock();
		}

		super.remove(reason);
		if (attachment != null) {
			attachment.destroy();
			attachment = null;
		}

		// Safe to call even if dock/undock already handled these — both are idempotent
		collisionEntities.discardAll();

		if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
			lighting.remove(serverWorld);
		}
	}
}
