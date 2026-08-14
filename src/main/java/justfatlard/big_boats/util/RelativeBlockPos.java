package justfatlard.big_boats.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A block position relative to the ship's helm block, which is always at {@link #ORIGIN} (0, 0, 0).
 *
 * <p>All ship blocks are stored in this coordinate space. To convert to world coordinates,
 * rotate by the ship's current yaw and add the helm's world position (helmX/helmY/helmZ).
 * See {@link justfatlard.big_boats.util.ShipBlockUtils#relativeToWorld} for snapped (docked)
 * transforms, and {@link #rotateY} for continuous (sailing) transforms.</p>
 *
 * <p>The helm is always at ORIGIN by convention, enforced by:
 * <ul>
 *   <li>{@link justfatlard.big_boats.ship.ShipBlock#isHelm()} checks equality with ORIGIN</li>
 *   <li>{@link justfatlard.big_boats.detection.FloodFillDetector#detect} starts BFS from the helm position</li>
 *   <li>Christening bottle targets the helm block as the origin point</li>
 * </ul>
 */
public record RelativeBlockPos(int x, int y, int z) {
	public static final Codec<RelativeBlockPos> CODEC = RecordCodecBuilder.create(instance ->
		instance.group(
			Codec.INT.fieldOf("x").forGetter(RelativeBlockPos::x),
			Codec.INT.fieldOf("y").forGetter(RelativeBlockPos::y),
			Codec.INT.fieldOf("z").forGetter(RelativeBlockPos::z)
		).apply(instance, RelativeBlockPos::new)
	);

	public static final RelativeBlockPos ORIGIN = new RelativeBlockPos(0, 0, 0);

	/**
	 * Creates a relative position from a world position and origin (helm position).
	 */
	public static RelativeBlockPos fromWorldPos(BlockPos worldPos, BlockPos origin) {
		return new RelativeBlockPos(
			worldPos.getX() - origin.getX(),
			worldPos.getY() - origin.getY(),
			worldPos.getZ() - origin.getZ()
		);
	}

	/**
	 * Converts this relative position to a world position given the ship's current position.
	 */
	public BlockPos toWorldPos(BlockPos shipOrigin) {
		return new BlockPos(
			shipOrigin.getX() + x,
			shipOrigin.getY() + y,
			shipOrigin.getZ() + z
		);
	}

	/**
	 * Converts to a Vec3 offset for entity positioning.
	 */
	public Vec3 toVec3d() {
		return new Vec3(x, y, z);
	}

	/**
	 * Rotates this position around the Y axis by the given yaw (in radians).
	 */
	public Vec3 rotateY(float yawRadians) {
		double cos = Math.cos(yawRadians);
		double sin = Math.sin(yawRadians);
		double newX = x * cos - z * sin;
		double newZ = x * sin + z * cos;
		return new Vec3(newX, y, newZ);
	}
}
