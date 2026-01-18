package justfatlard.big_boats.ship;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
		Quaternionf rotation = new Quaternionf().rotateY(-yawRadians);

		final double centerX = 0.5;
		final double centerZ = 0.5;

		double cos = Math.cos(yawRadians);
		double sin = Math.sin(yawRadians);

		final float SCALE = 0.98f;
		final double INSET = (1.0 - SCALE) / 2.0;

		for (ShipBlock block : shipBlocks) {
			BlockDisplayElement element = new BlockDisplayElement(block.blockState());

			double relToCenter_X = block.relativePos().x() - centerX;
			double relToCenter_Z = block.relativePos().z() - centerZ;

			double rotatedX = relToCenter_X * cos - relToCenter_Z * sin;
			double rotatedZ = relToCenter_X * sin + relToCenter_Z * cos;

			double finalX = rotatedX + centerX + INSET;
			double finalZ = rotatedZ + centerZ + INSET;
			double finalY = block.relativePos().y() + INSET;

			element.setOffset(new Vec3d(finalX, finalY, finalZ));
			element.setScale(new Vector3f(SCALE, SCALE, SCALE));
			element.setLeftRotation(rotation);
			element.setTeleportDuration(1);
			element.setInterpolationDuration(1);

			blockElements.add(element);
			this.addElement(element);
		}
	}

	/**
	 * Updates block positions and visual rotation based on ship rotation.
	 */
	public void updateRotation(float yawRadians) {
		boolean rotationChanged = Math.abs(yawRadians - currentYaw) > 0.001f;
		currentYaw = yawRadians;

		Quaternionf rotation = new Quaternionf().rotateY(-yawRadians);

		final double centerX = 0.5;
		final double centerZ = 0.5;

		double cos = Math.cos(yawRadians);
		double sin = Math.sin(yawRadians);

		for (int i = 0; i < shipBlocks.size() && i < blockElements.size(); i++) {
			ShipBlock block = shipBlocks.get(i);
			BlockDisplayElement element = blockElements.get(i);

			double relToCenter_X = block.relativePos().x() - centerX;
			double relToCenter_Z = block.relativePos().z() - centerZ;

			double rotatedX = relToCenter_X * cos - relToCenter_Z * sin;
			double rotatedZ = relToCenter_X * sin + relToCenter_Z * cos;

			double finalX = rotatedX + centerX;
			double finalZ = rotatedZ + centerZ;

			element.setOffset(new Vec3d(finalX, block.relativePos().y(), finalZ));
			element.setLeftRotation(rotation);

			if (rotationChanged) {
				element.startInterpolation();
			}
		}
	}

	/**
	 * Updates block positions with an additional offset to compensate for entity orbit.
	 */
	public void updateRotationWithOffset(float yawRadians, double offsetX, double offsetZ) {
		boolean rotationChanged = Math.abs(yawRadians - currentYaw) > 0.001f;
		currentYaw = yawRadians;

		Quaternionf rotation = new Quaternionf().rotateY(-yawRadians);

		final double centerX = 0.5;
		final double centerZ = 0.5;

		double cos = Math.cos(yawRadians);
		double sin = Math.sin(yawRadians);

		for (int i = 0; i < shipBlocks.size() && i < blockElements.size(); i++) {
			ShipBlock block = shipBlocks.get(i);
			BlockDisplayElement element = blockElements.get(i);

			double relToCenter_X = block.relativePos().x() - centerX;
			double relToCenter_Z = block.relativePos().z() - centerZ;

			double rotatedX = relToCenter_X * cos - relToCenter_Z * sin;
			double rotatedZ = relToCenter_X * sin + relToCenter_Z * cos;

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

	/**
	 * Updates the block state for a specific block (e.g., when toggling a door).
	 */
	public void updateBlockState(int index, net.minecraft.block.BlockState newState) {
		if (index >= 0 && index < blockElements.size()) {
			BlockDisplayElement element = blockElements.get(index);
			element.setBlockState(newState);
			element.tick();
		}
	}

	/**
	 * Shows or hides all block display elements.
	 */
	public void setVisible(boolean visible) {
		final float VISIBLE_SCALE = 0.98f;
		for (BlockDisplayElement element : blockElements) {
			if (visible) {
				element.setScale(new Vector3f(VISIBLE_SCALE, VISIBLE_SCALE, VISIBLE_SCALE));
			} else {
				element.setScale(new Vector3f(0, 0, 0));
			}
		}
	}

	/**
	 * Adds new display elements for blocks that were already added to the entity's block list.
	 */
	public void addBlocks(List<ShipBlock> newBlocks, float yawRadians) {
		Quaternionf rotation = new Quaternionf().rotateY(-yawRadians);

		final double centerX = 0.5;
		final double centerZ = 0.5;
		final float SCALE = 0.98f;
		final double INSET = (1.0 - SCALE) / 2.0;

		double cos = Math.cos(yawRadians);
		double sin = Math.sin(yawRadians);

		for (ShipBlock block : newBlocks) {
			BlockDisplayElement element = new BlockDisplayElement(block.blockState());

			double relToCenter_X = block.relativePos().x() - centerX;
			double relToCenter_Z = block.relativePos().z() - centerZ;

			double rotatedX = relToCenter_X * cos - relToCenter_Z * sin;
			double rotatedZ = relToCenter_X * sin + relToCenter_Z * cos;

			double finalX = rotatedX + centerX + INSET;
			double finalZ = rotatedZ + centerZ + INSET;
			double finalY = block.relativePos().y() + INSET;

			element.setOffset(new Vec3d(finalX, finalY, finalZ));
			element.setScale(new Vector3f(SCALE, SCALE, SCALE));
			element.setLeftRotation(rotation);
			element.setTeleportDuration(1);
			element.setInterpolationDuration(1);

			blockElements.add(element);
			this.addElement(element);
		}
	}
}
