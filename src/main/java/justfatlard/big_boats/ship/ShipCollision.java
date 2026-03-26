package justfatlard.big_boats.ship;

import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

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

	// Sample offsets for collision checking: corners of each hull block's volume.
	// 0.49 instead of 0.5 to avoid sampling exactly on block boundaries where
	// floating-point edge cases cause false positives from adjacent blocks.
	// Two offsets per axis (2x2x2 = 8 samples) catches all block positions a
	// hull block can overlap. The center (0) is redundant — corner samples always
	// cover the center block position.
	private static final double[] SAMPLE_OFFSETS = {-0.49, 0.49};

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
			Vec3d worldPos = pose.toWorld(hullPos);

			for (double ox : SAMPLE_OFFSETS) {
				for (double oy : SAMPLE_OFFSETS) {
					for (double oz : SAMPLE_OFFSETS) {
						positions.add(new BlockPos(
							(int) Math.floor(worldPos.x + ox),
							(int) Math.floor(worldPos.y + oy),
							(int) Math.floor(worldPos.z + oz)));
					}
				}
			}
		}

		return positions;
	}

	/**
	 * Checks if moving the ship would collide with world terrain, breaking fragile blocks in the path.
	 * Fragile blocks (plants, kelp, cobwebs, etc.) are broken and dropped instead of blocking movement.
	 *
	 * @param world The world to check collisions in
	 * @param pose Current ship pose
	 * @param deltaX Movement delta in X
	 * @param deltaY Movement delta in Y
	 * @param deltaZ Movement delta in Z
	 * @return true if collision detected (movement blocked), false if path is clear
	 */
	public boolean checkCollisionAndBreakFragile(World world, ShipPose pose,
							  double deltaX, double deltaY, double deltaZ) {
		ShipPose movedPose = new ShipPose(
			pose.helmX() + deltaX, pose.helmY() + deltaY,
			pose.helmZ() + deltaZ, pose.yawRadians());

		Set<BlockPos> positionsToCheck = gatherCollisionPositions(movedPose);

		for (BlockPos pos : positionsToCheck) {
			if (!world.isChunkLoaded(pos)) {
				return true;
			}

			BlockState worldBlock = world.getBlockState(pos);

			if (worldBlock.isAir() || worldBlock.isLiquid()) {
				continue;
			}

			if (ShipBlockUtils.isBreakableByShip(worldBlock) && world instanceof ServerWorld serverWorld) {
				serverWorld.breakBlock(pos, true);
				continue;
			}

			return true;
		}
		return false;
	}

	/**
	 * Checks if the ship at a given rotation would collide with world terrain.
	 * Unlike movement collision, rotation does NOT break blocks - just prevents rotation.
	 *
	 * @param world The world to check collisions in
	 * @param pose Ship pose with the proposed rotation
	 * @return true if collision detected, false if rotation is clear
	 */
	public boolean checkCollisionAtRotation(World world, ShipPose pose) {
		Set<BlockPos> positionsToCheck = gatherCollisionPositions(pose);

		for (BlockPos pos : positionsToCheck) {
			if (!world.isChunkLoaded(pos)) {
				return true;
			}

			BlockState worldBlock = world.getBlockState(pos);

			if (worldBlock.isAir() || worldBlock.isLiquid() || ShipBlockUtils.isBreakableByShip(worldBlock)) {
				continue;
			}

			return true;
		}
		return false;
	}

	/**
	 * Returns the set of world BlockPos that hull blocks occupy at the given pose.
	 * Used by other ships to check for ship-to-ship collision.
	 */
	public Set<BlockPos> getWorldHullPositions(ShipPose pose) {
		return gatherCollisionPositions(pose);
	}

	/**
	 * Checks if the ship at the proposed pose overlaps with positions occupied
	 * by other ships. Same principle as terrain collision — if any hull sample
	 * position falls inside another ship's hull, movement is blocked.
	 *
	 * @param movedPose The proposed ship pose after movement/rotation
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
