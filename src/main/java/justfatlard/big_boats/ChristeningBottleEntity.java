package justfatlard.big_boats;

import justfatlard.big_boats.block.HelmBlock;
import justfatlard.big_boats.detection.DetectionResult;
import justfatlard.big_boats.detection.FloodFillDetector;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipBlock;
import justfatlard.big_boats.ship.ShipConfig;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Throwable christening bottle projectile.
 * When it hits a valid ship (helm block with connected solid blocks),
 * it christens the ship. Otherwise, it returns to item form with an error message.
 *
 * <p>Rendered to Pandorical clients as a normal thrown item via
 * {@code PandoricalApi.registerEntityRenderer(..., "thrown_item")}; see
 * {@link BigBoats#onInitialize}.</p>
 */
public class ChristeningBottleEntity extends ThrowableItemProjectile {
	private static final Logger LOGGER = LoggerFactory.getLogger(ChristeningBottleEntity.class);

	public ChristeningBottleEntity(EntityType<? extends ThrowableItemProjectile> entityType, Level world) {
		super(entityType, world);
	}

	public ChristeningBottleEntity(Level world, LivingEntity owner, ItemStack stack) {
		super(BigBoats.CHRISTENING_BOTTLE_ENTITY_TYPE, owner, world, stack);
	}

	public ChristeningBottleEntity(Level world, double x, double y, double z, ItemStack stack) {
		super(BigBoats.CHRISTENING_BOTTLE_ENTITY_TYPE, x, y, z, world, stack);
	}

	@Override
	protected Item getDefaultItem() {
		return BigBoats.CHRISTENING_BOTTLE;
	}

	@Override
	protected void onHit(HitResult hitResult) {
		super.onHit(hitResult);

		Level world = this.level();
		if (world.isClientSide()) {
			return;
		}

		ServerLevel serverWorld = (ServerLevel) world;
		BlockPos targetPos;

		if (hitResult instanceof BlockHitResult blockHit) {
			targetPos = blockHit.getBlockPos();
		} else if (hitResult instanceof EntityHitResult entityHit) {
			targetPos = entityHit.getEntity().blockPosition();
		} else {
			failChristening(serverWorld, this.blockPosition(), "Missed target");
			return;
		}

		BlockPos helmPos = findHelmInStructure(world, targetPos);

		if (helmPos == null) {
			failChristening(serverWorld, targetPos, "No helm block found - build a ship with a helm!");
			return;
		}

		BlockState helmState = world.getBlockState(helmPos);
		Direction helmFacing = helmState.getValue(HelmBlock.FACING);

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
		for (MultiBlockShipEntity existingShip : serverWorld.getEntities(
				EntityTypeTest.forClass(MultiBlockShipEntity.class),
				new AABB(helmPos).inflate(ShipConfig.SHIP_OVERLAP_SEARCH_RANGE),
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
	private BlockPos findHelmInStructure(Level world, BlockPos pos) {
		if (pos == null) {
			return null;
		}

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
				BlockPos adjacent = pos.relative(dir);
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
	 * Called when christening fails: drops the bottle and notifies the player.
	 */
	private void failChristening(ServerLevel world, BlockPos pos, String errorMessage) {
		world.playSound(
			null,
			this.getX(), this.getY(), this.getZ(),
			SoundEvents.GLASS_BREAK,
			SoundSource.PLAYERS,
			1.0F, 0.8F
		);

		world.sendParticles(
			ParticleTypes.SMOKE,
			this.getX(), this.getY(), this.getZ(),
			10, 0.2, 0.2, 0.2, 0.02
		);

		this.spawnAtLocation(world, BigBoats.CHRISTENING_BOTTLE);

		LOGGER.debug("Christening failed at {}: {}", pos, errorMessage);

		if (this.getOwner() instanceof ServerPlayer player) {
			player.sendSystemMessage(
				Component.translatable("big-boats.christening.fail", errorMessage)
					.withStyle(ChatFormatting.RED),
				false
			);
		}

		this.discard();
	}

	/**
	 * Called when christening succeeds: converts the structure to a ship entity.
	 */
	private void successChristening(ServerLevel world, BlockPos helmPos, Direction helmFacing, DetectionResult.Success result) {
		world.playSound(
			null,
			helmPos.getX() + 0.5, helmPos.getY() + 0.5, helmPos.getZ() + 0.5,
			SoundEvents.SPLASH_POTION_BREAK,
			SoundSource.PLAYERS,
			1.0F, 1.0F
		);

		world.playSound(
			null,
			helmPos.getX() + 0.5, helmPos.getY() + 0.5, helmPos.getZ() + 0.5,
			SoundEvents.PLAYER_LEVELUP,
			SoundSource.PLAYERS,
			0.5F, 1.2F
		);

		for (ShipBlock block : result.blocks()) {
			BlockPos worldPos = block.relativePos().toWorldPos(helmPos);
			world.sendParticles(
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
		ItemStack bottleStack = this.getItem();
		String shipName = null;
		if (bottleStack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
			Component customName = bottleStack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
			if (customName != null) {
				shipName = customName.getString();
				ship.setShipName(shipName);
			}
		}

		// Initialize ship BEFORE spawning to prevent tick() firing on uninitialized state
		ship.initializeShip(helmPos);
		world.addFreshEntity(ship);

		if (this.getOwner() instanceof ServerPlayer player) {
			Component message = shipName != null
				? Component.translatable("big-boats.christening.success_named", shipName, result.blockCount())
				: Component.translatable("big-boats.christening.success", result.blockCount());
			player.sendSystemMessage(message.copy().withStyle(ChatFormatting.GREEN), false);
		}

		this.discard();
	}
}
