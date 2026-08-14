package justfatlard.big_boats.ship;

import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The ship's current position and rotation in world space; the single transform
 * from ship-local coordinates to world coordinates.
 *
 * <p>helmX/helmZ are the helm block's corner position (not center).
 * All ship-local positions are relative to this corner.</p>
 *
 * <h2>Coordinate transform implementations</h2>
 * The codebase has three rotation paths, each serving a distinct purpose:
 * <ol>
 *   <li>{@link ShipPose#toWorld}: continuous rotation for collision, lighting, and display
 *       during sailing. Delegates to {@link RelativeBlockPos#rotateY}. This is also the
 *       rotation the client-rendered structure uses ({@code StructurePose} poses the
 *       structure origin at (helmX, helmY, helmZ, yawDegrees) and rotates each block's
 *       unmodified integer {@code RelPos} the same way), so the rendered structure and
 *       the physics/collision hull stay consistent.</li>
 *   <li>{@link ShipPose#toWorldBlockPos} / {@link ShipBlockUtils#relativeToWorld}: snapped
 *       (90-degree) integer rotation for block placement during dock/undock.</li>
 *   <li>{@link RelativeBlockPos#rotateY}: raw continuous rotation returning Vec3.</li>
 * </ol>
 * <p>Paths 1 and 3 share the same math (sin/cos). Path 2 uses integer cos/sin for grid
 * alignment. If a rotation bug appears in one path, check whether the others are affected.</p>
 */
public record ShipPose(double helmX, double helmY, double helmZ, float yawRadians) {

	/**
	 * Transforms a ship-local relative position to world coordinates.
	 * Uses continuous rotation (for collision, lighting, display during sailing).
	 */
	public Vec3 toWorld(RelativeBlockPos relPos) {
		Vec3 rotated = relPos.rotateY(yawRadians);
		return new Vec3(helmX + rotated.x, helmY + rotated.y, helmZ + rotated.z);
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
	public Vec3 helmCenter() {
		return new Vec3(helmX + 0.5, helmY, helmZ + 0.5);
	}

	public BlockPos helmBlockPos() {
		return BlockPos.containing(helmX, helmY, helmZ);
	}
}
