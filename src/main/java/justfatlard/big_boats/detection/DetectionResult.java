package justfatlard.big_boats.detection;

import justfatlard.big_boats.ship.ShipBlock;
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
	record NoBlocks() implements Failure {
		public String message() { return "No valid blocks found at helm position"; }
	}
	record TooSmall(int found, int required) implements Failure {
		public String message() { return "Ship too small (minimum " + required + " blocks required, found " + found + ")"; }
	}
	record TooLarge() implements Failure {
		public String message() { return "Ship exceeds maximum block limit"; }
	}
}
