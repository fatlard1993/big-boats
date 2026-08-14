package justfatlard.big_boats.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.LilyPadBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Shared utility methods for ship block operations and coordinate transforms.
 */
public final class ShipBlockUtils {

	/**
	 * Checks if a block should be broken by ship collision instead of blocking movement.
	 * These are fragile/plant blocks like seagrass, kelp, lily pads, etc.
	 *
	 * <p>{@link DoublePlantBlock} covers both tall seagrass and tall flowers; the
	 * subclasses other mappings name {@code TallSeagrassBlock}/{@code TallFlowerBlock}
	 * share this common parent on the current version.</p>
	 */
	public static boolean isBreakableByShip(BlockState state) {
		Block block = state.getBlock();

		if (block instanceof SeagrassBlock
			|| block instanceof KelpBlock
			|| block instanceof KelpPlantBlock
			|| block instanceof LilyPadBlock
			|| block instanceof DoublePlantBlock
			|| block instanceof FlowerBlock
			|| block instanceof SugarCaneBlock
			|| block instanceof VineBlock
			|| block instanceof SnowLayerBlock
			|| block instanceof WebBlock
			|| block instanceof LightBlock) {
			return true;
		}

		// Also break replaceable non-air blocks (tall grass, ferns, etc.)
		return state.canBeReplaced() && !state.isAir();
	}

	/**
	 * Checks if a block state is eligible for ship construction.
	 * Excludes air, liquids, and fragile/plant blocks that ships break through.
	 */
	public static boolean isShipEligible(BlockState state) {
		if (state.isAir() || state.liquid()) {
			return false;
		}

		return !isBreakableByShip(state);
	}

	/**
	 * Converts ship yaw (in degrees) to a Rotation for rotating block states.
	 */
	public static Rotation yawToBlockRotation(float yaw) {
		float normalizedYaw = ((yaw % 360) + 360) % 360;
		int rotation = Math.round(normalizedYaw / 90) % 4;
		return switch (rotation) {
			case 0 -> Rotation.NONE;
			case 1 -> Rotation.CLOCKWISE_90;
			case 2 -> Rotation.CLOCKWISE_180;
			case 3 -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	/**
	 * Converts a Direction to yaw degrees for ship movement.
	 * W moves toward the helm's facing direction.
	 */
	public static float directionToYaw(Direction dir) {
		return switch (dir) {
			case SOUTH -> 180f;
			case WEST -> -90f;
			case NORTH -> 0f;
			case EAST -> 90f;
			default -> 0f;
		};
	}

	/**
	 * Rotates an (x, z) offset around the Y axis by the given yaw in radians.
	 */
	public static Vec3 rotateXZ(double x, double z, float yawRadians) {
		double cos = Math.cos(yawRadians);
		double sin = Math.sin(yawRadians);
		return new Vec3(x * cos - z * sin, 0, x * sin + z * cos);
	}

	/**
	 * Snaps a visual yaw to the nearest 90 degrees and computes integer cos/sin.
	 */
	public record SnappedRotation(int cos, int sin, float yawDegrees) {
		public float yawRadians() {
			return (float) Math.toRadians(yawDegrees);
		}
	}

	public static SnappedRotation snappedRotation(float visualYaw) {
		float snapped = Math.round(visualYaw / 90.0f) * 90.0f;
		float rad = (float) Math.toRadians(snapped);
		int cos = (int) Math.round(Math.cos(rad));
		int sin = (int) Math.round(Math.sin(rad));
		return new SnappedRotation(cos, sin, snapped);
	}

	// Cached seat offsets per direction; avoids allocating a new Vec3 every call
	private static final Vec3 SEAT_OFFSET_NORTH = new Vec3(0, 0, -0.5);
	private static final Vec3 SEAT_OFFSET_SOUTH = new Vec3(0, 0, 0.5);
	private static final Vec3 SEAT_OFFSET_EAST = new Vec3(0.5, 0, 0);
	private static final Vec3 SEAT_OFFSET_WEST = new Vec3(-0.5, 0, 0);

	/**
	 * Returns the seat offset behind the helm based on the helm's facing direction.
	 */
	public static Vec3 helmSeatOffset(Direction helmFacing) {
		return switch (helmFacing) {
			case NORTH -> SEAT_OFFSET_NORTH;
			case SOUTH -> SEAT_OFFSET_SOUTH;
			case EAST -> SEAT_OFFSET_EAST;
			case WEST -> SEAT_OFFSET_WEST;
			default -> Vec3.ZERO;
		};
	}

	/**
	 * Computes the world position of a ship block given the ship's helm position and snapped rotation.
	 */
	public static BlockPos relativeToWorld(RelativeBlockPos relPos, double helmX, double helmY, double helmZ, int cos, int sin) {
		int rotatedX = relPos.x() * cos - relPos.z() * sin;
		int rotatedZ = relPos.x() * sin + relPos.z() * cos;
		return new BlockPos(
			(int) Math.floor(helmX) + rotatedX,
			(int) Math.floor(helmY) + relPos.y(),
			(int) Math.floor(helmZ) + rotatedZ
		);
	}

	/**
	 * Inverse of relativeToWorld: converts world deltas back to local relative coordinates.
	 */
	public static RelativeBlockPos worldToRelative(int worldDeltaX, int worldDeltaY, int worldDeltaZ, int cos, int sin) {
		int localX = worldDeltaX * cos + worldDeltaZ * sin;
		int localZ = -worldDeltaX * sin + worldDeltaZ * cos;
		return new RelativeBlockPos(localX, worldDeltaY, localZ);
	}

	private ShipBlockUtils() {}
}
