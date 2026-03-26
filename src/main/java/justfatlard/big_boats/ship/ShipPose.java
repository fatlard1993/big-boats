package justfatlard.big_boats.ship;

import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * The ship's current position and rotation in world space.
 * Centralizes the transform from ship-local coordinates to world coordinates,
 * eliminating the quad-tuple parameter passing that was duplicated across
 * every delegate (ShipCollision, ShipCollisionEntities, ShipLighting, etc.).
 *
 * <p>helmX/helmZ are the helm block's corner position (not center).
 * All ship-local positions are relative to this corner.</p>
 *
 * <h2>Coordinate transform implementations</h2>
 * The codebase has four rotation paths, each serving a distinct purpose:
 * <ol>
 *   <li>{@link ShipPose#toWorld} — continuous rotation for collision, lighting, and display
 *       during sailing. Delegates to {@link RelativeBlockPos#rotateY}.</li>
 *   <li>{@link ShipPose#toWorldBlockPos} / {@link ShipBlockUtils#relativeToWorld} — snapped
 *       (90-degree) integer rotation for block placement during dock/undock.</li>
 *   <li>{@link RelativeBlockPos#rotateY} — raw continuous rotation returning Vec3d.</li>
 *   <li>{@link ShipElementHolder} — continuous rotation with BLOCK_CENTER_OFFSET adjustment
 *       so display blocks rotate around their visual centers, not corners.</li>
 * </ol>
 * <p>Paths 1 and 3 share the same math (sin/cos). Path 2 uses integer cos/sin for grid
 * alignment. Path 4 adds a half-block center adjustment for visual correctness.
 * If a rotation bug appears in one path, check whether the others are affected.</p>
 */
public record ShipPose(double helmX, double helmY, double helmZ, float yawRadians) {

	/**
	 * Transforms a ship-local relative position to world coordinates.
	 * Uses continuous rotation (for collision, lighting, display during sailing).
	 */
	public Vec3d toWorld(RelativeBlockPos relPos) {
		Vec3d rotated = relPos.rotateY(yawRadians);
		return new Vec3d(helmX + rotated.x, helmY + rotated.y, helmZ + rotated.z);
	}

	/**
	 * Transforms a ship-local relative position to a world BlockPos.
	 * Uses snapped (90-degree) rotation for block placement during dock/undock.
	 */
	public BlockPos toWorldBlockPos(RelativeBlockPos relPos, int cos, int sin) {
		return ShipBlockUtils.relativeToWorld(relPos, helmX, helmY, helmZ, cos, sin);
	}

	/**
	 * Returns the helm center position (block corner + 0.5 on X and Z).
	 */
	public Vec3d helmCenter() {
		return new Vec3d(helmX + 0.5, helmY, helmZ + 0.5);
	}

	/**
	 * Returns the helm position as a BlockPos.
	 */
	public BlockPos helmBlockPos() {
		return BlockPos.ofFloored(helmX, helmY, helmZ);
	}
}
