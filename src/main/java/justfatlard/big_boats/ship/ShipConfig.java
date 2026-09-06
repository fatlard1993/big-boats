package justfatlard.big_boats.ship;

/**
 * Central configuration constants for ship behavior.
 * All values tuned by playtesting unless noted otherwise.
 */
public final class ShipConfig {
	// --- Physics constants (tuned by feel to match vanilla boat "weight") ---

	// Acceleration per tick when W is held. Low value gives ships inertia/momentum.
	public static final double ACCELERATION = 0.008;

	// Maximum speed in blocks/tick. 0.18 ≈ 3.6 blocks/sec; brisk but controllable.
	public static final double MAX_SPEED = 0.18;

	// Multiplied against velocity each tick. 0.98 = 2% slowdown/tick ≈ 1 second to stop from max speed.
	public static final double DRAG = 0.98;

	// Rotation rate: 2 degrees/tick = 40 deg/sec = full 360° in 9 seconds.
	public static final float TURN_SPEED = (float) Math.toRadians(2.0);

	// --- Size limits ---

	// Upper bound on flood-fill detection. Balances ship ambition vs. server entity budget.
	/**
	 * The largest ship any helm can command: the Tonnage III rating.
	 *
	 * <p>Also the ceiling on every internal search bound, which is why it stays a plain constant
	 * rather than becoming per-ship - those bounds are about not walking the world forever, not
	 * about what a particular helm is rated for.
	 */
	public static final int MAX_BLOCKS = 2000;

	/**
	 * Ship size a helm can hold together, by Tonnage level.
	 *
	 * <p>Index 0 is a plain helm off the crafting table, and 100 blocks is a boat rather than a
	 * barge - enough to be worth sailing and cheap enough to try. The enchantment is the whole of
	 * the progression from there, so a big ship is something earned rather than the only size on
	 * offer.
	 */
	private static final int[] CAPACITY_BY_TONNAGE = {100, 400, 1000, MAX_BLOCKS};

	/** Capacity for an enchantment level, clamped so an over-levelled helm is still answerable. */
	public static int capacityForTonnage(int tonnage) {
		if (tonnage <= 0) return CAPACITY_BY_TONNAGE[0];
		return CAPACITY_BY_TONNAGE[Math.min(tonnage, CAPACITY_BY_TONNAGE.length - 1)];
	}

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
	/**
	 * Every tick. The hull's collision used to be allowed to fall five ticks or half a block behind
	 * the ship before it was worth moving, which is a long way to be wrong about where the floor is
	 * - a rider standing still on a deck that had left without them simply dropped through it.
	 */
	public static final int COLLISION_UPDATE_TICK_INTERVAL = 1;

	/**
	 * How long a client takes to slide an entity to where it was told it is.
	 *
	 * <p>Vanilla interpolates entity positions over roughly this many ticks rather than snapping
	 * them, so anything of the ship's that is an entity - its collision, its cushions - arrives
	 * this far behind the deck those things belong to, which is drawn exactly. They are sent ahead
	 * by this much so that the sliding lands them in the right place.
	 */
	public static final double CLIENT_INTERP_TICKS = 3.0;

	// --- Height keeping ---
	// A ship holds the height it was christened at; nothing tracks the water any more.

	// Y position delta below which the ship is considered at target height. Prevents jitter.
	/**
	 * Ticks between a sailing ship looking for cushions it should be carrying. Comfortably under
	 * vanilla's own hundred-tick support check, which is the thing being got in front of.
	 */
	public static final int SEAT_SCAN_INTERVAL = 40;

	/** Ticks between a docked ship checking that it still has a helm to be steered by. */
	public static final int DOCKED_HELM_CHECK_INTERVAL = 40;

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

	// --- Camera (Pandorical CameraApi hints, pushed server-side on mount/dismount) ---
	// Distance scales with ship size: MIN + blockCount * PER_BLOCK, clamped to [MIN, MAX].
	/**
	 * Camera pull-back for someone sitting on a cushion rather than steering.
	 *
	 * <p>Deliberately under {@link #MIN_CAMERA_DISTANCE}, which is where the pilot's own view
	 * starts before it grows with the ship: a passenger gets enough of a step back to see the deck
	 * they are on, and the wider view stays the helm's.
	 */
	public static final float PASSENGER_CAMERA_DISTANCE = 4.0f;

	public static final float MIN_CAMERA_DISTANCE = 6.0f;
	public static final float MAX_CAMERA_DISTANCE = 20.0f;
	public static final float CAMERA_DISTANCE_PER_BLOCK = 0.15f;

	private ShipConfig() {}
}
