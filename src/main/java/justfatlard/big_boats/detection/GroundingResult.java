package justfatlard.big_boats.detection;

/**
 * Result of grounding detection. A ship can only undock when FreeFloating or TouchingTerrain.
 *
 * Variants:
 * - FreeFloating: No adjacent solid blocks — ship is in open water.
 * - TouchingTerrain: Adjacent blocks found but small enough that the ship can still undock.
 *   Connected blocks may be absorbed during the undock rescan if still attached.
 * - GroundedTooLarge: Connected landmass exceeds the ship's remaining block capacity.
 * - GroundedMassive: BFS search limit hit before fully exploring the landmass (very large terrain).
 */
public sealed interface GroundingResult {
	record FreeFloating() implements GroundingResult {}
	record TouchingTerrain() implements GroundingResult {}
	record GroundedTooLarge() implements GroundingResult {}
	record GroundedMassive() implements GroundingResult {}

	default boolean canUndock() {
		return this instanceof FreeFloating || this instanceof TouchingTerrain;
	}

	default String message() {
		return switch (this) {
			case FreeFloating f -> "Ship is free-floating";
			case TouchingTerrain t -> "Ship is touching adjacent terrain (small enough to undock)";
			case GroundedTooLarge g -> "Ship is grounded — connected to landmass too large to absorb";
			case GroundedMassive g -> "Ship is grounded — connected landmass is very large";
		};
	}
}
