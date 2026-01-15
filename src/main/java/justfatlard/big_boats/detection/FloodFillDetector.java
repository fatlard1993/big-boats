package justfatlard.big_boats.detection;

import justfatlard.big_boats.ship.ShipBlock;
import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Detects connected blocks for ship construction using BFS flood-fill algorithm.
 */
public class FloodFillDetector {
	public static final int MAX_BLOCKS = 2000;
	public static final int MIN_BLOCKS = 2; // Helm + at least one other block

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

			// Add this block to the ship
			RelativeBlockPos relativePos = RelativeBlockPos.fromWorldPos(pos, helmPos);
			blocks.add(new ShipBlock(relativePos, state));

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
	 * Any solid block (not air, not liquid) can be part of a ship.
	 */
	private static boolean isBoatableBlock(BlockState state) {
		// Allow any solid block - no arbitrary material restrictions
		return !state.isAir() && !state.isLiquid();
	}
}
