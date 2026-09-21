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
	 * Walks out from the helm and returns everything solid connected to it.
	 *
	 * @param world the world to search
	 * @param helmPos the helm to start from
	 * @param maxBlocks what that helm is rated to hold together; see
	 *                  {@link ShipConfig#capacityForTonnage}
	 * @return the connected blocks, or why they could not be counted
	 */
	public static DetectionResult detect(Level world, BlockPos helmPos, int maxBlocks) {
		List<ShipBlock> blocks = new ArrayList<>();
		Set<BlockPos> visited = new HashSet<>();
		Queue<BlockPos> queue = new ArrayDeque<>();

		visited.add(helmPos);
		queue.add(helmPos);

		// Counted on past what the helm holds, only so a refusal can say by how much: blocks beyond
		// the capacity are counted but never built into ShipBlocks. Too large means more blocks than
		// the capacity, not a queue left over - the queue always ends holding the air round the hull,
		// so a ship of exactly the size its helm holds would read as too big.
		int found = 0;
		while (!queue.isEmpty() && found <= ShipConfig.SIZE_REPORT_LIMIT) {
			BlockPos pos = queue.poll();

			// Stop, rather than step over it. Skipping the position skipped its neighbours too,
			// so the walk simply ended at the chunk border and returned a partial ship that no
			// caller could tell from a whole one - the truncation the old comment here claimed
			// to be preventing.
			if (!world.isLoaded(pos)) {
				return new DetectionResult.Unloaded(pos);
			}

			BlockState state = world.getBlockState(pos);

			if (!ShipBlockUtils.isShipEligible(state)) {
				continue;
			}

			found++;
			if (found <= maxBlocks) {
				blocks.add(ShipBlock.fromWorld(world, pos, helmPos));
			}

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

		if (found > maxBlocks) {
			return new DetectionResult.TooLarge(maxBlocks, found);
		}

		LOGGER.debug("Detected ship: {} blocks from helm at {}", blocks.size(), helmPos);
		return new DetectionResult.Success(blocks);
	}

	/**
	 * Whether this ship is resting on something it cannot simply sail off.
	 *
	 * <p>A verdict, not a list of blocks: every {@link GroundingResult} variant is an empty
	 * record, and what actually gets absorbed is decided later, by the rescan.
	 *
	 * @param world The world to search in
	 * @param shipPositions The current world positions of ship blocks
	 * @param currentShipSize Current number of blocks in the ship
	 * @param maxBlocks what this ship's helm is rated to hold, the same figure {@code detect} is
	 *                  given. Measured against the global ceiling instead, a plain helm rated for
	 *                  a hundred blocks was told it had room for nineteen hundred more, so a
	 *                  player was cleared to leave and then lost the surplus at the rescan cap.
	 * @param referencePos A reference position for calculating relative positions (usually helm)
	 * @return GroundingResult indicating grounding status and any absorbable blocks
	 */
	public static GroundingResult detectGrounding(Level world, Set<BlockPos> shipPositions,
			int currentShipSize, int maxBlocks, BlockPos referencePos) {
		int availableCapacity = maxBlocks - currentShipSize;

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

		// Counted apart from `visited`, which starts holding the whole ship: sharing them gave a
		// fifty-block ship four thousand positions of exploration and a two-thousand-block ship
		// two thousand, so grounding detection was least reliable for the ships most likely to
		// be aground.
		int explored = 0;
		while (!queue.isEmpty() && explored < ShipConfig.MAX_BLOCKS * 2) {
			explored++;
			BlockPos pos = queue.poll();

			if (Math.abs(pos.getY() - referencePos.getY()) > ShipConfig.MAX_GROUNDING_Y_RANGE) {
				continue;
			}

			// Unloaded ground is not ground we can judge, and reading it would load the chunk:
			// getBlockState loads if absent, so an unguarded walk of a few thousand positions can
			// generate terrain on the server thread while a player waits.
			if (!world.isLoaded(pos)) {
				continue;
			}

			BlockState state = world.getBlockState(pos);

			if (!ShipBlockUtils.isShipEligible(state)) {
				continue;
			}

			connectedCount++;
			// Strictly greater: a landmass of exactly the capacity left fits, and refusing it
			// told a player their ship was too full for a rock it had room for.
			if (connectedCount > availableCapacity) {
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
	 * What a search for one block turned up: the block, or nothing - and if nothing, whether the
	 * structure ran out or the search did.
	 */
	public record Search(BlockPos found, boolean gaveUp) {}

	/**
	 * BFS through connected boatable blocks to find one matching the predicate.
	 *
	 * <p>Bounded by the blocks walked rather than the positions looked at, and as far as a ship is
	 * ever counted: the air round a big hull outnumbers its blocks, so a bound on positions gives up
	 * short of a helm the size count would reach, and a bottle thrown at the far end of a ship too
	 * big for its helm would be told there is no helm.
	 *
	 * @param world The world to search in
	 * @param startPos Starting position for the search
	 * @param predicate Test applied to each block's state
	 */
	public static Search findBlock(Level world, BlockPos startPos, Predicate<BlockState> predicate) {
		Queue<BlockPos> queue = new ArrayDeque<>();
		Set<BlockPos> visited = new HashSet<>();
		visited.add(startPos);
		queue.add(startPos);
		int walked = 0;

		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();

			BlockState state = world.getBlockState(pos);

			if (predicate.test(state)) {
				return new Search(pos, false);
			}

			if (ShipBlockUtils.isShipEligible(state)) {
				if (++walked > ShipConfig.SIZE_REPORT_LIMIT) {
					return new Search(null, true);
				}
				for (Direction dir : Direction.values()) {
					BlockPos neighbor = pos.relative(dir);
					if (!visited.contains(neighbor)) {
						visited.add(neighbor);
						queue.add(neighbor);
					}
				}
			}
		}

		return new Search(null, false);
	}
}
