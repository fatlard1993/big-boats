package justfatlard.big_boats.ship;

import com.mojang.serialization.Codec;
import justfatlard.big_boats.BigBoats;
import justfatlard.big_boats.detection.FloodFillDetector;
import justfatlard.big_boats.util.PlayerInputStorage;
import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.big_boats.util.ShipBlockUtils;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.ChatFormatting;

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
 * A multi-block ship entity that can be driven by players. Blocks are detected via
 * flood-fill from the helm.
 *
 * <h2>Dual coordinate system</h2>
 * <ul>
 *   <li><b>helmX/helmZ</b>: the world position of the helm block's corner. All ship
 *       blocks, collision, display, and lighting are positioned relative to this.</li>
 *   <li><b>Entity position (getX/getZ)</b>: orbits helmX/helmZ based on helm facing and
 *       current yaw; this is where the passenger sits. Computed each tick as
 *       {@code entityX = helmX + 0.5 + rotateXZ(helmSeatOffset, yawRadians).x}</li>
 * </ul>
 * Use helmX/helmZ for anything that positions ship elements. Use entity position only
 * for passenger placement and Minecraft's entity system.
 *
 * <p>Ships are plain server {@link Entity} instances registered with Pandorical's
 * {@code "invisible"} renderer ({@link BigBoats#onInitialize}), so the entity draws
 * nothing. {@link ShipStructure} renders the blocks, posed at (helmX, helmY, helmZ,
 * yawDegrees). Camera pull-back while piloting is pushed via
 * {@link PandoricalApi#camera()} on mount/dismount. Delegates receive a
 * {@link ShipPose} to transform ship-local coordinates to world coordinates.
 */
public class MultiBlockShipEntity extends Entity {
	private static final Logger LOGGER = LoggerFactory.getLogger(MultiBlockShipEntity.class);
	private static final Codec<List<ShipBlock>> BLOCKS_CODEC = ShipBlock.CODEC.listOf();
	private static final Codec<List<BlockPos>> BLOCK_POS_LIST_CODEC = BlockPos.CODEC.listOf();
	private static final Codec<List<UUID>> UUID_LIST_CODEC = UUIDUtil.CODEC.listOf();
	private static final String BLOCKS_KEY = "ship_blocks";

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

	// writeCustomData reads this from the chunk-saving thread while tick/rescan writes on the
	// server thread. Every assignment must be a fresh immutable list: blocks = List.copyOf(local).
	private volatile List<ShipBlock> blocks = List.of();
	private ShipStructure structure;

	private final ShipPhysics physics = new ShipPhysics();
	private final ShipCollision collision = new ShipCollision();
	private final ShipLighting lighting = new ShipLighting();

	private final ShipCollisionEntities collisionEntities = new ShipCollisionEntities();

	private final ShipDocking docking = new ShipDocking();

	// Tracks the water surface when over water; holds last-known value over land.
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

	// Volatile: read by writeCustomData on chunk-saving thread.
	private volatile ShipState state = ShipState.DOCKED;


	private int lastRescanRejectedBlocks = 0;

	// Hull world positions for ship-to-ship collision, computed once per tick when sailing
	// and read by other ships' gatherNearbyShipHullPositions.
	private Set<BlockPos> cachedHullPositions = Set.of();
	private int cachedHullTick = -1;

	private int ticksSinceWaterCheck = 0;

	// Set by a named christening bottle
	private String shipName = null;

	public MultiBlockShipEntity(EntityType<?> entityType, Level world) {
		super(entityType, world);
		this.setPermanentlyInvulnerable(true);
	}

	public MultiBlockShipEntity(Level world, double x, double y, double z, List<ShipBlock> blocks, Direction helmFacing) {
		this(BigBoats.MULTI_BLOCK_SHIP_ENTITY_TYPE, world);
		this.setPos(x, y, z);
		this.blocks = List.copyOf(blocks);
		this.floatTargetY = y;
		this.helmFacing = helmFacing;

		this.helmX = x;
		this.helmZ = z;

		collision.computeHullBlocks(blocks);

		this.setYRot(0);
		this.yawRadians = 0;

		LOGGER.debug("Ship created at ({}, {}, {}) with {} blocks, facing {}", x, y, z, blocks.size(), helmFacing);

		initializeStructure();
	}

	/**
	 * Initializes the ship after christening.
	 * Ship starts DOCKED with real blocks still in place - undocks when player mounts.
	 */
	public void initializeShip(BlockPos helmPos) {
		if (!(this.level() instanceof ServerLevel)) {
			return;
		}

		state = ShipState.DOCKED;
		docking.recordPositions(blocks, helmPos);

		// Hide structure (real blocks are visible)
		if (structure != null) {
			structure.setVisible(false);
		}
	}

	/**
	 * Docks the ship - places real blocks at current position for full interaction.
	 * Called when no one is driving. Snaps to nearest cardinal direction.
	 */
	public void dock() {
		if (state == ShipState.DOCKED || state == ShipState.DOCKING
				|| !(this.level() instanceof ServerLevel world)) {
			return;
		}

		state = ShipState.DOCKING;

		try {
			dockInner(world);
		} catch (RuntimeException e) {
			// Broad catch intentional: dock involves world mutations (block placement, entity
			// spawning, NBT restoration) that can fail in many ways. The ship MUST reach DOCKED
			// state regardless; a stuck DOCKING state strands the entity permanently.
			LOGGER.error("Exception during dock — forcing DOCKED state to prevent stranding", e);
		} finally {
			state = ShipState.DOCKED;
		}
	}

	private void dockInner(ServerLevel world) {
		LOGGER.debug("Docking ship ({} blocks) at ({}, {}, {})", blocks.size(), helmX, this.getY(), helmZ);

		lighting.remove(world);
		physics.reset();

		// Snap rotation and position to block grid
		float yawDegrees = (float) Math.toDegrees(yawRadians);
		ShipBlockUtils.SnappedRotation snap = ShipBlockUtils.snappedRotation(yawDegrees);
		yawRadians = snap.yawRadians();
		this.setYRot(snap.yawDegrees());
		helmX = Math.round(helmX);
		helmZ = Math.round(helmZ);

		ShipDocking.DockStats stats = docking.placeBlocks(world, blocks, helmX, this.getY(), helmZ, snap);
		docking.restoreDecorations(world, helmX, this.getY(), helmZ, snap);

		// Notify nearby players of docking issues
		if (stats.obstructed() > 0 || stats.lostBlockEntities() > 0) {
			AABB notifyArea = new AABB(helmX - 10, this.getY() - 5, helmZ - 10,
				helmX + 10, this.getY() + 10, helmZ + 10);
			for (Player player : world.getEntities(EntityTypeTest.forClass(Player.class), notifyArea, p -> true)) {
				if (player instanceof ServerPlayer serverPlayer) {
					if (stats.obstructed() > 0) {
						serverPlayer.sendSystemMessage(
							Component.translatable("big-boats.ship.obstructed", stats.obstructed())
								.withStyle(ChatFormatting.YELLOW), true);
					}
					if (stats.lostBlockEntities() > 0) {
						serverPlayer.sendSystemMessage(
							Component.translatable("big-boats.ship.lost_contents", stats.lostBlockEntities())
								.withStyle(ChatFormatting.RED), true);
					}
				}
			}
		}

		if (structure != null) {
			structure.setVisible(false);
		}
		collisionEntities.discardAll();
	}

	/**
	 * Undocks the ship - removes real blocks, enables the structure for movement.
	 * Called when player starts driving.
	 *
	 * <p>Two failure modes with different recovery:
	 * <ul>
	 *   <li>Pre-mutation failure (rescan): blocks are still in the world abort to DOCKED</li>
	 *   <li>Post-mutation failure (after removeBlocks): blocks are gone force-dock to restore</li>
	 * </ul>
	 */
	public void undock() {
		if (state != ShipState.DOCKED || !(this.level() instanceof ServerLevel world)) {
			return;
		}

		state = ShipState.UNDOCKING;

		try {
			if (!undockInner(world)) {
				// Rescan or validation failed; blocks are still in the world, just abort.
				state = ShipState.DOCKED;
				return;
			}
		} catch (RuntimeException e) {
			// Exception after removeBlocks: blocks have been removed from the world.
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
	private boolean undockInner(ServerLevel world) {
		LOGGER.debug("Undocking ship ({} blocks)", blocks.size());

		// Re-detect ship structure to include/remove blocks changed while docked.
		// If rescan fails, return false; blocks are still in the world, safe to abort.
		BlockPos helmWorldPos = BlockPos.containing(helmX, this.getY(), helmZ);
		if (!rescanShipStructure(world, helmWorldPos)) {
			LOGGER.warn("Aborting undock — rescan failed, ship structure may be missing");
			for (Entity p : this.getPassengers()) {
				if (p instanceof ServerPlayer sp) {
					sp.sendSystemMessage(
						Component.translatable("big-boats.ship.structure_damaged").withStyle(ChatFormatting.RED), true);
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

		// Position entity at passenger seat and sync structure
		Vec3 seatWorld = computeSeatWorldPos();
		this.setPos(seatWorld.x, this.getY(), seatWorld.z);

		if (structure != null) {
			structure.setVisible(true);
			structure.updatePose(pose());
		}

		ShipPose currentPose = pose();
		collisionEntities.spawnAll(world, blocks, currentPose, collision.getHullBlocks());
		collisionEntities.updatePositions(currentPose);
		collisionEntities.syncTrackingState(helmX, helmZ, yawRadians);

		lighting.detectFromBlocks(blocks);
		lighting.spawnLightBlocks(world, currentPose);

		LOGGER.debug("Undock complete: {} blocks, lighting={}", blocks.size(), lighting.hasLightSources());
		return true;
	}

	public ShipPose pose() {
		return new ShipPose(helmX, this.getY(), helmZ, yawRadians);
	}

	private Vec3 computeSeatWorldPos() {
		Vec3 seatOffset = ShipBlockUtils.helmSeatOffset(helmFacing);
		Vec3 rotated = ShipBlockUtils.rotateXZ(seatOffset.x, seatOffset.z, yawRadians);
		return new Vec3(helmX + 0.5 + rotated.x, this.getY(), helmZ + 0.5 + rotated.z);
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
	private boolean rescanShipStructure(ServerLevel world, BlockPos helmWorldPos) {
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
			detectedWorldPositions.add(helmWorldPos.offset(
				detectedBlock.relativePos().x(),
				detectedBlock.relativePos().y(),
				detectedBlock.relativePos().z()
			));
		}

		// Remove blocks that are no longer in the detected structure (broken while docked)
		int removedCount = 0;
		List<ShipBlock> survivingBlocks = new ArrayList<>();
		List<RelativeBlockPos> removedRelPositions = new ArrayList<>();
		for (ShipBlock block : blocks) {
			BlockPos worldPos = relToWorldPos.get(block.relativePos());

			if (worldPos != null && detectedWorldPositions.contains(worldPos)) {
				survivingBlocks.add(block);
			} else if (block.isHelm()) {
				survivingBlocks.add(block);
			} else {
				removedCount++;
				removedRelPositions.add(block.relativePos());
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
			BlockPos detectedWorldPos = helmWorldPos.offset(
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

		// Assign as immutable snapshot: safe for concurrent read by the chunk-saving thread
		if (removedCount > 0 || !newBlocks.isEmpty()) {
			List<ShipBlock> updatedBlocks = new ArrayList<>(survivingBlocks);
			updatedBlocks.addAll(newBlocks);
			blocks = List.copyOf(updatedBlocks);

			if (structure != null) {
				if (!removedRelPositions.isEmpty()) structure.removeBlocks(removedRelPositions);
				if (!newBlocks.isEmpty()) structure.addBlocks(newBlocks);
			}
		}

		return true;
	}

	private void initializeStructure() {
		if (this.structure != null) {
			return;
		}

		if (this.level() instanceof ServerLevel && !blocks.isEmpty()) {
			this.structure = new ShipStructure("bigboats:" + this.getUUID());
			this.structure.spawn(this, new ArrayList<>(blocks), pose());
			// Collision entities are spawned in undock(), not here.
			// Docked ships use real blocks for collision; spawning shulkers here
			// would waste server entity budget on idle docked ships.
		}
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		// No tracked data: ship state is server-authoritative and synced to Pandorical
		// clients out-of-band via the structure API, not vanilla entity tracked data.
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public void push(Entity entity) {
		// Don't get pushed
	}

	@Override
	public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
		// Ships are invulnerable; removal is via /kill, which triggers remove() -> dock()
		return false;
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 hitPos) {
		if (!this.level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
			return tryMount(serverPlayer) ? InteractionResult.CONSUME : InteractionResult.PASS;
		}
		return InteractionResult.PASS;
	}

	/**
	 * Single entry point for mounting the ship. All mount paths converge here.
	 * Returns true if the player was successfully mounted.
	 *
	 * Grounding check runs HERE (before startRiding) rather than in addPassenger,
	 * to avoid calling stopRiding() inside addPassenger() which is recursive and
	 * can cause inconsistent passenger state.
	 */
	public boolean tryMount(ServerPlayer player) {
		if (!this.getPassengers().isEmpty()) {
			player.sendSystemMessage(
				Component.translatable("big-boats.ship.occupied").withStyle(ChatFormatting.YELLOW), true);
			return false;
		}

		if (state == ShipState.DOCKED && this.level() instanceof ServerLevel world) {
			Set<BlockPos> shipPositions = new HashSet<>(docking.getDockedBlockPositions());
			BlockPos helmPos = BlockPos.containing(helmX, this.getY(), helmZ);

			var groundingResult = FloodFillDetector.detectGrounding(
				world, shipPositions, blocks.size(), helmPos);

			if (!groundingResult.canUndock()) {
				player.sendSystemMessage(
					Component.translatable("big-boats.ship.grounded").withStyle(ChatFormatting.RED), true);
				return false;
			}
		}

		return player.startRiding(this);
	}

	@Override
	protected void addPassenger(Entity passenger) {
		super.addPassenger(passenger);

		if (state == ShipState.DOCKED && !this.level().isClientSide()
				&& this.level() instanceof ServerLevel) {
			// Grounding already checked in tryMount().
			int blocksBefore = blocks.size();
			undock();
			int blocksAfter = blocks.size();

			if (passenger instanceof ServerPlayer player) {
				int absorbed = blocksAfter - blocksBefore;
				if (absorbed > 0) {
					player.sendSystemMessage(
						Component.translatable("big-boats.ship.absorbed", absorbed).withStyle(ChatFormatting.GREEN), true);
				}
				if (lastRescanRejectedBlocks > 0) {
					player.sendSystemMessage(
						Component.translatable("big-boats.ship.absorption_capped", lastRescanRejectedBlocks, ShipConfig.MAX_BLOCKS)
							.withStyle(ChatFormatting.YELLOW), true);
				}
				Component pilotMessage = hasShipName()
					? Component.translatable("big-boats.ship.piloting_named", shipName).withStyle(ChatFormatting.GOLD)
					: Component.translatable("big-boats.ship.piloting").withStyle(ChatFormatting.GOLD);
				player.sendSystemMessage(pilotMessage, true);

				applyPilotingCameraHints(player);
			}
		}
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);

		if (passenger instanceof ServerPlayer player) {
			PandoricalApi.camera().reset(player);
		}

		if (state == ShipState.SAILING && this.getPassengers().isEmpty() && !this.level().isClientSide()) {
			dock();
		}
	}

	/**
	 * Pulls the camera back based on ship size and forces third-person-back view while
	 * piloting, via Pandorical's CameraApi. No-op for players without Pandorical.
	 */
	private void applyPilotingCameraHints(ServerPlayer player) {
		if (!PandoricalApi.isAvailable(player)) return;
		float distance = Math.min(ShipConfig.MAX_CAMERA_DISTANCE,
			Math.max(ShipConfig.MIN_CAMERA_DISTANCE, ShipConfig.MIN_CAMERA_DISTANCE + blocks.size() * ShipConfig.CAMERA_DISTANCE_PER_BLOCK));
		PandoricalApi.camera().setDistance(player, distance);
		PandoricalApi.camera().setPerspective(player, "third_person_back");
	}

	/**
	 * Builds a new immutable block list; safe for concurrent read by the chunk-saving thread.
	 */
	public void updateShipBlock(int index, BlockState newState) {
		List<ShipBlock> current = blocks;
		if (index >= 0 && index < current.size()) {
			ShipBlock oldBlock = current.get(index);
			List<ShipBlock> updated = new ArrayList<>(current);
			updated.set(index, new ShipBlock(oldBlock.relativePos(), newState));
			blocks = List.copyOf(updated);

			if (structure != null) {
				structure.updateBlockState(oldBlock.relativePos(), newState);
			}
		}
	}

	@Override
	public void tick() {
		super.tick();

		if (state != ShipState.SAILING) {
			return;
		}

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

		// Floating physics: ease toward water surface
		double currentY = this.getY();
		double yDiff = floatTargetY - currentY;
		double yVelocity = 0;

		if (Math.abs(yDiff) > ShipConfig.FLOAT_SNAP_THRESHOLD) {
			yVelocity = yDiff * ShipConfig.FLOAT_LERP_FACTOR;
			yVelocity = Math.max(-ShipConfig.FLOAT_MAX_Y_SPEED, Math.min(ShipConfig.FLOAT_MAX_Y_SPEED, yVelocity));
		}

		ServerPlayer controller = null;
		if (this.isVehicle() && this.getFirstPassenger() instanceof ServerPlayer passenger) {
			controller = passenger;
		}

		// Hull positions of other sailing ships, computed once per tick and reused
		// for the rotation, X, Z, and Y collision checks.
		Set<BlockPos> otherShipHullPositions = gatherNearbyShipHullPositions();

		if (controller != null) {
			Input input = PlayerInputStorage.getInput(controller);

			float forward = 0;
			float sideways = 0;
			if (input.forward()) forward += 1.0f;
			if (input.backward()) forward -= 1.0f;
			if (input.left()) sideways += 1.0f;
			if (input.right()) sideways -= 1.0f;

			// A/D rotates the ship; blocked by terrain or other ships
			if (sideways != 0) {
				float newYaw = yawRadians - sideways * ShipConfig.TURN_SPEED;
				ShipPose rotatedPose = new ShipPose(helmX, this.getY(), helmZ, newYaw);
				if (!collision.checkCollisionAtRotation(this.level(), rotatedPose)
						&& !collision.checkShipCollision(rotatedPose, otherShipHullPositions)) {
					yawRadians = newYaw;
					this.setYRot((float) Math.toDegrees(yawRadians));
				}
			}

			// W/S applies acceleration
			physics.applyAcceleration(forward, helmFacing, yawRadians);
		}

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
			if (!collision.checkCollisionAndBreakFragile(this.level(), currentPose, velX, 0, 0)
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
			if (!collision.checkCollisionAndBreakFragile(this.level(), currentPose, 0, 0, velZ)
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
			if (!collision.checkCollisionAndBreakFragile(this.level(), currentPose, 0, yVelocity, 0)
					&& !collision.checkShipCollision(movedPose, otherShipHullPositions)) {
				newY += yVelocity;
			}
		}

		// Move entity to passenger seat position
		Vec3 seatWorld = computeSeatWorldPos();
		this.setDeltaMovement(seatWorld.x - this.getX(), newY - this.getY(), seatWorld.z - this.getZ());
		this.move(MoverType.SELF, this.getDeltaMovement());

		// Sync structure pose: pushed every tick while sailing (Pandorical's client-side
		// interpolation expects a steady stream; see StructureManager's docs).
		if (structure != null) {
			structure.updatePose(pose());
		}

		ShipPose tickPose = pose();
		collisionEntities.tickUpdate(tickPose);

		if (lighting.hasLightSources() && this.level() instanceof ServerLevel serverWorld) {
			if (lighting.needsUpdate(tickPose)) {
				lighting.updatePositions(serverWorld, tickPose);
			}
		}

	}

	@Override
	public void onPassengerTurned(Entity passenger) {
		// Ship rotation controlled by A/D, not player look
	}

	@Override
	protected void positionRider(Entity passenger, MoveFunction positionUpdater) {
		if (this.hasPassenger(passenger)) {
			// Entity position is already at the player seat (helmX + 0.5 + rotatedHelmOffset)
			// calculated in tick(). No additional offset needed.
			positionUpdater.accept(passenger, this.getX(), this.getY(), this.getZ());
		}
	}

	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		Vec3[] offsets = {
			// Cardinals at distance 2
			new Vec3(2, 0, 0),
			new Vec3(-2, 0, 0),
			new Vec3(0, 0, 2),
			new Vec3(0, 0, -2),
			// Diagonals
			new Vec3(2, 0, 2),
			new Vec3(-2, 0, 2),
			new Vec3(2, 0, -2),
			new Vec3(-2, 0, -2),
			// Cardinals at distance 3
			new Vec3(3, 0, 0),
			new Vec3(-3, 0, 0),
			new Vec3(0, 0, 3),
			new Vec3(0, 0, -3),
			// Above
			new Vec3(0, 2, 0),
			new Vec3(0, 3, 0),
		};

		Level world = this.level();
		Vec3 thisPos = new Vec3(this.getX(), this.getY(), this.getZ());
		Vec3 passengerPos = new Vec3(passenger.getX(), passenger.getY(), passenger.getZ());

		for (Vec3 offset : offsets) {
			Vec3 pos = thisPos.add(offset);
			if (world.noCollision(passenger, passenger.getBoundingBox().move(pos.subtract(passengerPos)))) {
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
	protected void readAdditionalSaveData(ValueInput view) {
		this.blocks = List.copyOf(view.read(BLOCKS_KEY, BLOCKS_CODEC).orElse(List.of()));

		collision.computeHullBlocks(blocks);

		int facingOrdinal = view.getIntOr("helm_facing", Direction.NORTH.ordinal());
		Direction[] directions = Direction.values();
		Direction loaded = (facingOrdinal >= 0 && facingOrdinal < directions.length)
			? directions[facingOrdinal]
			: Direction.NORTH;
		// Guard against corrupt data; only horizontal directions are valid for helms
		this.helmFacing = loaded.getAxis().isHorizontal() ? loaded : Direction.NORTH;

		this.floatTargetY = view.getDoubleOr("target_y", this.getY());

		float savedYawDegrees = view.getFloatOr("ship_yaw", 0f);
		this.yawRadians = (float) Math.toRadians(savedYawDegrees);
		this.setYRot(savedYawDegrees);

		// Legacy fallback: "base_x"/"base_z" from pre-rename saves. Safe to remove once
		// all existing worlds have been loaded at least once under the current version.
		this.helmX = view.getDoubleOr("helm_x", view.getDoubleOr("base_x", this.getX()));
		this.helmZ = view.getDoubleOr("helm_z", view.getDoubleOr("base_z", this.getZ()));

		boolean savedDocked = view.getBooleanOr("docked", true);
		this.state = savedDocked ? ShipState.DOCKED : ShipState.SAILING;
		List<BlockPos> loadedPositions = List.copyOf(view.read("docked_positions", ShipDocking.BLOCK_POS_LIST_CODEC).orElse(List.of()));
		boolean needsForceDock = !savedDocked;

		String savedName = view.getStringOr("ship_name", "");
		if (!savedName.isEmpty()) {
			setShipName(savedName);
		}

		List<UUID> oldUUIDs = new ArrayList<>(view.read("child_uuids", UUID_LIST_CODEC).orElse(List.of()));
		if (this.level() instanceof ServerLevel serverWorld) {
			collisionEntities.cleanupOrphanedEntities(serverWorld, oldUUIDs);
		}

		List<BlockPos> savedLightPositions = new ArrayList<>(view.read("light_pos", BLOCK_POS_LIST_CODEC).orElse(List.of()));
		if (!savedLightPositions.isEmpty() && this.level() instanceof ServerLevel serverWorld) {
			ShipLighting.cleanupLightPositions(serverWorld, savedLightPositions);
		}

		List<ShipDecoration> loadedDecorations = List.copyOf(view.read("decorations", ShipDocking.DECORATIONS_CODEC).orElse(List.of()));
		docking.loadState(loadedPositions, loadedDecorations);

		if (!blocks.isEmpty()) {
			initializeStructure();
			if (needsForceDock && this.level() instanceof ServerLevel) {
				// Ship was undocked when saved (server crash/restart during sailing)
				// Force dock to place blocks back in the world
				this.state = ShipState.SAILING; // Set to SAILING so dock() can transition
				dock();
				LOGGER.debug("Force-docked ship that was undocked when saved");
			} else if (state == ShipState.DOCKED) {
				if (structure != null) {
					structure.setVisible(false);
				}
			}
		}

		LOGGER.debug("Loaded ship: {} blocks, state={}, yaw={} deg", blocks.size(), state, Math.toDegrees(yawRadians));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput view) {
		// blocks is already an immutable snapshot, so this List.copyOf is a no-op.
		List<ShipBlock> blocksSnapshot = List.copyOf(blocks);
		view.store(BLOCKS_KEY, BLOCKS_CODEC, blocksSnapshot);
		// Ordinal encoding is fragile if the Direction enum is ever reordered; kept for
		// backward compatibility. readAdditionalSaveData bounds-checks and guards horizontals.
		view.putInt("helm_facing", helmFacing.ordinal());
		view.putDouble("target_y", floatTargetY);
		view.putFloat("ship_yaw", (float) Math.toDegrees(yawRadians));
		view.putDouble("helm_x", helmX);
		view.putDouble("helm_z", helmZ);
		// Treat transient states (UNDOCKING/DOCKING) as docked to prevent
		// force-dock on reload from duplicating blocks already in the world
		view.putBoolean("docked", state != ShipState.SAILING);

		if (state == ShipState.DOCKED && !docking.getDockedBlockPositions().isEmpty()) {
			view.store("docked_positions", ShipDocking.BLOCK_POS_LIST_CODEC, docking.getDockedBlockPositions());
		}

		if (shipName != null && !shipName.isEmpty()) {
			view.putString("ship_name", shipName);
		}

		List<UUID> childUUIDs = collisionEntities.getTrackedChildEntityUUIDs();
		if (!childUUIDs.isEmpty()) {
			view.store("child_uuids", UUID_LIST_CODEC, childUUIDs);
		}

		List<BlockPos> lightPositions = lighting.getPlacedLightPositions();
		if (!lightPositions.isEmpty()) {
			view.store("light_pos", BLOCK_POS_LIST_CODEC, lightPositions);
		}

		if (!docking.getDecorations().isEmpty()) {
			view.store("decorations", ShipDocking.DECORATIONS_CODEC, docking.getDecorations());
		}
	}

	@Override
	public ItemStack getPickResult() {
		if (!blocks.isEmpty()) {
			return new ItemStack(blocks.get(0).blockState().getBlock().asItem());
		}
		return ItemStack.EMPTY;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengers().isEmpty();
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

	public BlockPos getHelmBlockPos() {
		return BlockPos.containing(helmX, this.getY(), helmZ);
	}

	public void setShipName(String name) {
		this.shipName = name;
		if (name != null && !name.isEmpty()) {
			this.setCustomName(Component.literal(name));
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
	 * Returns this ship's hull world positions, computing once per tick.
	 * Other ships call this to check for overlap; caching avoids recomputing the
	 * same positions for every querying ship.
	 */
	private Set<BlockPos> getOrComputeHullPositions() {
		int currentTick = this.tickCount;
		if (currentTick != cachedHullTick) {
			cachedHullPositions = collision.getWorldHullPositions(pose());
			cachedHullTick = currentTick;
		}
		return cachedHullPositions;
	}

	/**
	 * Gathers the combined hull world positions of all other sailing ships nearby.
	 * Used for ship-to-ship collision: ships stop when they meet, same as terrain.
	 * Computed once per tick and reused for all axis checks.
	 */
	private Set<BlockPos> gatherNearbyShipHullPositions() {
		Level world = this.level();
		if (!(world instanceof ServerLevel)) return Set.of();

		double searchRange = ShipConfig.SHIP_OVERLAP_SEARCH_RANGE;
		AABB searchBox = new AABB(
			helmX - searchRange, this.getY() - 20, helmZ - searchRange,
			helmX + searchRange, this.getY() + 20, helmZ + searchRange);

		List<MultiBlockShipEntity> nearbyShips = world.getEntities(
			EntityTypeTest.forClass(MultiBlockShipEntity.class), searchBox,
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
		Level world = this.level();
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
			Vec3 worldPos = currentPose.toWorld(sample);
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
	 * Finds the water surface Y at the given column, or empty if no water (ship is over land).
	 */
	private static OptionalDouble findWaterSurface(Level world, int x, int startY, int z) {
		int scanBottom = startY - ShipConfig.WATER_SURFACE_SCAN_DEPTH;
		int waterTop = Integer.MIN_VALUE;

		BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

		// Scan from 2 above current Y (ship may be rising) down to scan depth
		for (int y = startY + 2; y >= scanBottom; y--) {
			BlockState stateAtY = world.getBlockState(scanPos.set(x, y, z));
			if (stateAtY.liquid()) {
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
			if (!stateAtY.liquid()) {
				return OptionalDouble.of(y);
			}
			waterTop = y;
		}

		return OptionalDouble.of(waterTop + 1);
	}

	@Override
	public void remove(RemovalReason reason) {
		// If the ship is not fully docked, place blocks back in the world; without this,
		// /kill or entity removal permanently destroys all ship blocks.
		// SAILING: blocks are virtual, dock restores them.
		// UNDOCKING: blocks are being removed mid-transition.
		// DOCKING: dock() is on the call stack and finishes via try-finally; the call
		//   here hits the reentry guard and is a no-op.
		if (state != ShipState.DOCKED
				&& !blocks.isEmpty() && this.level() instanceof ServerLevel) {
			LOGGER.info("Ship removed while {} — force-docking to preserve {} blocks", state, blocks.size());
			dock();
		}

		super.remove(reason);

		// Pandorical does not auto-despawn structures on entity removal; despawn explicitly
		// to avoid leaking server-side structure state.
		if (structure != null) {
			structure.despawn();
		}

		// Idempotent; safe even if dock/undock already handled these
		collisionEntities.discardAll();

		if (this.level() instanceof ServerLevel serverWorld) {
			lighting.remove(serverWorld);
		}
	}
}
