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
 *   <li>{@link ShipPose#toWorld}: continuous rotation of a block's corner, for collision,
 *       lighting and interaction during sailing; each adds the unrotated half block that makes
 *       it the block's centre. Delegates to {@link RelativeBlockPos#rotateY}. Points that are
 *       not blocks go through {@link #toWorldPoint}, and the rendered structure is posed at
 *       {@link #renderOrigin}, so all of them turn about the helm block's centre.</li>
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
	 * Ship-local point to world, turning about the helm block's centre.
	 *
	 * <p>In the local frame block {@code r} fills {@code [r, r+1)}, so its centre {@code r + 0.5}
	 * lands on {@code helm + R·r + 0.5}: the cell docking places it in, and where its collision
	 * sits. Anything aboard that is not a block - a cushion, someone standing on the deck - has to
	 * turn about that same point, or a quarter turn leaves it a block away from the deck it was on.
	 */
	public Vec3 toWorldPoint(Vec3 local) {
		Vec3 turned = ShipBlockUtils.rotateXZ(local.x - 0.5, local.z - 0.5, yawRadians);
		return new Vec3(helmX + 0.5 + turned.x, helmY + local.y, helmZ + 0.5 + turned.z);
	}

	/** Inverse of {@link #toWorldPoint}. */
	public Vec3 toLocalPoint(Vec3 world) {
		Vec3 turned = ShipBlockUtils.rotateXZ(world.x - helmX - 0.5, world.z - helmZ - 0.5, -yawRadians);
		return new Vec3(turned.x + 0.5, world.y - helmY, turned.z + 0.5);
	}

	/**
	 * Where to put the rendered structure's origin so it turns about the helm block's centre too.
	 *
	 * <p>The structure renderer rotates each block's model about the origin itself, which is a
	 * corner; posed at the helm corner, a ship heading east was drawn a block to one side of its
	 * own collision, its pilot and the cells it docks into.
	 */
	public Vec3 renderOrigin() {
		return toWorldPoint(Vec3.ZERO);
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
