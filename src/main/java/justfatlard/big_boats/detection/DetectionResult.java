package justfatlard.big_boats.detection;

import justfatlard.big_boats.ship.ShipBlock;

import java.util.List;
import java.util.Optional;

/**
 * Result of flood-fill ship detection.
 */
public record DetectionResult(
	boolean success,
	List<ShipBlock> blocks,
	Optional<String> errorMessage
) {
	public static DetectionResult success(List<ShipBlock> blocks) {
		return new DetectionResult(true, blocks, Optional.empty());
	}

	public static DetectionResult failure(String message) {
		return new DetectionResult(false, List.of(), Optional.of(message));
	}

	public int blockCount() {
		return blocks.size();
	}
}
