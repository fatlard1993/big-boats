package justfatlard.big_boats.detection;

import justfatlard.big_boats.ship.ShipBlock;
import justfatlard.big_boats.ship.ShipConfig;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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
	/**
	 * @param maxBlocks what the helm at {@code helmPos} is rated to hold together; see
	 *                  {@code ShipConfig.capacityForTonnage}
	 */
	public static DetectionResult detect(Level world, BlockPos helmPos, int maxBlocks) {
		List<ShipBlock> blocks = new ArrayList<>();
		Set<BlockPos> visited = new HashSet<>();
		Queue<BlockPos> queue = new ArrayDeque<>();

		visited.add(helmPos);
		queue.add(helmPos);

		while (!queue.isEmpty() && blocks.size() < maxBlocks) {
			BlockPos pos = queue.poll();

			// Skip positions in unloaded chunks: getBlockState returns air there,
			// which would silently truncate ships at chunk borders
			if (!world.isLoaded(pos)) {
				continue;
			}

			BlockState state = world.getBlockState(pos);

			if (!ShipBlockUtils.isShipEligible(state)) {
				continue;
			}

			blocks.add(ShipBlock.fromWorld(world, pos, helmPos));

			// Mark adjacent positions visited at enqueue time to prevent queue pollution
			for (Direction direction : Direction.values()) {
				BlockPos adjacent = pos.relative(direction);
				if (!visited.contains(adjacent)) {
					visited.add(adjacent);
					queue.add(adjacent);
				}
			}
		}

		if (blocks.isEmpty()) {
			return new DetectionResult.NoBlocks();
		}

		if (blocks.size() < ShipConfig.MIN_BLOCKS) {
			return new DetectionResult.TooSmall(blocks.size(), ShipConfig.MIN_BLOCKS);
		}

		// If we hit the block limit with unexplored territory, the structure exceeds max size
		if (blocks.size() >= maxBlocks && !queue.isEmpty()) {
			return new DetectionResult.TooLarge(maxBlocks);
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
	public static GroundingResult detectGrounding(Level world, Set<BlockPos> shipPositions, int currentShipSize, BlockPos referencePos) {
		int availableCapacity = ShipConfig.MAX_BLOCKS - currentShipSize;

		// Find all solid blocks the ship actually touches that aren't part of the ship.
		//
		// Sharing a face is not the same as touching: a bottom slab and the top slab beside it
		// are neighbours whose shapes never meet, and so is anything sitting a block above a
		// bottom slab. Those read as clear water to the eye and used to read as land to the ship,
		// so the two shapes are asked whether they share any area across the face between them.
		Set<BlockPos> adjacentSolids = new HashSet<>();
		for (BlockPos shipPos : shipPositions) {
			VoxelShape ours = world.getBlockState(shipPos).getShape(world, shipPos);
			for (Direction direction : Direction.values()) {
				BlockPos adjacent = shipPos.relative(direction);
				if (shipPositions.contains(adjacent)) continue;
				BlockState state = world.getBlockState(adjacent);
				if (!ShipBlockUtils.isShipEligible(state)) continue;
				if (touches(ours, direction, state.getShape(world, adjacent))) {
					adjacentSolids.add(adjacent);
				}
			}
		}

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
				BlockPos adjacent = pos.relative(direction);
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
	 * Whether two shapes on either side of a face meet across it with some area, not just an
	 * edge or a corner. An empty face - a bottom slab's top - meets nothing.
	 */
	private static boolean touches(VoxelShape ours, Direction toward, VoxelShape theirs) {
		return Shapes.joinIsNotEmpty(ours.getFaceShape(toward), theirs.getFaceShape(toward.getOpposite()), BooleanOp.AND);
	}

	/**
	 * BFS through connected boatable blocks to find one matching the predicate.
	 *
	 * @param world The world to search in
	 * @param startPos Starting position for the search
	 * @param predicate Test applied to each block's state
	 * @return The position of the first matching block, or null if not found
	 */
	public static BlockPos findBlock(Level world, BlockPos startPos, Predicate<BlockState> predicate) {
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
					BlockPos neighbor = pos.relative(dir);
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
