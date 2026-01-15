package justfatlard.big_boats.ship;

import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Handles physics calculations for multi-block ships.
 */
public class ShipPhysics {
	// Physics constants
	private static final double GRAVITY = 0.04;
	private static final double BUOYANCY_FORCE = 0.05;
	private static final double WATER_DRAG = 0.9;
	private static final double AIR_DRAG = 0.98;
	private static final double MOVEMENT_SPEED = 0.04;

	/**
	 * Calculates the center of mass for a ship based on its blocks.
	 */
	public static Vec3d calculateCenterOfMass(List<ShipBlock> blocks) {
		if (blocks.isEmpty()) {
			return Vec3d.ZERO;
		}

		double sumX = 0, sumY = 0, sumZ = 0;
		for (ShipBlock block : blocks) {
			RelativeBlockPos pos = block.relativePos();
			sumX += pos.x() + 0.5;
			sumY += pos.y() + 0.5;
			sumZ += pos.z() + 0.5;
		}

		int count = blocks.size();
		return new Vec3d(sumX / count, sumY / count, sumZ / count);
	}

	/**
	 * Calculates the bounding box for a ship at a given position and rotation.
	 */
	public static Box calculateBoundingBox(List<ShipBlock> blocks, Vec3d shipPos, float yawRadians) {
		if (blocks.isEmpty()) {
			return new Box(shipPos, shipPos.add(1, 1, 1));
		}

		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

		for (ShipBlock block : blocks) {
			Vec3d rotatedPos = block.relativePos().rotateY(yawRadians);

			minX = Math.min(minX, rotatedPos.x);
			minY = Math.min(minY, rotatedPos.y);
			minZ = Math.min(minZ, rotatedPos.z);
			maxX = Math.max(maxX, rotatedPos.x + 1);
			maxY = Math.max(maxY, rotatedPos.y + 1);
			maxZ = Math.max(maxZ, rotatedPos.z + 1);
		}

		return new Box(
			shipPos.x + minX, shipPos.y + minY, shipPos.z + minZ,
			shipPos.x + maxX, shipPos.y + maxY, shipPos.z + maxZ
		);
	}

	/**
	 * Calculates buoyancy and returns the velocity adjustment.
	 */
	public static BuoyancyResult calculateBuoyancy(World world, List<ShipBlock> blocks, Vec3d shipPos, float yawRadians) {
		if (blocks.isEmpty()) {
			return new BuoyancyResult(false, -Double.MAX_VALUE, 0);
		}

		int submergedBlocks = 0;
		double highestWaterLevel = -Double.MAX_VALUE;

		for (ShipBlock block : blocks) {
			Vec3d rotatedPos = block.relativePos().rotateY(yawRadians);
			Vec3d worldBlockPos = shipPos.add(rotatedPos);

			BlockPos blockPos = BlockPos.ofFloored(worldBlockPos);
			FluidState fluidState = world.getFluidState(blockPos);

			if (!fluidState.isEmpty()) {
				float fluidHeight = blockPos.getY() + fluidState.getHeight(world, blockPos);
				highestWaterLevel = Math.max(highestWaterLevel, fluidHeight);

				if (worldBlockPos.y < fluidHeight) {
					submergedBlocks++;
				}
			}
		}

		boolean inWater = submergedBlocks > 0;
		double submergedRatio = inWater ? (double) submergedBlocks / blocks.size() : 0;

		return new BuoyancyResult(inWater, highestWaterLevel, submergedRatio);
	}

	/**
	 * Applies physics to update velocity.
	 */
	public static Vec3d applyPhysics(Vec3d velocity, BuoyancyResult buoyancy, double shipY) {
		double vx = velocity.x;
		double vy = velocity.y;
		double vz = velocity.z;

		if (buoyancy.inWater()) {
			// Apply buoyancy
			double targetY = buoyancy.waterLevel() - 0.3; // Float slightly below surface
			if (shipY < targetY) {
				vy += BUOYANCY_FORCE * buoyancy.submergedRatio();
			} else if (shipY > targetY + 0.5) {
				vy -= GRAVITY * 0.5;
			}

			// Water drag
			vx *= WATER_DRAG;
			vz *= WATER_DRAG;
			vy *= 0.95;
		} else {
			// Gravity when not in water
			vy -= GRAVITY;

			// Air drag
			vx *= AIR_DRAG;
			vz *= AIR_DRAG;
		}

		return new Vec3d(vx, vy, vz);
	}

	/**
	 * Calculates movement input from player controls.
	 */
	public static Vec3d calculateMovementInput(float forward, float sideways, float yawRadians, boolean inWater) {
		if (!inWater || (forward == 0 && sideways == 0)) {
			return Vec3d.ZERO;
		}

		double moveX = -Math.sin(yawRadians) * forward + Math.cos(yawRadians) * sideways;
		double moveZ = Math.cos(yawRadians) * forward + Math.sin(yawRadians) * sideways;

		return new Vec3d(moveX * MOVEMENT_SPEED, 0, moveZ * MOVEMENT_SPEED);
	}

	public record BuoyancyResult(boolean inWater, double waterLevel, double submergedRatio) {}
}
