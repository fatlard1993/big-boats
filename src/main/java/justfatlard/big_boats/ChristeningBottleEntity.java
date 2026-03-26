package justfatlard.big_boats;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import justfatlard.big_boats.block.HelmBlock;
import justfatlard.big_boats.detection.DetectionResult;
import justfatlard.big_boats.detection.FloodFillDetector;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipBlock;
import justfatlard.big_boats.ship.ShipConfig;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.block.BlockState;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Throwable christening bottle projectile.
 * When it hits a valid ship (helm block with connected solid blocks),
 * it christens the ship. Otherwise, it returns to item form with an error message.
 */
public class ChristeningBottleEntity extends ThrownItemEntity implements PolymerEntity {
	private static final Logger LOGGER = LoggerFactory.getLogger(ChristeningBottleEntity.class);

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
		BlockPos helmPos = findHelmInStructure(world, targetPos);

		if (helmPos == null) {
			failChristening(serverWorld, targetPos, "No helm block found - build a ship with a helm!");
			return;
		}

		// Get helm facing direction
		BlockState helmState = world.getBlockState(helmPos);
		Direction helmFacing = helmState.get(HelmBlock.FACING);

		// Run flood-fill detection
		DetectionResult result = FloodFillDetector.detect(world, helmPos);

		if (!(result instanceof DetectionResult.Success success)) {
			String reason = result instanceof DetectionResult.Failure failure
				? failure.message() : "Detection failed";
			failChristening(serverWorld, targetPos, reason);
			return;
		}

		// Check if any detected block overlaps with an existing ship
		Set<BlockPos> shipPositions = new HashSet<>();
		for (ShipBlock block : success.blocks()) {
			shipPositions.add(block.relativePos().toWorldPos(helmPos));
		}
		for (MultiBlockShipEntity existingShip : serverWorld.getEntitiesByClass(
				MultiBlockShipEntity.class,
				new Box(helmPos).expand(ShipConfig.SHIP_OVERLAP_SEARCH_RANGE),
				MultiBlockShipEntity::isDocked)) {
			for (BlockPos existingPos : existingShip.getDockedBlockPositions()) {
				if (shipPositions.contains(existingPos)) {
					failChristening(serverWorld, targetPos, "These blocks are already part of a ship!");
					return;
				}
			}
		}
		var groundingResult = FloodFillDetector.detectGrounding(serverWorld, shipPositions, success.blockCount(), helmPos);
		if (!groundingResult.canUndock()) {
			failChristening(serverWorld, targetPos, "Ship is connected to land - disconnect it first!");
			return;
		}

		LOGGER.info("Christening ship at {} with {} blocks", helmPos, success.blockCount());
		successChristening(serverWorld, helmPos, helmFacing, success);
	}

	/**
	 * Searches for a helm block by BFS through connected ship-eligible blocks.
	 * This allows hitting any part of the ship structure to christen it.
	 */
	private BlockPos findHelmInStructure(World world, BlockPos pos) {
		if (pos == null) {
			return null;
		}

		// Check the hit position first
		BlockState hitState = world.getBlockState(pos);
		if (hitState.getBlock() instanceof HelmBlock) {
			return pos;
		}

		// If hit block isn't a valid ship block, check adjacent positions for a starting point
		BlockPos startPos = null;
		if (ShipBlockUtils.isShipEligible(hitState)) {
			startPos = pos;
		} else {
			for (Direction dir : Direction.values()) {
				BlockPos adjacent = pos.offset(dir);
				BlockState adjacentState = world.getBlockState(adjacent);
				if (adjacentState.getBlock() instanceof HelmBlock) {
					return adjacent;
				}
				if (ShipBlockUtils.isShipEligible(adjacentState)) {
					startPos = adjacent;
					break;
				}
			}
		}

		if (startPos == null) {
			return null;
		}

		return FloodFillDetector.findBlock(world, startPos, state -> state.getBlock() instanceof HelmBlock);
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

		// Spawn failure particles (smoke puff)
		world.spawnParticles(
			ParticleTypes.SMOKE,
			this.getX(), this.getY(), this.getZ(),
			10, 0.2, 0.2, 0.2, 0.02
		);

		// Drop the bottle as an item
		this.dropItem(world, BigBoats.CHRISTENING_BOTTLE);

		LOGGER.debug("Christening failed at {}: {}", pos, errorMessage);

		// Send error message to the thrower
		if (this.getOwner() instanceof ServerPlayerEntity player) {
			player.sendMessage(
				Text.translatable("big-boats.christening.fail", errorMessage)
					.formatted(Formatting.RED),
				false
			);
		}

		this.discard();
	}

	/**
	 * Called when christening succeeds - converts the structure to a ship entity.
	 */
	private void successChristening(ServerWorld world, BlockPos helmPos, Direction helmFacing, DetectionResult.Success result) {
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

		// Spawn particles at block positions
		for (ShipBlock block : result.blocks()) {
			BlockPos worldPos = block.relativePos().toWorldPos(helmPos);
			world.spawnParticles(
				ParticleTypes.HAPPY_VILLAGER,
				worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5,
				3, 0.3, 0.3, 0.3, 0.0
			);
		}

		// Spawn the multi-block ship entity at helm block corner
		MultiBlockShipEntity ship = new MultiBlockShipEntity(
			world,
			helmPos.getX(),
			helmPos.getY(),
			helmPos.getZ(),
			result.blocks(),
			helmFacing
		);

		// Transfer custom name from christening bottle to ship
		ItemStack bottleStack = this.getStack();
		String shipName = null;
		if (bottleStack.contains(net.minecraft.component.DataComponentTypes.CUSTOM_NAME)) {
			Text customName = bottleStack.get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME);
			if (customName != null) {
				shipName = customName.getString();
				ship.setShipName(shipName);
			}
		}

		// Initialize ship BEFORE spawning to prevent tick() firing on uninitialized state
		ship.initializeShip(helmPos);
		world.spawnEntity(ship);

		// Notify the player with ship name if present
		if (this.getOwner() instanceof ServerPlayerEntity player) {
			Text message = shipName != null
				? Text.translatable("big-boats.christening.success_named", shipName, result.blockCount())
				: Text.translatable("big-boats.christening.success", result.blockCount());
			player.sendMessage(message.copy().formatted(Formatting.GREEN), false);
		}

		this.discard();
	}
}
