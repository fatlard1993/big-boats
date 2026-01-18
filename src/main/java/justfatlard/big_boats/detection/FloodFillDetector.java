package justfatlard.big_boats.detection;

import justfatlard.big_boats.ship.ShipBlock;
import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Detects connected blocks for ship construction using BFS flood-fill algorithm.
 */
public class FloodFillDetector {
	public static final int MAX_BLOCKS = 2000;
	public static final int MIN_BLOCKS = 2; // Helm + at least one other block

	/**
	 * Result of grounding detection.
	 */
	public record GroundingResult(boolean isGrounded, List<ShipBlock> connectedBlocks, String message) {
		public static GroundingResult grounded(String message) {
			return new GroundingResult(true, List.of(), message);
		}

		public static GroundingResult notGrounded() {
			return new GroundingResult(false, List.of(), null);
		}

		public static GroundingResult absorbable(List<ShipBlock> blocks) {
			return new GroundingResult(false, blocks, null);
		}
	}

	/**
	 * Performs flood-fill detection starting from the helm position.
	 * Includes any solid (non-air, non-liquid) connected block.
	 *
	 * @param world The world to search in
	 * @param helmPos The starting position (helm block)
	 * @return DetectionResult containing all connected blocks or an error
	 */
	public static DetectionResult detect(World world, BlockPos helmPos) {
		List<ShipBlock> blocks = new ArrayList<>();
		Set<BlockPos> visited = new HashSet<>();
		Queue<BlockPos> queue = new LinkedList<>();

		// Start from helm position
		queue.add(helmPos);

		while (!queue.isEmpty() && blocks.size() < MAX_BLOCKS) {
			BlockPos pos = queue.poll();

			// Skip if already visited
			if (visited.contains(pos)) {
				continue;
			}
			visited.add(pos);

			BlockState state = world.getBlockState(pos);

			// Check if this block is valid for ship construction
			if (!isBoatableBlock(state)) {
				continue;
			}

			// Add this block to the ship, including any block entity data
			RelativeBlockPos relativePos = RelativeBlockPos.fromWorldPos(pos, helmPos);
			Optional<NbtCompound> blockEntityData = Optional.empty();

			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity != null) {
				// Save block entity NBT data (for chests, furnaces, signs, etc.)
				NbtCompound nbt = blockEntity.createNbtWithIdentifyingData(world.getRegistryManager());
				blockEntityData = Optional.of(nbt);
			}

			blocks.add(new ShipBlock(relativePos, state, blockEntityData));

			// Add all adjacent positions to the queue
			for (Direction direction : Direction.values()) {
				BlockPos adjacent = pos.offset(direction);
				if (!visited.contains(adjacent)) {
					queue.add(adjacent);
				}
			}
		}

		// Validate result
		if (blocks.isEmpty()) {
			return DetectionResult.failure("No valid blocks found at helm position");
		}

		if (blocks.size() < MIN_BLOCKS) {
			return DetectionResult.failure("Ship too small (minimum " + MIN_BLOCKS + " blocks required)");
		}

		if (blocks.size() >= MAX_BLOCKS) {
			return DetectionResult.failure("Ship too large (maximum " + MAX_BLOCKS + " blocks allowed)");
		}

		return DetectionResult.success(blocks);
	}

	/**
	 * Checks if a block state is valid for ship construction.
	 * Excludes air, liquids, and fragile/plant blocks that ships break through.
	 */
	private static boolean isBoatableBlock(BlockState state) {
		if (state.isAir() || state.isLiquid()) {
			return false;
		}

		// Exclude fragile/plant blocks that ships can break through
		Block block = state.getBlock();
		if (block instanceof SeagrassBlock
			|| block instanceof TallSeagrassBlock
			|| block instanceof KelpBlock
			|| block instanceof KelpPlantBlock
			|| block instanceof LilyPadBlock
			|| block instanceof TallPlantBlock
			|| block instanceof FlowerBlock
			|| block instanceof TallFlowerBlock
			|| block instanceof SugarCaneBlock
			|| block instanceof VineBlock
			|| block instanceof SnowBlock
			|| block instanceof CobwebBlock) {
			return false;
		}

		// Also exclude blocks that are instantly breakable (hardness 0)
		if (state.getHardness(null, null) == 0.0f) {
			return false;
		}

		return true;
	}

	/**
	 * Detects if a ship at the given positions is grounded (connected to land).
	 * If connected to a small mass (<= available capacity), returns those blocks to absorb.
	 * If connected to a large mass, returns grounded status.
	 *
	 * @param world The world to search in
	 * @param shipPositions The current world positions of ship blocks
	 * @param currentShipSize Current number of blocks in the ship
	 * @param referencePos A reference position for calculating relative positions (usually helm)
	 * @return GroundingResult indicating grounding status and any absorbable blocks
	 */
	public static GroundingResult detectGrounding(World world, Set<BlockPos> shipPositions, int currentShipSize, BlockPos referencePos) {
		int availableCapacity = MAX_BLOCKS - currentShipSize;

		// Find all solid blocks adjacent to the ship that aren't part of the ship
		Set<BlockPos> adjacentSolids = new HashSet<>();
		for (BlockPos shipPos : shipPositions) {
			for (Direction direction : Direction.values()) {
				BlockPos adjacent = shipPos.offset(direction);
				if (!shipPositions.contains(adjacent)) {
					BlockState state = world.getBlockState(adjacent);
					if (isBoatableBlock(state)) {
						adjacentSolids.add(adjacent);
					}
				}
			}
		}

		// No adjacent solid blocks - ship is floating freely
		if (adjacentSolids.isEmpty()) {
			return GroundingResult.notGrounded();
		}

		// Flood-fill from adjacent solids to find connected landmass
		List<ShipBlock> connectedBlocks = new ArrayList<>();
		Set<BlockPos> visited = new HashSet<>(shipPositions); // Treat ship positions as already visited
		Queue<BlockPos> queue = new LinkedList<>(adjacentSolids);

		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();

			if (visited.contains(pos)) {
				continue;
			}
			visited.add(pos);

			BlockState state = world.getBlockState(pos);

			if (!isBoatableBlock(state)) {
				continue;
			}

			// Check if we've exceeded capacity
			if (connectedBlocks.size() >= availableCapacity) {
				return GroundingResult.grounded("Ship is grounded - connected to landmass too large to absorb");
			}

			// Add this block to the connected set
			RelativeBlockPos relativePos = RelativeBlockPos.fromWorldPos(pos, referencePos);
			Optional<NbtCompound> blockEntityData = Optional.empty();

			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity != null) {
				NbtCompound nbt = blockEntity.createNbtWithIdentifyingData(world.getRegistryManager());
				blockEntityData = Optional.of(nbt);
			}

			connectedBlocks.add(new ShipBlock(relativePos, state, blockEntityData));

			// Add adjacent positions to queue
			for (Direction direction : Direction.values()) {
				BlockPos adjacent = pos.offset(direction);
				if (!visited.contains(adjacent)) {
					queue.add(adjacent);
				}
			}
		}

		// If we found connected blocks but didn't exceed capacity, they can be absorbed
		if (!connectedBlocks.isEmpty()) {
			return GroundingResult.absorbable(connectedBlocks);
		}

		return GroundingResult.notGrounded();
	}
}
