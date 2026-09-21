package justfatlard.big_boats.detection;

import justfatlard.big_boats.ship.ShipBlock;
import net.minecraft.core.BlockPos;
import justfatlard.big_boats.ship.ShipConfig;
import java.util.List;

/**
 * Result of flood-fill ship detection.
 */
public sealed interface DetectionResult {
	record Success(List<ShipBlock> blocks) implements DetectionResult {
		public int blockCount() { return blocks.size(); }
	}

	sealed interface Failure extends DetectionResult {
		String message();
	}
	/**
	 * Part of the structure is in a chunk that is not loaded, so how big the ship is cannot be
	 * known right now.
	 *
	 * <p>A failure and not a smaller {@link Success}, because the two are indistinguishable to
	 * every caller and the consequences are not: a rescan that believes a truncated answer drops
	 * every block it could not see, which severs a live ship and leaves the far half standing in
	 * the world as orphaned geometry. Refusing costs a retry; believing costs the ship.
	 */
	record Unloaded(BlockPos at) implements Failure {
		public String message() {
			return "Part of the ship is in an unloaded chunk (" + at.toShortString() + ") — try again from closer";
		}
	}
	record NoBlocks() implements Failure {
		public String message() { return "No valid blocks found at helm position"; }
	}
	record TooSmall(int found, int required) implements Failure {
		public String message() { return "Ship too small (minimum " + required + " blocks required, found " + found + ")"; }
	}
	/**
	 * A structure bigger than its helm holds. {@code found} is as far as it was counted: anything
	 * over {@link ShipConfig#SIZE_REPORT_LIMIT} is reported as that many and more.
	 */
	record TooLarge(int limit, int found) implements Failure {
		public String message() {
			String size = found > ShipConfig.SIZE_REPORT_LIMIT
				? ShipConfig.SIZE_REPORT_LIMIT + "+"
				: String.valueOf(found);
			int tonnage = tonnageToHold(found);
			if (tonnage < 0) {
				return "Ship is too big for any helm (" + size + " blocks, and the most a helm holds is "
					+ ShipConfig.MAX_BLOCKS + ")";
			}
			return "Ship is too big for this helm (" + size + " blocks, and it holds " + limit
				+ ") — enchant it with Tonnage " + (tonnage < ROMAN.length ? ROMAN[tonnage] : String.valueOf(tonnage));
		}

		/** The lowest Tonnage level that holds this many blocks, or -1 when none does. */
		private static int tonnageToHold(int blocks) {
			for (int level = 1; ShipConfig.capacityForTonnage(level) > ShipConfig.capacityForTonnage(level - 1); level++) {
				if (ShipConfig.capacityForTonnage(level) >= blocks) return level;
			}
			return -1;
		}

		private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V"};
	}
}
