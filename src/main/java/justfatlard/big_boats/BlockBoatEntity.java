package justfatlard.big_boats;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

public class BlockBoatEntity extends Entity implements PolymerEntity {
	// The block this boat represents
	private BlockState carriedBlock = Blocks.OAK_PLANKS.getDefaultState();

	// Boat movement variables
	private double waterLevel;

	// Tracked data for the carried block (synced to clients)
	private static final TrackedData<BlockState> CARRIED_BLOCK = DataTracker.registerData(
		BlockBoatEntity.class,
		TrackedDataHandlerRegistry.BLOCK_STATE
	);

	public BlockBoatEntity(EntityType<?> entityType, World world) {
		super(entityType, world);
	}

	public BlockBoatEntity(World world, double x, double y, double z) {
		this(BigBoats.BLOCK_BOAT_ENTITY_TYPE, world);
		this.setPosition(x, y, z);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		builder.add(CARRIED_BLOCK, Blocks.OAK_PLANKS.getDefaultState());
	}

	public void setCarriedBlock(BlockState state) {
		this.carriedBlock = state;
		this.dataTracker.set(CARRIED_BLOCK, state);
	}

	public BlockState getCarriedBlock() {
		return this.dataTracker.get(CARRIED_BLOCK);
	}

	@Override
	public EntityType<?> getPolymerEntityType(PacketContext context) {
		// Appear as an oak boat to vanilla clients
		return EntityType.OAK_BOAT;
	}

	@Override
	public boolean canHit() {
		return !this.isRemoved();
	}

	@Override
	public boolean isPushable() {
		return true;
	}

	@Override
	public boolean damage(ServerWorld world, DamageSource source, float amount) {
		if (this.isInvulnerable()) {
			return false;
		}
		if (!this.isRemoved()) {
			// Drop the original block when destroyed
			Item blockItem = this.carriedBlock.getBlock().asItem();
			if (blockItem != null) {
				this.dropItem(world, blockItem);
			}
			this.discard();
		}
		return true;
	}

	@Override
	public ActionResult interact(PlayerEntity player, Hand hand) {
		if (!this.getEntityWorld().isClient()) {
			if (!player.shouldCancelInteraction()) {
				return player.startRiding(this) ? ActionResult.CONSUME : ActionResult.PASS;
			}
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public void tick() {
		super.tick();

		// Handle water floating physics
		floatOnWater();

		// Apply movement from passengers
		if (this.hasPassengers()) {
			tickMovement();
		}
	}

	private void floatOnWater() {
		double buoyancy = 0.0;

		// Check if in water
		Box box = this.getBoundingBox();
		int minX = MathHelper.floor(box.minX);
		int maxX = MathHelper.ceil(box.maxX);
		int minY = MathHelper.floor(box.minY);
		int maxY = MathHelper.ceil(box.maxY);
		int minZ = MathHelper.floor(box.minZ);
		int maxZ = MathHelper.ceil(box.maxZ);

		boolean inWater = false;
		this.waterLevel = -Double.MAX_VALUE;

		World world = this.getEntityWorld();
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		for (int x = minX; x < maxX; x++) {
			for (int y = minY; y < maxY; y++) {
				for (int z = minZ; z < maxZ; z++) {
					mutable.set(x, y, z);
					FluidState fluidState = world.getFluidState(mutable);
					if (fluidState.isStill() || !fluidState.isEmpty()) {
						float fluidHeight = (float) y + fluidState.getHeight(world, mutable);
						this.waterLevel = Math.max(fluidHeight, this.waterLevel);
						inWater |= box.minY < (double) fluidHeight;
					}
				}
			}
		}

		if (inWater) {
			// Apply buoyancy - push up towards water surface
			double targetY = this.waterLevel - 0.1;
			if (this.getY() < targetY) {
				buoyancy = 0.04;
			}

			Vec3d velocity = this.getVelocity();
			this.setVelocity(velocity.x * 0.9, velocity.y + buoyancy, velocity.z * 0.9);
		} else {
			// Apply gravity when not in water
			Vec3d velocity = this.getVelocity();
			this.setVelocity(velocity.x * 0.98, velocity.y - 0.04, velocity.z * 0.98);
		}

		this.move(MovementType.SELF, this.getVelocity());
	}

	private void tickMovement() {
		Entity passenger = this.getFirstPassenger();
		if (!(passenger instanceof PlayerEntity player)) {
			return;
		}

		// Get input from player
		float forward = player.forwardSpeed;
		float sideways = player.sidewaysSpeed;

		if (forward != 0 || sideways != 0) {
			// Calculate movement direction based on player's look direction
			float yaw = player.getYaw() * ((float) Math.PI / 180F);

			float moveX = -MathHelper.sin(yaw) * forward + MathHelper.cos(yaw) * sideways;
			float moveZ = MathHelper.cos(yaw) * forward + MathHelper.sin(yaw) * sideways;

			// Apply movement (scaled down for boat-like speed)
			float speed = 0.04F;
			Vec3d velocity = this.getVelocity();
			this.setVelocity(
				velocity.x + moveX * speed,
				velocity.y,
				velocity.z + moveZ * speed
			);
		}
	}

	@Override
	public void onPassengerLookAround(Entity passenger) {
		// Rotate boat with passenger's yaw
		if (passenger instanceof PlayerEntity) {
			this.setYaw(passenger.getYaw());
		}
	}

	@Override
	public Vec3d updatePassengerForDismount(LivingEntity passenger) {
		// Dismount to the side
		Vec3d[] offsets = {
			new Vec3d(1.5, 0, 0),
			new Vec3d(-1.5, 0, 0),
			new Vec3d(0, 0, 1.5),
			new Vec3d(0, 0, -1.5)
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
		// TODO: Implement proper block state loading when we figure out the new API
		// For now, the tracked data handles syncing during gameplay
	}

	@Override
	public void writeCustomData(WriteView view) {
		// TODO: Implement proper block state saving when we figure out the new API
		// For now, the tracked data handles syncing during gameplay
	}

	@Override
	public ItemStack getPickBlockStack() {
		return new ItemStack(this.carriedBlock.getBlock().asItem());
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengerList().size() < 1;
	}

	@Override
	public LivingEntity getControllingPassenger() {
		Entity entity = this.getFirstPassenger();
		return entity instanceof LivingEntity living ? living : null;
	}
}
