package justfatlard.big_boats.detection;

import justfatlard.big_boats.ship.ShipBlock;
import justfatlard.big_boats.ship.ShipConfig;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects connected blocks for ship construction using BFS flood-fill algorithm.
 */
public class FloodFillDetector {
	private static final Logger LOGGER = LoggerFactory.getLogger(FloodFillDetector.class);

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
		Queue<BlockPos> queue = new ArrayDeque<>();

		visited.add(helmPos);
		queue.add(helmPos);

		while (!queue.isEmpty() && blocks.size() < ShipConfig.MAX_BLOCKS) {
			BlockPos pos = queue.poll();

			// Skip positions in unloaded chunks — getBlockState returns air for
			// unloaded chunks, which would silently truncate ships at chunk borders
			if (!world.isChunkLoaded(pos)) {
				continue;
			}

			BlockState state = world.getBlockState(pos);

			// Check if this block is valid for ship construction
			if (!ShipBlockUtils.isShipEligible(state)) {
				continue;
			}

			// Add this block to the ship, including any block entity data
			blocks.add(ShipBlock.fromWorld(world, pos, helmPos));

			// Mark adjacent positions visited at enqueue time to prevent queue pollution
			for (Direction direction : Direction.values()) {
				BlockPos adjacent = pos.offset(direction);
				if (!visited.contains(adjacent)) {
					visited.add(adjacent);
					queue.add(adjacent);
				}
			}
		}

		// Validate result
		if (blocks.isEmpty()) {
			return new DetectionResult.NoBlocks();
		}

		if (blocks.size() < ShipConfig.MIN_BLOCKS) {
			return new DetectionResult.TooSmall(blocks.size(), ShipConfig.MIN_BLOCKS);
		}

		// If we hit the block limit with unexplored territory, the structure exceeds max size
		if (blocks.size() >= ShipConfig.MAX_BLOCKS && !queue.isEmpty()) {
			return new DetectionResult.TooLarge();
		}

		LOGGER.debug("Detected ship: {} blocks from helm at {}", blocks.size(), helmPos);
		return new DetectionResult.Success(blocks);
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
		int availableCapacity = ShipConfig.MAX_BLOCKS - currentShipSize;

		// Find all solid blocks adjacent to the ship that aren't part of the ship
		Set<BlockPos> adjacentSolids = new HashSet<>();
		for (BlockPos shipPos : shipPositions) {
			for (Direction direction : Direction.values()) {
				BlockPos adjacent = shipPos.offset(direction);
				if (!shipPositions.contains(adjacent)) {
					BlockState state = world.getBlockState(adjacent);
					if (ShipBlockUtils.isShipEligible(state)) {
						adjacentSolids.add(adjacent);
					}
				}
			}
		}

		// No adjacent solid blocks - ship is floating freely
		if (adjacentSolids.isEmpty()) {
			return new GroundingResult.FreeFloating();
		}

		// Flood-fill from adjacent solids to measure connected landmass size.
		// Only counts blocks (no ShipBlock/NBT construction) since the result is pass/fail.
		int connectedCount = 0;
		Set<BlockPos> visited = new HashSet<>(shipPositions); // Treat ship positions as already visited
		Queue<BlockPos> queue = new ArrayDeque<>();

		for (BlockPos adj : adjacentSolids) {
			if (!visited.contains(adj)) {
				visited.add(adj);
				queue.add(adj);
			}
		}

		while (!queue.isEmpty() && visited.size() < ShipConfig.MAX_BLOCKS * 2) {
			BlockPos pos = queue.poll();

			if (Math.abs(pos.getY() - referencePos.getY()) > ShipConfig.MAX_GROUNDING_Y_RANGE) {
				continue;
			}

			BlockState state = world.getBlockState(pos);

			if (!ShipBlockUtils.isShipEligible(state)) {
				continue;
			}

			connectedCount++;
			if (connectedCount >= availableCapacity) {
				return new GroundingResult.GroundedTooLarge();
			}

			for (Direction direction : Direction.values()) {
				BlockPos adjacent = pos.offset(direction);
				if (!visited.contains(adjacent)) {
					visited.add(adjacent);
					queue.add(adjacent);
				}
			}
		}

		if (!queue.isEmpty()) {
			return new GroundingResult.GroundedMassive();
		}

		if (connectedCount > 0) {
			return new GroundingResult.TouchingTerrain();
		}

		return new GroundingResult.FreeFloating();
	}

	/**
	 * BFS through connected boatable blocks to find one matching the predicate.
	 *
	 * @param world The world to search in
	 * @param startPos Starting position for the search
	 * @param predicate Test applied to each block's state
	 * @return The position of the first matching block, or null if not found
	 */
	public static BlockPos findBlock(World world, BlockPos startPos, Predicate<BlockState> predicate) {
		Queue<BlockPos> queue = new ArrayDeque<>();
		Set<BlockPos> visited = new HashSet<>();
		visited.add(startPos);
		queue.add(startPos);

		while (!queue.isEmpty() && visited.size() < ShipConfig.MAX_BLOCKS) {
			BlockPos pos = queue.poll();

			BlockState state = world.getBlockState(pos);

			if (predicate.test(state)) {
				return pos;
			}

			if (ShipBlockUtils.isShipEligible(state)) {
				for (Direction dir : Direction.values()) {
					BlockPos neighbor = pos.offset(dir);
					if (!visited.contains(neighbor)) {
						visited.add(neighbor);
						queue.add(neighbor);
					}
				}
			}
		}

		return null;
	}
}
