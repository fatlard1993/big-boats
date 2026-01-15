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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.List;

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
		// Use pig - living entity that can be invisible and supports passengers
		return EntityType.PIG;
	}

	@Override
	public void modifyRawTrackedData(List<DataTracker.SerializedEntry<?>> data, ServerPlayerEntity player, boolean initial) {
		// Make the pig invisible via entity flags (index 0, bit 0x20 = invisible)
		data.removeIf(entry -> entry.id() == 0);
		data.add(new DataTracker.SerializedEntry<>(0, TrackedDataHandlerRegistry.BYTE, (byte) 0x20));
		// Add saddle so it can be ridden (pig tracked data index 17)
		data.add(new DataTracker.SerializedEntry<>(17, TrackedDataHandlerRegistry.BOOLEAN, true));
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
		if (!this.getEntityWorld().isClient()) {
			if (!player.shouldCancelInteraction()) {
				// Mount player directly on the ship
				if (this.getPassengerList().isEmpty()) {
					player.startRiding(this);
					return ActionResult.CONSUME;
				}
			}
		}
		return ActionResult.PASS;
	}

	@Override
	public void tick() {
		super.tick();

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

		// Get input from player riding the ship
		if (this.hasPassengers() && this.getFirstPassenger() instanceof ServerPlayerEntity passenger) {
			// Get player movement input from mixin-captured storage
			PlayerInput input = PlayerInputStorage.getInput(passenger);

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

			// W/S moves forward/backward - combines helm facing (base) + visual yaw (rotation)
			float baseYaw = directionToYaw(helmFacing);
			float totalYawRadians = (float) Math.toRadians(baseYaw + visualYaw);
			double speed = 0.15; // Ship speed per tick
			if (forward != 0) {
				moveX += -Math.sin(totalYawRadians) * forward * speed;
				moveZ += Math.cos(totalYawRadians) * forward * speed;
			}
		}

		// Apply movement to logical BASE position (not entity position)
		float yawRadians = (float) Math.toRadians(visualYaw);

		double newY = this.getY();

		// Check each axis and accumulate valid movement on BASE position
		if (moveX != 0 && !checkShipCollision(moveX, 0, 0, yawRadians)) {
			baseX += moveX;
		}
		if (moveZ != 0 && !checkShipCollision(0, 0, moveZ, yawRadians)) {
			baseZ += moveZ;
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
		double entityX = baseX + 0.5 + rotatedPlayerX;
		double entityZ = baseZ + 0.5 + rotatedPlayerZ;

		this.setPosition(entityX, newY, entityZ);
		// Clear velocity to prevent sliding
		this.setVelocity(0, 0, 0);

		// Update display entity positions - blocks need to compensate for entity orbit
		// Pass the orbit offset so blocks can adjust
		if (elementHolder != null) {
			elementHolder.updateRotationWithOffset(yawRadians, -rotatedPlayerX - 0.5, -rotatedPlayerZ - 0.5);
		}

		// Update collision entity positions (relative to logical helm position)
		updateCollisionPositionsWithOffset(baseX, baseZ, yawRadians);

		// Keep seat entity synced (if it exists)
		if (seatEntity != null && !seatEntity.isRemoved()) {
			seatEntity.setPosition(entityX, newY, entityZ);
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
			// The entity yaw handles rotation on the client side
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

		// Re-initialize display entities after loading (yaw is already set, so they'll be created correctly)
		if (!blocks.isEmpty()) {
			initializeElementHolder();
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
	 * Checks if moving the ship would cause any block to collide with world terrain.
	 * Returns true if collision detected. Breaks certain fragile blocks instead of colliding.
	 */
	private boolean checkShipCollision(double deltaX, double deltaY, double deltaZ, float yawRadians) {
		World world = this.getEntityWorld();
		double newX = this.getX() + deltaX;
		double newY = this.getY() + deltaY;
		double newZ = this.getZ() + deltaZ;

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
