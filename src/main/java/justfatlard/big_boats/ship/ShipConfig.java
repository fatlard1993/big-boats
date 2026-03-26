package justfatlard.big_boats.ship;

/**
 * Central configuration constants for ship behavior.
 * All values tuned by playtesting unless noted otherwise.
 */
public final class ShipConfig {
	// --- Physics constants (tuned by feel to match vanilla boat "weight") ---

	// Acceleration per tick when W is held. Low value gives ships inertia/momentum.
	public static final double ACCELERATION = 0.008;

	// Maximum speed in blocks/tick. 0.18 ≈ 3.6 blocks/sec — brisk but controllable.
	public static final double MAX_SPEED = 0.18;

	// Multiplied against velocity each tick. 0.98 = 2% slowdown/tick ≈ 1 second to stop from max speed.
	public static final double DRAG = 0.98;

	// Rotation rate: 2 degrees/tick = 40 deg/sec = full 360° in 9 seconds.
	public static final float TURN_SPEED = (float) Math.toRadians(2.0);

	// --- Size limits ---

	// Upper bound on flood-fill detection. Balances ship ambition vs. server entity budget.
	public static final int MAX_BLOCKS = 2000;

	// Helm + at least one other block. A lone helm is not a ship.
	public static final int MIN_BLOCKS = 2;

	// --- Grounding detection ---

	// Max vertical distance (blocks) when scanning for land mass below/above ship.
	// 8 blocks covers most dock/shore scenarios without catching sea floor in deep ocean.
	public static final int MAX_GROUNDING_Y_RANGE = 8;

	// --- Collision update thresholds (tick-spreading) ---
	// Collision shulker positions update when ANY of these thresholds is exceeded.
	// Balances visual accuracy vs. server load from repositioning many entities.

	// ~5 degrees in radians. Rotation smaller than this is imperceptible at collision resolution.
	public static final float COLLISION_UPDATE_YAW_THRESHOLD = 0.087f;

	// Half a block. Shulkers are 1-block wide, so 0.5 keeps overlap within one block width.
	public static final double COLLISION_UPDATE_POS_THRESHOLD = 0.5;

	// Fallback: update at least every 5 ticks (4x/sec) even if thresholds aren't met.
	public static final int COLLISION_UPDATE_TICK_INTERVAL = 5;

	// --- Water surface tracking ---

	// Check water surface every 10 ticks (2x/sec). Smooth enough for wave-following.
	public static final int WATER_SURFACE_CHECK_INTERVAL = 10;

	// Scan depth below ship for water. 32 covers deep ocean biomes (max ~30 blocks deep).
	public static final int WATER_SURFACE_SCAN_DEPTH = 32;

	// --- Floating / buoyancy ---

	// Y position delta below which the ship is considered at target height. Prevents jitter.
	public static final double FLOAT_SNAP_THRESHOLD = 0.01;

	// Fraction of Y distance to close per tick. 0.1 = 10% per tick ≈ smooth ease-in.
	public static final double FLOAT_LERP_FACTOR = 0.1;

	// Maximum Y velocity in blocks/tick. Prevents jarring vertical jumps.
	public static final double FLOAT_MAX_Y_SPEED = 0.1;

	// --- Entity tracking ---

	// Tracking range in chunks. 32 ensures large ships remain visible at distance.
	public static final int ENTITY_TRACKING_RANGE = 32;

	// --- Interaction ranges ---
	public static final double PLAYER_REACH = 4.5;
	public static final int CLEANUP_LIGHT_RADIUS = 50;
	public static final double DOCKED_HELM_SEARCH_RANGE = 5.0;
	public static final double SHIP_OVERLAP_SEARCH_RANGE = 50.0;

	// Camera constants live in CameraMixin (client classloader) — not here.
	// CameraMixin runs in a separate classloader where server-side classes are unavailable.
	// See CameraMixin for MIN_CAMERA_DISTANCE, MAX_CAMERA_DISTANCE, etc.

	private ShipConfig() {}
}
