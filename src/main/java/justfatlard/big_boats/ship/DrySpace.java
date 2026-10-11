package justfatlard.big_boats.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The part of a ship the sea stays out of: her hull at and below the waterline, and whatever
 * space below it the water outside cannot get into. Water comes in from outside at or below its
 * own surface and never climbs, so an open boat's hold is as dry as a sealed one, as long as its
 * sides stand above the water - a vanilla boat's rule.
 *
 * <p>One rule, at sea and ashore: the hold kept dry while she sails is the hold bailed out when
 * she docks, so nothing that was dry aboard is under water the moment she ties up.
 */
public final class DrySpace {
	private static final Logger LOGGER = LoggerFactory.getLogger(DrySpace.class);

	private DrySpace() {}

	/**
	 * The dry cells of a hull standing in water whose top block is at {@code waterline}: the hull's
	 * own cells at or below it, and every cell of its bounding box at or below it that water from
	 * outside the box cannot reach. Empty when the hull is too spread out to hold water.
	 */
	public static Set<BlockPos> of(Collection<BlockPos> hullCells, int waterline) {
		if (hullCells.isEmpty()) return Set.of();
		Set<BlockPos> hull = new HashSet<>(hullCells);
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos pos : hull) {
			minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
		}
		int maxY = waterline;
		if (maxY < minY) return Set.of();

		// The walk below is over the bounding BOX, and nothing anywhere bounds a ship's extent -
		// only its block count. Six arms of a few hundred blocks enclose a box of a hundred
		// million positions, and filling a HashSet with them is an OutOfMemoryError, which is an
		// Error and so goes straight past dock()'s catch and strands the ship mid-dock. A ship
		// spread that thin has no enclosed room to drain anyway.
		long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
		if (volume > ShipConfig.MAX_BAIL_VOLUME) {
			LOGGER.warn("No dry space for a ship spanning {} positions below the water (limit {}): too spread out to hold water out",
				volume, ShipConfig.MAX_BAIL_VOLUME);
			return Set.of();
		}

		// The sea comes in from the box's sides and floor, never its top: the top is the water's
		// surface. What it reaches through anything that is not hull is the sea's; the rest is hers.
		Set<BlockPos> sea = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					boolean onShell = x == minX || x == maxX || y == minY || z == minZ || z == maxZ;
					if (!onShell) continue;
					BlockPos pos = new BlockPos(x, y, z);
					if (hull.contains(pos) || !sea.add(pos)) continue;
					queue.add(pos);
				}
			}
		}
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction);
				if (next.getX() < minX || next.getX() > maxX
					|| next.getY() < minY || next.getY() > maxY
					|| next.getZ() < minZ || next.getZ() > maxZ) continue;
				if (hull.contains(next) || !sea.add(next)) continue;
				queue.add(next);
			}
		}

		Set<BlockPos> dry = new HashSet<>();
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!sea.contains(pos)) dry.add(pos);
				}
			}
		}
		return dry;
	}

	/**
	 * The top block of the water a ship stands in: the highest water in the ring of cells just
	 * outside this box of the world, from its top down. Measured outside the ship, so neither the
	 * water in her hold nor a bucket's worth on her deck moves it. Empty when she stands in none.
	 */
	public static OptionalInt waterline(ServerLevel level, double minX, int minY, double minZ, double maxX, int maxY, double maxZ) {
		int x0 = Mth.floor(minX) - 1, x1 = Mth.floor(maxX) + 1;
		int z0 = Mth.floor(minZ) - 1, z1 = Mth.floor(maxZ) + 1;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = maxY; y >= minY; y--) {
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					if (x != x0 && x != x1 && z != z0 && z != z1) continue;
					cursor.set(x, y, z);
					if (!level.isLoaded(cursor)) continue;
					if (!level.getFluidState(cursor).isEmpty()) return OptionalInt.of(y);
				}
			}
		}
		return OptionalInt.empty();
	}
}
