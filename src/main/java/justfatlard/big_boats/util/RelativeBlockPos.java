package justfatlard.big_boats.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Represents a block position relative to the ship's helm (origin).
 * Used for storing ship structure and calculating world positions during movement.
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
	 * Converts to a Vec3d offset for entity positioning.
	 */
	public Vec3d toVec3d() {
		return new Vec3d(x, y, z);
	}

	/**
	 * Rotates this position around the Y axis by the given yaw (in radians).
	 */
	public Vec3d rotateY(float yawRadians) {
		double cos = Math.cos(yawRadians);
		double sin = Math.sin(yawRadians);
		double newX = x * cos - z * sin;
		double newZ = x * sin + z * cos;
		return new Vec3d(newX, y, newZ);
	}
}
