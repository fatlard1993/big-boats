package justfatlard.big_boats.ship;

import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.pandorical.api.BlockEntry;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.RelPos;
import justfatlard.pandorical.api.StructurePose;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wraps Pandorical's {@code StructureApi} to render a ship's blocks as a single
 * batch-rendered object to Pandorical clients.
 *
 * <p>The structure is posed with independent world coordinates, decoupled from the
 * anchor entity's own position (which orbits the helm at the passenger seat; see
 * {@link MultiBlockShipEntity} class docs). It is posed directly at the helm's world
 * position/yaw, exactly what {@link ShipPose} represents, with each block's
 * {@link RelPos} left as its unmodified integer {@link RelativeBlockPos}. The client
 * renderer rotates {@code pose + Rotate(yaw) * relPos}, which is bit-for-bit the same
 * transform as {@link ShipPose#toWorld}, so visual and physics/collision positions
 * stay consistent.</p>
 */
public class ShipStructure {
	private final String structureId;
	private boolean spawned = false;

	public ShipStructure(String structureId) {
		this.structureId = structureId;
	}

	public String getId() {
		return structureId;
	}

	public boolean isSpawned() {
		return spawned;
	}

	private static RelPos toRelPos(RelativeBlockPos pos) {
		return new RelPos(pos.x(), pos.y(), pos.z());
	}

	private static List<BlockEntry> toBlockEntries(List<ShipBlock> blocks) {
		List<BlockEntry> entries = new ArrayList<>(blocks.size());
		for (ShipBlock block : blocks) {
			entries.add(new BlockEntry(toRelPos(block.relativePos()), block.blockState()));
		}
		return entries;
	}

	/** Converts a ship pose to Pandorical's world-space structure pose (yaw in degrees). */
	public static StructurePose toStructurePose(ShipPose pose) {
		return new StructurePose(pose.helmX(), pose.helmY(), pose.helmZ(), (float) Math.toDegrees(pose.yawRadians()));
	}

	/** Registers and broadcasts this structure. No-op if already spawned. */
	public void spawn(Entity anchorEntity, List<ShipBlock> blocks, ShipPose initialPose) {
		if (spawned) return;
		PandoricalApi.structures().spawn(anchorEntity, structureId, toBlockEntries(blocks), toStructurePose(initialPose));
		spawned = true;
	}

	/** Pushes a new world pose. Call roughly once per tick while sailing for smooth interpolation. */
	public void updatePose(ShipPose pose) {
		if (!spawned) return;
		PandoricalApi.structures().updatePose(structureId, toStructurePose(pose));
	}

	/** Shows or hides the structure without discarding its server-side state (e.g. on dock/undock). */
	public void setVisible(boolean visible) {
		if (!spawned) return;
		PandoricalApi.structures().setVisible(structureId, visible);
	}

	/** Updates a single block's state in place (e.g. toggling a door). */
	public void updateBlockState(RelativeBlockPos relPos, BlockState newState) {
		if (!spawned) return;
		PandoricalApi.structures().updateBlocks(structureId, List.of(), List.of(), Map.of(toRelPos(relPos), newState));
	}

	/** Adds newly absorbed blocks (e.g. from a rescan). */
	public void addBlocks(List<ShipBlock> newBlocks) {
		if (!spawned || newBlocks.isEmpty()) return;
		PandoricalApi.structures().updateBlocks(structureId, toBlockEntries(newBlocks), List.of(), Map.of());
	}

	/** Removes blocks no longer part of the ship (e.g. broken while docked). */
	public void removeBlocks(List<RelativeBlockPos> removedPositions) {
		if (!spawned || removedPositions.isEmpty()) return;
		List<RelPos> removed = new ArrayList<>(removedPositions.size());
		for (RelativeBlockPos pos : removedPositions) removed.add(toRelPos(pos));
		PandoricalApi.structures().updateBlocks(structureId, List.of(), removed, Map.of());
	}

	/** Permanently removes this structure. Call on ship entity removal. */
	public void despawn() {
		if (!spawned) return;
		PandoricalApi.structures().despawn(structureId);
		spawned = false;
	}
}
