package justfatlard.big_boats.ship;

import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.pandorical.api.BlockEntry;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.RelPos;
import justfatlard.pandorical.api.StructurePose;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wraps Pandorical's {@code StructureApi} to render a ship's blocks as a single
 * batch-rendered object to Pandorical clients.
 *
 * <p>The structure is posed with independent world coordinates, decoupled from the
 * anchor entity's own position (which orbits the helm at the passenger seat; see
 * {@link MultiBlockShipEntity} class docs), at {@link ShipPose#renderOrigin}, with each
 * block's {@link RelPos} left as its unmodified integer {@link RelativeBlockPos}. The
 * client renderer draws a block's model at {@code origin + Rotate(yaw) * (relPos + v)},
 * turning it about the origin; that origin is chosen so the result is the same block
 * centre collision and docking use.</p>
 *
 * <p>Each entry is made through Pandorical's drawing registry from the block's state and saved
 * block entity data, so a block another mod draws as something else (a miniature as its build) is
 * drawn that way at sea without this knowing what it is.</p>
 */
public class ShipStructure {
	private final String structureId;
	private boolean spawned = false;
	/** Where an entry went out with a drawing, so a change of state there is sent with the drawing it has now. */
	private final java.util.Set<RelPos> drawn = new java.util.HashSet<>();

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

	private BlockEntry toBlockEntry(ShipBlock block) {
		BlockEntry entry = PandoricalApi.structures().drawings()
			.entry(toRelPos(block.relativePos()), block.blockState(), block.blockEntityData().orElse(null));
		if (entry.drawing() != null) drawn.add(entry.pos());
		else drawn.remove(entry.pos());
		return entry;
	}

	private List<BlockEntry> toBlockEntries(List<ShipBlock> blocks) {
		List<BlockEntry> entries = new ArrayList<>(blocks.size());
		for (ShipBlock block : blocks) entries.add(toBlockEntry(block));
		return entries;
	}

	/** Converts a ship pose to Pandorical's world-space structure pose (yaw in degrees). */
	public static StructurePose toStructurePose(ShipPose pose) {
		Vec3 origin = pose.renderOrigin();
		return new StructurePose(origin.x, origin.y, origin.z, (float) Math.toDegrees(pose.yawRadians()));
	}

	/** Registers and broadcasts this structure. No-op if already spawned. */
	public void spawn(Entity anchorEntity, List<ShipBlock> blocks, ShipPose initialPose) {
		if (spawned) return;
		drawn.clear();
		PandoricalApi.structures().spawn(anchorEntity, structureId, toBlockEntries(blocks), toStructurePose(initialPose));
		PandoricalApi.structures().setWalkable(structureId, true);
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

	/**
	 * Updates a single block's state in place (e.g. toggling a door). One drawn as something else,
	 * then or now, goes out whole, so its drawing follows the change.
	 */
	public void updateBlockState(ShipBlock block) {
		if (!spawned) return;
		RelPos pos = toRelPos(block.relativePos());
		boolean wasDrawn = drawn.contains(pos);
		BlockEntry entry = toBlockEntry(block);
		if (wasDrawn || entry.drawing() != null) {
			PandoricalApi.structures().updateBlocks(structureId, List.of(entry), List.of(), Map.of());
		} else {
			PandoricalApi.structures().updateBlocks(structureId, List.of(), List.of(), Map.of(pos, block.blockState()));
		}
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
		drawn.removeAll(removed);
		PandoricalApi.structures().updateBlocks(structureId, List.of(), removed, Map.of());
	}

	/** Before it, the hold takes the sea like the deck does. */
	private static final boolean DRY = justfatlard.big_boats.PandoricalVersion.atLeast("15.18.0");

	/** Keeps the sea out of these cells while she sails; see {@link DrySpace}. */
	public void setDry(java.util.Collection<net.minecraft.core.BlockPos> cells) {
		if (!spawned || !DRY) return;
		List<RelPos> dry = new ArrayList<>(cells.size());
		for (net.minecraft.core.BlockPos cell : cells) dry.add(new RelPos(cell.getX(), cell.getY(), cell.getZ()));
		PandoricalApi.structures().setDry(structureId, dry);
	}

	/** Permanently removes this structure. Call on ship entity removal. */
	public void despawn() {
		if (!spawned) return;
		PandoricalApi.structures().despawn(structureId);
		spawned = false;
	}
}
