package justfatlard.big_boats.ship;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages virtual {@link BlockDisplayElement} entities for rendering ship blocks to clients.
 *
 * <p>Uses Polymer's virtual entity system to show blocks without placing them in the world.
 * Keyed by {@link RelativeBlockPos} to avoid index-coupling bugs. Block positions rotate
 * around their visual centers (BLOCK_CENTER_OFFSET = 0.5) rather than their corners.</p>
 *
 * <p>The display compensates for the entity orbit offset: the entity position orbits the
 * helm based on player seat position, so display elements need an inverse offset to stay
 * visually anchored to the helm.</p>
 */
public class ShipElementHolder extends ElementHolder {
	private final Map<RelativeBlockPos, BlockDisplayElement> blockElements = new LinkedHashMap<>();
	private float currentYaw = 0;

	// Block-center offset: rotates blocks around their centers rather than corners.
	// Without this, blocks visually orbit the helm corner instead of the helm center.
	private static final double BLOCK_CENTER_OFFSET = 0.5;

	public ShipElementHolder(List<ShipBlock> blocks, float initialYawRadians) {
		this.currentYaw = initialYawRadians;
		createBlockElements(blocks, initialYawRadians);
	}

	private Vec3d computeRotatedOffset(RelativeBlockPos relPos, float yawRadians) {
		double relToCenterX = relPos.x() - BLOCK_CENTER_OFFSET;
		double relToCenterZ = relPos.z() - BLOCK_CENTER_OFFSET;
		Vec3d rotated = ShipBlockUtils.rotateXZ(relToCenterX, relToCenterZ, yawRadians);
		return new Vec3d(rotated.x + BLOCK_CENTER_OFFSET, relPos.y(), rotated.z + BLOCK_CENTER_OFFSET);
	}

	private void createBlockElements(List<ShipBlock> blocks, float yawRadians) {
		Quaternionf rotation = new Quaternionf().rotateY(-yawRadians);

		for (ShipBlock block : blocks) {
			BlockDisplayElement element = new BlockDisplayElement(block.blockState());
			Vec3d offset = computeRotatedOffset(block.relativePos(), yawRadians);

			element.setOffset(offset);
			element.setLeftRotation(rotation);
			element.setTeleportDuration(2);
			element.setInterpolationDuration(2);

			blockElements.put(block.relativePos(), element);
			this.addElement(element);
		}
	}

	/**
	 * Updates block positions with an additional offset to compensate for entity orbit.
	 */
	public void updateRotationWithOffset(float yawRadians, double offsetX, double offsetZ) {
		currentYaw = yawRadians;

		Quaternionf rotation = new Quaternionf().rotateY(-yawRadians);

		for (var entry : blockElements.entrySet()) {
			RelativeBlockPos relPos = entry.getKey();
			BlockDisplayElement element = entry.getValue();

			Vec3d offset = computeRotatedOffset(relPos, yawRadians);
			element.setOffset(new Vec3d(offset.x + offsetX, offset.y, offset.z + offsetZ));
			element.setLeftRotation(rotation);
			element.startInterpolation();
		}
	}

	public int getBlockCount() {
		return blockElements.size();
	}

	/**
	 * Updates the block state for a specific block (e.g., when toggling a door).
	 */
	public void updateBlockState(RelativeBlockPos relPos, BlockState newState) {
		BlockDisplayElement element = blockElements.get(relPos);
		if (element != null) {
			element.setBlockState(newState);
			element.tick();
		}
	}

	/**
	 * Shows or hides all block display elements.
	 */
	public void setVisible(boolean visible) {
		for (BlockDisplayElement element : blockElements.values()) {
			if (visible) {
				element.setScale(new Vector3f(1.0f, 1.0f, 1.0f));
			} else {
				element.setScale(new Vector3f(0, 0, 0));
			}
		}
	}

	/**
	 * Adds new display elements for blocks absorbed into the ship.
	 */
	public void addBlocks(List<ShipBlock> newBlocks, float yawRadians) {
		Quaternionf rotation = new Quaternionf().rotateY(-yawRadians);

		for (ShipBlock block : newBlocks) {
			BlockDisplayElement element = new BlockDisplayElement(block.blockState());
			Vec3d offset = computeRotatedOffset(block.relativePos(), yawRadians);

			element.setOffset(offset);
			element.setLeftRotation(rotation);
			element.setTeleportDuration(2);
			element.setInterpolationDuration(2);

			blockElements.put(block.relativePos(), element);
			this.addElement(element);
		}
	}

	/**
	 * Rebuilds all display elements from a new block list.
	 * Used after rescanShipStructure when blocks may have been added or removed.
	 */
	public void rebuildFromBlocks(List<ShipBlock> blocks, float yawRadians) {
		// Save old elements for removal after new ones are created.
		// This minimizes the flicker window where no blocks are visible to clients.
		var oldElements = new ArrayList<>(blockElements.values());
		blockElements.clear();

		// Create new elements first
		createBlockElements(blocks, yawRadians);

		// Then remove old elements
		for (BlockDisplayElement element : oldElements) {
			this.removeElement(element);
		}
	}
}
