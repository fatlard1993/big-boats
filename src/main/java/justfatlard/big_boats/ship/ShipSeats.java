package justfatlard.big_boats.ship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Cushions the ship carries, so that sitting down is a way to ride.
 *
 * <p>A cushion is an entity, not a block, and one of the block-attached kind - the same family as
 * item frames and paintings. The ship used to treat it as exactly that: saved to NBT at undock,
 * deleted from the world, and rebuilt on docking. That is right for a painting, which only has to
 * be somewhere when you next look at it, and wrong for a cushion, which is furniture you use. The
 * moment the ship set sail every cushion aboard stopped existing, which is what "the cushions fell
 * off" was - not dropped, deleted, and handed back at the far end of the voyage.
 *
 * <p>Kept alive and moved with the hull instead. That also settles the seated rider for free:
 * a player who sits on a cushion is that cushion's passenger, and a passenger goes where its
 * vehicle goes without anyone arranging it. No packets, no prediction fighting - the rider is
 * riding a real entity that happens to be moving.
 */
public final class ShipSeats {

	/**
	 * Every cushion currently held by some ship, by id.
	 *
	 * <p>Read by {@code CushionSurvivesMixin}. A cushion checks every hundred ticks that it still
	 * has a block beneath it and breaks when it does not, and a sailing ship's deck is not blocks -
	 * it is a rendered structure over invisible collision entities. Left alone, every cushion
	 * aboard would drop itself into the sea five seconds out of port.
	 *
	 * <p>Static because a mixin has no way to reach the particular ship, and one flat set is
	 * cheaper to ask than a search: the question is asked by every cushion in the world.
	 */
	private static final Set<UUID> CARRIED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Forget every carried cushion. Called when the server stops; see {@code BigBoats}. */
	public static void forgetAll() {
		CARRIED.clear();
	}

	/** Whether this cushion is being carried by a ship, and so should not test the ground. */
	public static boolean isCarried(UUID id) {
		return !CARRIED.isEmpty() && CARRIED.contains(id);
	}

	/** One cushion and where it sits, in the ship's own unrotated frame. */
	private record Seat(Cushion cushion, Vec3 localOffset) {}

	private final List<Seat> seats = new ArrayList<>();

	/**
	 * Take a cushion aboard, remembering where it sits relative to the helm.
	 *
	 * <p>The offset is stored unrotated, so it survives the ship turning: it is re-rotated to the
	 * current heading every tick rather than being a world position that would have to be chased.
	 */
	public void take(Cushion cushion, ShipPose pose) {
		seats.add(new Seat(cushion, pose.toLocalPoint(cushion.position())));
		CARRIED.add(cushion.getUUID());
	}

	/**
	 * Players we have pulled the camera back for, so it can be put back when they get up.
	 *
	 * <p>Held by player rather than by cushion because the reset is owed to the person: they may
	 * stand up, the cushion may be broken under them, or the ship may dock, and all three end the
	 * same way.
	 */
	private final Map<UUID, ServerPlayer> seatedCameras = new HashMap<>();

	/** Move every cushion to where the hull now is. Called each tick while sailing. */
	public void updatePositions(ShipPose pose) {
		if (seats.isEmpty()) return;

		Set<UUID> seatedNow = new HashSet<>();

		seats.removeIf(seat -> {
			if (seat.cushion().isRemoved()) {
				CARRIED.remove(seat.cushion().getUUID());
				return true;
			}
			Vec3 world = pose.toWorldPoint(seat.localOffset());
			seat.cushion().setPos(world.x, world.y, world.z);
			// Vanilla never expects a cushion to move, so its tracker never sends where one is:
			// an update interval of never, deltas off. Moved silently, a cushion stayed on the
			// client wherever the ship set sail from, and whoever sat on it stayed with it while
			// the deck left without them. This is the tracker's own flag for "send it anyway".
			seat.cushion().needsSync = true;

			for (Entity passenger : seat.cushion().getPassengers()) {
				if (passenger instanceof ServerPlayer player) {
					seatedNow.add(player.getUUID());
					if (seatedCameras.putIfAbsent(player.getUUID(), player) == null) {
						pullCameraBack(player);
					}
				}
			}
			return false;
		});

		seatedCameras.values().removeIf(player -> {
			if (seatedNow.contains(player.getUUID())) return false;
			PandoricalApi.camera().reset(player);
			return true;
		});
	}

	/**
	 * A step back for a seated passenger: enough to see the deck they are riding on, and less than
	 * the helm's, which grows with the ship. Third person as well as distance, because a distance
	 * on its own does nothing to a first-person view.
	 */
	private static void pullCameraBack(ServerPlayer player) {
		if (!PandoricalApi.isAvailable(player)) return;
		PandoricalApi.camera().setDistance(player, ShipConfig.PASSENGER_CAMERA_DISTANCE);
		PandoricalApi.camera().setPerspective(player, "third_person_back");
	}

	/**
	 * Hand the cushions back to the world.
	 *
	 * <p>Called on docking, when there is a deck under them again. They are left exactly where they
	 * are - the last tick already put them over the hull - and simply stop being exempt from the
	 * check that they are standing on something.
	 */
	public void release() {
		for (Seat seat : seats) {
			CARRIED.remove(seat.cushion().getUUID());
		}
		seats.clear();

		for (ServerPlayer player : seatedCameras.values()) {
			PandoricalApi.camera().reset(player);
		}
		seatedCameras.clear();
	}

	public boolean isEmpty() {
		return seats.isEmpty();
	}

	/**
	 * Take aboard any cushion standing on the hull that this ship is not already carrying.
	 *
	 * <p>Because the carry list does not survive a restart. It is a set of ids in memory, and a
	 * ship that was at sea when the server went down comes back up sailing, with its cushions
	 * still floating over the deck and no longer exempt from the ground check - so they would drop
	 * into the water a few seconds later, for a reason nobody watching could possibly guess at.
	 * Re-taking them costs one entity query and settles it without anything having to be persisted.
	 */
	public void scan(ServerLevel world, ShipPose pose, AABB hullBox) {
		for (Cushion cushion : world.getEntitiesOfClass(Cushion.class, hullBox,
				c -> !c.isRemoved() && !isCarried(c.getUUID()))) {
			take(cushion, pose);
		}
	}
}
