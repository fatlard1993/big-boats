package justfatlard.big_boats.ship;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages virtual display entities for rendering ship blocks to clients.
 */
public class ShipElementHolder extends ElementHolder {
	private final List<BlockDisplayElement> blockElements = new ArrayList<>();
	private final List<ShipBlock> shipBlocks;
	private float currentYaw = 0;

	public ShipElementHolder(List<ShipBlock> blocks, float initialYawRadians) {
		this.shipBlocks = blocks;
		this.currentYaw = initialYawRadians;
		createBlockElements(initialYawRadians);
	}

	private void createBlockElements(float yawRadians) {
		// Create rotation quaternion for visual rotation
		Quaternionf rotation = new Quaternionf().rotateY(-yawRadians);

		// Rotation center is at helm block center (0.5, 0, 0.5) relative to entity
		final double centerX = 0.5;
		final double centerZ = 0.5;

		double cos = Math.cos(yawRadians);
		double sin = Math.sin(yawRadians);

		for (ShipBlock block : shipBlocks) {
			BlockDisplayElement element = new BlockDisplayElement(block.blockState());

			// Entity is at helm CORNER. Rotate around helm CENTER (0.5, 0, 0.5).
			// Block corner relative to entity = relativePos
			// Block corner relative to center = relativePos - center
			double relToCenter_X = block.relativePos().x() - centerX;
			double relToCenter_Z = block.relativePos().z() - centerZ;

			// Rotate around center
			double rotatedX = relToCenter_X * cos - relToCenter_Z * sin;
			double rotatedZ = relToCenter_X * sin + relToCenter_Z * cos;

			// Translate back: final position = rotated + center
			double finalX = rotatedX + centerX;
			double finalZ = rotatedZ + centerZ;

			element.setOffset(new Vec3d(finalX, block.relativePos().y(), finalZ));

			// Apply visual rotation
			element.setLeftRotation(rotation);

			// Use teleport duration for position tracking (instant, no interpolation delay)
			element.setTeleportDuration(1);
			// Instant interpolation - no delay for rotation either
			element.setInterpolationDuration(1);

			blockElements.add(element);
			this.addElement(element);
		}
	}

	/**
	 * Updates block positions and visual rotation based on ship rotation.
	 * Blocks orbit around the helm CENTER and visually rotate together as a unit.
	 */
	public void updateRotation(float yawRadians) {
		// Check if rotation changed meaningfully (for interpolation trigger)
		boolean rotationChanged = Math.abs(yawRadians - currentYaw) > 0.001f;
		currentYaw = yawRadians;

		// Create rotation quaternion for visual rotation (negative because display uses opposite convention)
		Quaternionf rotation = new Quaternionf().rotateY(-yawRadians);

		// Rotation center is at helm block center (0.5, 0, 0.5) relative to entity
		final double centerX = 0.5;
		final double centerZ = 0.5;

		double cos = Math.cos(yawRadians);
		double sin = Math.sin(yawRadians);

		for (int i = 0; i < shipBlocks.size() && i < blockElements.size(); i++) {
			ShipBlock block = shipBlocks.get(i);
			BlockDisplayElement element = blockElements.get(i);

			// Block corner relative to center
			double relToCenter_X = block.relativePos().x() - centerX;
			double relToCenter_Z = block.relativePos().z() - centerZ;

			// Rotate around center
			double rotatedX = relToCenter_X * cos - relToCenter_Z * sin;
			double rotatedZ = relToCenter_X * sin + relToCenter_Z * cos;

			// Translate back: final position = rotated + center
			double finalX = rotatedX + centerX;
			double finalZ = rotatedZ + centerZ;

			element.setOffset(new Vec3d(finalX, block.relativePos().y(), finalZ));

			// Apply visual rotation
			element.setLeftRotation(rotation);

			// Only trigger interpolation when rotation changes (for smooth visual rotation)
			if (rotationChanged) {
				element.startInterpolation();
			}
		}
	}

	/**
	 * Updates block positions with an additional offset to compensate for entity orbit.
	 * The offset accounts for the entity moving in a circle around the logical center.
	 */
	public void updateRotationWithOffset(float yawRadians, double offsetX, double offsetZ) {
		boolean rotationChanged = Math.abs(yawRadians - currentYaw) > 0.001f;
		currentYaw = yawRadians;

		Quaternionf rotation = new Quaternionf().rotateY(-yawRadians);

		// Rotation center relative to the LOGICAL helm position (not entity position)
		final double centerX = 0.5;
		final double centerZ = 0.5;

		double cos = Math.cos(yawRadians);
		double sin = Math.sin(yawRadians);

		for (int i = 0; i < shipBlocks.size() && i < blockElements.size(); i++) {
			ShipBlock block = shipBlocks.get(i);
			BlockDisplayElement element = blockElements.get(i);

			// Block corner relative to center
			double relToCenter_X = block.relativePos().x() - centerX;
			double relToCenter_Z = block.relativePos().z() - centerZ;

			// Rotate around center
			double rotatedX = relToCenter_X * cos - relToCenter_Z * sin;
			double rotatedZ = relToCenter_X * sin + relToCenter_Z * cos;

			// Translate back and apply offset to compensate for entity orbit
			double finalX = rotatedX + centerX + offsetX;
			double finalZ = rotatedZ + centerZ + offsetZ;

			element.setOffset(new Vec3d(finalX, block.relativePos().y(), finalZ));
			element.setLeftRotation(rotation);

			if (rotationChanged) {
				element.startInterpolation();
			}
		}
	}

	/**
	 * Gets the number of block display elements.
	 */
	public int getBlockCount() {
		return blockElements.size();
	}
}
