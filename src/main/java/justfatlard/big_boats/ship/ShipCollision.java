package justfatlard.big_boats.ship;

import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles ship collision detection and hull management.
 * Computes which blocks are on the exterior hull and performs collision checks.
 */
public class ShipCollision {
	private static final Logger LOGGER = LoggerFactory.getLogger(ShipCollision.class);
	private Set<RelativeBlockPos> hullBlocks = new HashSet<>();

	private static final int[][] NEIGHBOR_OFFSETS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	// Sample offsets for collision checking, from each hull block's centre to the corners of its
	// volume. 0.49 instead of 0.5 to avoid sampling exactly on block boundaries where
	// floating-point edge cases cause false positives from adjacent blocks.
	// Two offsets per axis (2x2x2 = 8 samples) catches all block positions a
	// hull block can overlap. The center (0) is redundant; corner samples always
	// cover the center block position.
	/**
	 * Where a hull block is sampled, as a fraction of its half-width.
	 *
	 * <p>Just inside its own faces, so a block resting exactly against another does not read as
	 * inside it. Scaled by {@link #rotatedHalfWidth} before use: a unit square turned forty-five
	 * degrees is 1.414 wide, not 1, and sampling it at its unrotated half-width left the outer
	 * seventh of the block untested - so a ship on a diagonal heading clipped its corners through
	 * stone a ship on a cardinal heading would have been stopped by.
	 */
	private static final double[] SAMPLE_OFFSETS = {-0.98, 0.98};

	/** Half the width a unit block presents to the axes at this heading: 0.5 to 0.707. */
	private static double rotatedHalfWidth(float yawRadians) {
		return 0.5 * (Math.abs(Math.cos(yawRadians)) + Math.abs(Math.sin(yawRadians)));
	}

	/**
	 * Computes which blocks are on the exterior "hull" of the ship.
	 * Interior blocks (completely surrounded by other ship blocks) are excluded
	 * from collision checks since they can never collide with the world.
	 */
	public void computeHullBlocks(List<ShipBlock> blocks) {
		hullBlocks.clear();

		Set<RelativeBlockPos> occupiedPositions = new HashSet<>();
		for (ShipBlock block : blocks) {
			occupiedPositions.add(block.relativePos());
		}

		// A block is on the hull if any of its 6 neighbors is NOT occupied
		for (ShipBlock block : blocks) {
			var pos = block.relativePos();
			boolean isHull = false;

			for (int[] offset : NEIGHBOR_OFFSETS) {
				var neighborPos = new RelativeBlockPos(
					pos.x() + offset[0],
					pos.y() + offset[1],
					pos.z() + offset[2]
				);
				if (!occupiedPositions.contains(neighborPos)) {
					isHull = true;
					break;
				}
			}

			if (isHull) {
				hullBlocks.add(pos);
			}
		}
		LOGGER.debug("Hull computed: {} hull blocks of {} total", hullBlocks.size(), blocks.size());
	}

	/**
	 * Gathers the set of world BlockPos that hull blocks would occupy at the given pose.
	 * Shared by both movement and rotation collision checks.
	 */
	private Set<BlockPos> gatherCollisionPositions(ShipPose pose) {
		Set<BlockPos> positions = new HashSet<>();

		for (RelativeBlockPos hullPos : hullBlocks) {
			// toWorld is the block's corner; the samples go around its centre. Taken around the
			// corner they reached a block past the hull to the north, west and below, so a ship
			// stopped short of a jetty on those sides and scraped a seabed it was clear of.
			Vec3 worldPos = pose.toWorld(hullPos).add(0.5, 0.5, 0.5);

			// Across the deck the block is as wide as its heading makes it; up and down it is
			// never rotated and stays half a block.
			double across = rotatedHalfWidth(pose.yawRadians());

			for (double ox : SAMPLE_OFFSETS) {
				for (double oy : SAMPLE_OFFSETS) {
					for (double oz : SAMPLE_OFFSETS) {
						positions.add(new BlockPos(
							(int) Math.floor(worldPos.x + ox * across),
							(int) Math.floor(worldPos.y + oy * 0.5),
							(int) Math.floor(worldPos.z + oz * across)));
					}
				}
			}
		}

		return positions;
	}

	/**
	 * Checks if moving the ship would collide with world terrain. Fragile blocks
	 * (plants, kelp, cobwebs, etc.) and loose terrain ({@link ShipBlockUtils#isKnockedLooseByShip})
	 * are broken and dropped instead of blocking movement, whether or not something else stops it.
	 *
	 * @return true if collision detected (movement blocked), false if path is clear
	 */
	public boolean checkCollisionAndBreakFragile(Level world, ShipPose pose,
							  double deltaX, double deltaY, double deltaZ) {
		ShipPose movedPose = new ShipPose(
			pose.helmX() + deltaX, pose.helmY() + deltaY,
			pose.helmZ() + deltaZ, pose.yawRadians());

		Set<BlockPos> positionsToCheck = gatherCollisionPositions(movedPose);
		List<BlockPos> toBreak = new ArrayList<>();
		boolean blocked = false;

		for (BlockPos pos : positionsToCheck) {
			if (!world.isLoaded(pos)) {
				return true;
			}

			BlockState worldBlock = world.getBlockState(pos);

			// Light blocks are passed through, not broken: the ship's own lights stand inside its
			// hull, and breaking them on every move played a block breaking over and over while
			// the lighting put them straight back.
			if (worldBlock.isAir() || worldBlock.liquid() || worldBlock.is(Blocks.LIGHT)) {
				continue;
			}

			if (ShipBlockUtils.isBreakableByShip(worldBlock)
					|| ShipBlockUtils.isKnockedLooseByShip(world, pos, worldBlock)) {
				toBreak.add(pos);
				continue;
			}

			blocked = true;
		}

		// Broken only once every block has been judged against the world as it was, so knocking
		// one loose cannot loosen the next in the same move and let a hull chew through a reef.
		if (world instanceof ServerLevel) {
			for (BlockPos pos : toBreak) world.destroyBlock(pos, true, null, 512);
		}
		return blocked;
	}

	/**
	 * Whether turning to this heading would put the hull through anything solid.
	 *
	 * <p>Fragile blocks and loose terrain are passed over rather than broken: unlike a move, a
	 * turn neither stops for them nor destroys them, so a ship turning through kelp leaves the
	 * kelp standing inside its hull until it next moves. Worth knowing before changing it - the
	 * alternative is a turn that shears a garden while standing still.
	 *
	 * @return true if something solid is in the way, false if the turn is clear
	 */
	public boolean checkCollisionAtRotation(Level world, ShipPose pose) {
		Set<BlockPos> positionsToCheck = gatherCollisionPositions(pose);

		for (BlockPos pos : positionsToCheck) {
			if (!world.isLoaded(pos)) {
				return true;
			}

			BlockState worldBlock = world.getBlockState(pos);

			if (worldBlock.isAir() || worldBlock.liquid() || ShipBlockUtils.isBreakableByShip(worldBlock)
					|| ShipBlockUtils.isKnockedLooseByShip(world, pos, worldBlock)) {
				continue;
			}

			return true;
		}
		return false;
	}

	/** Asked by other ships, to find out whether they would be sailing through this one. */
	public Set<BlockPos> getWorldHullPositions(ShipPose pose) {
		return gatherCollisionPositions(pose);
	}

	/**
	 * Checks if the ship at the proposed pose overlaps positions occupied by
	 * other ships: if any hull sample position falls inside another ship's
	 * hull, movement is blocked.
	 *
	 * @param otherShipPositions Combined hull positions of all nearby ships
	 * @return true if collision detected (movement blocked)
	 */
	public boolean checkShipCollision(ShipPose movedPose, Set<BlockPos> otherShipPositions) {
		if (otherShipPositions.isEmpty()) return false;

		Set<BlockPos> ourPositions = gatherCollisionPositions(movedPose);
		for (BlockPos pos : ourPositions) {
			if (otherShipPositions.contains(pos)) {
				return true;
			}
		}
		return false;
	}

	public Set<RelativeBlockPos> getHullBlocks() {
		return Collections.unmodifiableSet(hullBlocks);
	}
}
