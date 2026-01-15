package justfatlard.big_boats;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import justfatlard.big_boats.block.HelmBlock;
import justfatlard.big_boats.detection.DetectionResult;
import justfatlard.big_boats.detection.FloodFillDetector;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Throwable christening bottle projectile.
 * When it hits a valid ship (helm block with connected boatable blocks),
 * it christens the ship. Otherwise, it returns to item form with an error message.
 */
public class ChristeningBottleEntity extends ThrownItemEntity implements PolymerEntity {

	public ChristeningBottleEntity(EntityType<? extends ThrownItemEntity> entityType, World world) {
		super(entityType, world);
	}

	public ChristeningBottleEntity(World world, LivingEntity owner, ItemStack stack) {
		super(BigBoats.CHRISTENING_BOTTLE_ENTITY_TYPE, owner, world, stack);
	}

	public ChristeningBottleEntity(World world, double x, double y, double z, ItemStack stack) {
		super(BigBoats.CHRISTENING_BOTTLE_ENTITY_TYPE, x, y, z, world, stack);
	}

	@Override
	protected Item getDefaultItem() {
		return BigBoats.CHRISTENING_BOTTLE;
	}

	@Override
	public EntityType<?> getPolymerEntityType(PacketContext context) {
		// Appear as a thrown splash potion to vanilla clients
		return EntityType.SPLASH_POTION;
	}

	@Override
	protected void onCollision(HitResult hitResult) {
		super.onCollision(hitResult);

		World world = this.getEntityWorld();
		if (world.isClient()) {
			return;
		}

		ServerWorld serverWorld = (ServerWorld) world;
		BlockPos targetPos = null;

		// Determine what we hit
		if (hitResult.getType() == HitResult.Type.BLOCK) {
			BlockHitResult blockHit = (BlockHitResult) hitResult;
			targetPos = blockHit.getBlockPos();
		} else if (hitResult.getType() == HitResult.Type.ENTITY) {
			EntityHitResult entityHit = (EntityHitResult) hitResult;
			targetPos = entityHit.getEntity().getBlockPos();
		} else {
			// Missed everything - just drop the bottle
			failChristening(serverWorld, this.getBlockPos(), "Missed target");
			return;
		}

		// Try to find a helm block at or near the impact point
		BlockPos helmPos = findHelmNear(world, targetPos);

		if (helmPos == null) {
			failChristening(serverWorld, targetPos, "No helm block found - build a ship with a helm!");
			return;
		}

		// Get helm facing direction
		BlockState helmState = world.getBlockState(helmPos);
		Direction helmFacing = helmState.get(HelmBlock.FACING);

		// Run flood-fill detection
		DetectionResult result = FloodFillDetector.detect(world, helmPos);

		if (!result.success()) {
			String errorMessage = result.errorMessage().orElse("Unknown detection error");
			failChristening(serverWorld, targetPos, errorMessage);
			return;
		}

		// Check if ship is connected to land (has adjacent non-air/water blocks not part of ship)
		String landCheckError = checkConnectedToLand(world, helmPos, result);
		if (landCheckError != null) {
			failChristening(serverWorld, targetPos, landCheckError);
			return;
		}

		// Success! Christen the ship
		successChristening(serverWorld, helmPos, helmFacing, result);
	}

	/**
	 * Searches for a helm block at or adjacent to the given position.
	 */
	private BlockPos findHelmNear(World world, BlockPos pos) {
		if (pos == null) {
			return null;
		}

		// Check the hit position first
		if (world.getBlockState(pos).getBlock() instanceof HelmBlock) {
			return pos;
		}

		// Check adjacent positions
		for (Direction dir : Direction.values()) {
			BlockPos adjacent = pos.offset(dir);
			if (world.getBlockState(adjacent).getBlock() instanceof HelmBlock) {
				return adjacent;
			}
		}

		return null;
	}

	/**
	 * Checks if the detected ship is connected to land (non-ship, non-air, non-water blocks).
	 */
	private String checkConnectedToLand(World world, BlockPos helmPos, DetectionResult result) {
		for (ShipBlock block : result.blocks()) {
			BlockPos worldPos = block.relativePos().toWorldPos(helmPos);

			for (Direction dir : Direction.values()) {
				BlockPos adjacent = worldPos.offset(dir);
				BlockState adjacentState = world.getBlockState(adjacent);

				// Skip air and water
				if (adjacentState.isAir() || adjacentState.isOf(Blocks.WATER)) {
					continue;
				}

				// Check if this adjacent block is part of the ship
				boolean isPartOfShip = false;
				for (ShipBlock shipBlock : result.blocks()) {
					if (shipBlock.relativePos().toWorldPos(helmPos).equals(adjacent)) {
						isPartOfShip = true;
						break;
					}
				}

				if (!isPartOfShip) {
					return "Ship is connected to land - disconnect it first!";
				}
			}
		}
		return null;
	}

	/**
	 * Called when christening fails - drops the bottle and notifies the player.
	 */
	private void failChristening(ServerWorld world, BlockPos pos, String errorMessage) {
		// Play break sound
		world.playSound(
			null,
			this.getX(), this.getY(), this.getZ(),
			SoundEvents.BLOCK_GLASS_BREAK,
			SoundCategory.PLAYERS,
			1.0F, 0.8F
		);

		// Spawn failure particles
		world.spawnParticles(
			ParticleTypes.SPLASH,
			this.getX(), this.getY(), this.getZ(),
			15, 0.3, 0.3, 0.3, 0.1
		);

		// Drop the bottle as an item
		this.dropItem(world, BigBoats.CHRISTENING_BOTTLE);

		// Send error message to the thrower
		if (this.getOwner() instanceof ServerPlayerEntity player) {
			player.sendMessage(
				Text.literal("Christening failed: " + errorMessage)
					.formatted(Formatting.RED),
				false
			);
		}

		this.discard();
	}

	/**
	 * Called when christening succeeds - converts the structure to a ship entity.
	 */
	private void successChristening(ServerWorld world, BlockPos helmPos, Direction helmFacing, DetectionResult result) {
		// Play christening sounds
		world.playSound(
			null,
			helmPos.getX() + 0.5, helmPos.getY() + 0.5, helmPos.getZ() + 0.5,
			SoundEvents.ENTITY_SPLASH_POTION_BREAK,
			SoundCategory.PLAYERS,
			1.0F, 1.0F
		);

		world.playSound(
			null,
			helmPos.getX() + 0.5, helmPos.getY() + 0.5, helmPos.getZ() + 0.5,
			SoundEvents.ENTITY_PLAYER_LEVELUP,
			SoundCategory.PLAYERS,
			0.5F, 1.2F
		);

		// Remove all detected blocks and spawn particles
		for (ShipBlock block : result.blocks()) {
			BlockPos worldPos = block.relativePos().toWorldPos(helmPos);
			world.setBlockState(worldPos, Blocks.AIR.getDefaultState());

			world.spawnParticles(
				ParticleTypes.SPLASH,
				worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5,
				5, 0.3, 0.3, 0.3, 0.1
			);
		}

		// Spawn the multi-block ship entity at helm block corner
		// Rotation math will rotate around helm center (+0.5, +0.5)
		MultiBlockShipEntity ship = new MultiBlockShipEntity(
			world,
			helmPos.getX(),
			helmPos.getY(),
			helmPos.getZ(),
			result.blocks(),
			helmFacing
		);
		world.spawnEntity(ship);

		// Notify the player
		if (this.getOwner() instanceof ServerPlayerEntity player) {
			player.sendMessage(
				Text.literal("Ship launched with " + result.blockCount() + " blocks!")
					.formatted(Formatting.GREEN),
				false
			);
		}

		this.discard();
	}
}
