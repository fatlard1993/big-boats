package justfatlard.big_boats.ship;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.pandorical.api.Capabilities;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Everything standing on the deck that is not a player, carried along as the ship moves under it.
 *
 * <p>A seated rider needs none of this: sit on a cushion and you are that cushion's passenger, and
 * vanilla moves passengers with their vehicle. Standing is the hard case, because a deck is not a
 * vehicle and the thing standing on it has no idea it is on a ship at all. Pandorical holds it up;
 * this moves it with the boards, setting its position outright, since for anything but a player
 * the server's word is final.
 *
 * <p>Players are not carried from here. A Pandorical client that walks structures carries its own
 * player across the deck it draws, in step with it frame by frame ({@link #walksDecks}); a player
 * without one has no deck under them at sea at all, and is told so as she casts off.
 */
public final class ShipRiders {

	/** Whether this player's client stands on and rides a sailing ship by itself. */
	public static boolean walksDecks(ServerPlayer player) {
		return PandoricalApi.hasCapability(player, Capabilities.WALKABLE_STRUCTURES);
	}

	/** How far below its feet an entity may find deck and still count as standing on it. */
	private static final double SUPPORT_REACH = 1.2;

	/**
	 * How far below a rider the deck may be while they are off it and still be theirs.
	 *
	 * <p>A jump's height and room to spare. A rider who jumps has no deck within
	 * {@link #SUPPORT_REACH} of their feet for most of the arc, and judged on that alone the ship
	 * would let go the moment they left it and pick them up again on landing - so jumping on a
	 * moving ship would put you down somewhere aft of where you left, and on a fast one it would
	 * put you in the sea. Going up and coming down in the same spot is the whole of what a jump
	 * means on a moving deck.
	 */
	private static final double AIRBORNE_REACH = 4.0;

	/** Beyond this a rider has genuinely moved itself rather than merely failed to keep up. */
	private static final double SELF_MOVED = 0.02;

	/** Below this the ship has not really moved, and nudging everyone aboard is noise. */
	private static final double MOVED_EPSILON = 1.0E-4;

	private int knownLayout = -1;
	private Set<RelativeBlockPos> occupied = Set.of();

	/** What is known about one rider between ticks. */
	private static final class Carried {
		/** Where they are meant to be standing, in the ship's own unrotated frame. */
		Vec3 anchor;
		/** Where we last put them, so a shortfall can be told from a step taken. */
		Vec3 commanded;
	}

	private final Map<UUID, Carried> riders = new HashMap<>();

	/**
	 * Move everything standing on the ship by however far the ship just moved.
	 *
	 * @param from the pose at the start of this tick, which is the frame the riders are still in
	 * @param to   the pose the ship has arrived at
	 */
	public void carry(ServerLevel world, MultiBlockShipEntity ship, List<ShipBlock> blocks,
					  ShipPose from, ShipPose to, AABB searchBox) {
		// Both early returns forget who was aboard, because what the map buys is the looser reach
		// that keeps a jumping rider - and a ship that has stopped is not carrying anyone through
		// a jump. Kept across a standstill, an entry that had wandered onto a jetty four blocks
		// up still counted as aboard, and the ship took them with it when it got under way.
		if (!moved(from, to)) {
			riders.clear();
			return;
		}

		indexBlocks(ship, blocks);
		if (occupied.isEmpty()) {
			riders.clear();
			return;
		}

		Set<UUID> aboard = new HashSet<>();

		// A passenger is already being carried by whatever it is riding - a mob on a cushion, the
		// pilot by the ship - and moving it again would double the trip. Players are their
		// clients' to carry; see the class comment.
		for (Entity rider : world.getEntities(ship, searchBox,
				e -> couldRide(e) && e.getVehicle() == null && !ship.ownsChildEntity(e) && !(e instanceof Player))) {
			UUID id = rider.getUUID();
			Carried state = riders.get(id);
			Vec3 position = rider.position();
			Vec3 local = toLocal(position, from);

			// Deck under the feet is what puts someone aboard. Staying aboard is a looser test,
			// because a rider mid-jump has no deck under their feet and is still on the ship.
			if (!hasDeckBeneath(local, state != null ? AIRBORNE_REACH : SUPPORT_REACH)) continue;

			aboard.add(id);
			if (state == null) {
				state = new Carried();
				riders.put(id, state);
			}

			// The anchor is a spot on the deck, not a position in the world, and it only moves
			// when the rider moves ITSELF. Re-deriving it from where the rider actually is each
			// tick was the whole of the sliding-off bug: a rider that fell short of last tick's
			// carry had that shortfall written into the anchor as though it had walked backwards,
			// so every tick lost a little more ground.
			if (state.anchor == null || movedItself(position, state.commanded)) {
				state.anchor = local;
			}

			// Aimed at the anchor rather than fed the ship's displacement, so a rider that is
			// behind is brought back rather than merely moved along in parallel with its gap.
			Vec3 target = toWorld(state.anchor, to);
			rider.setPos(target.x, target.y, target.z);
			state.commanded = target;
		}

		riders.keySet().retainAll(aboard);
	}

	/**
	 * Move everyone standing on the deck by a snap the hull just made, once and outright.
	 *
	 * <p>Docking is the one move that is not a tick of travel: the hull jumps to the block grid
	 * and turns to a cardinal, and nobody aboard is carried through it by velocity. A teleport
	 * is fine here, where it is not while sailing - the pause it puts on a player's own movement
	 * lasts one round trip, and the ship has stopped.
	 *
	 * @param pilot the player stepping off the helm, if that is what docked it. Their position is
	 *              the server's, set by the seat they just left, so the server moves them even when
	 *              their client walks decks.
	 */
	public void settle(ServerLevel world, MultiBlockShipEntity ship, List<ShipBlock> blocks,
					   ShipPose from, ShipPose to, AABB searchBox, Entity pilot) {
		riders.clear();
		if (!moved(from, to)) return;

		indexBlocks(ship, blocks);
		if (occupied.isEmpty()) return;

		float turn = Mth.wrapDegrees((float) Math.toDegrees(to.yawRadians() - from.yawRadians()));

		for (Entity rider : world.getEntities(ship, searchBox,
				e -> couldRide(e) && e.getVehicle() == null && !ship.ownsChildEntity(e))) {
			// Their client moves them through the snap, from the deck it drew.
			if (rider != pilot && rider instanceof ServerPlayer player && walksDecks(player)) continue;

			Vec3 position = rider.position();
			Vec3 local = toLocal(position, from);
			if (!hasDeckBeneath(local, AIRBORNE_REACH)) continue;

			Vec3 target = toWorld(local, to);
			if (rider instanceof ServerPlayer player) {
				player.teleportTo(world, target.x, target.y, target.z, Set.of(),
					player.getYRot() + turn, player.getXRot(), false);
			} else {
				rider.setPos(target.x, target.y, target.z);
				rider.setYRot(rider.getYRot() + turn);
			}
		}
	}

	/** Entities worth considering; the ship's own furniture and hardware move by other means. */
	private static boolean couldRide(Entity entity) {
		return !entity.isRemoved()
			&& !(entity instanceof Cushion)
			&& !(entity instanceof MultiBlockShipEntity);
	}

	/** Whether this rider went somewhere under its own power since we last placed it. */
	private static boolean movedItself(Vec3 position, Vec3 lastCommanded) {
		if (lastCommanded == null) return true;
		return position.distanceToSqr(lastCommanded) > SELF_MOVED * SELF_MOVED;
	}

	private Vec3 toLocal(Vec3 worldPos, ShipPose pose) {
		return pose.toLocalPoint(worldPos);
	}

	private Vec3 toWorld(Vec3 local, ShipPose pose) {
		return pose.toWorldPoint(local);
	}

	/**
	 * Deck within {@code reach} under these ship-local coordinates.
	 *
	 * <p>A block at relative Y occupies {@code [y, y+1)}, so feet resting on it sit at {@code y+1};
	 * the small bias below keeps a rider standing exactly on a boundary from reading as one storey
	 * up, hanging off nothing.
	 */
	private boolean hasDeckBeneath(Vec3 local, double reach) {
		int x = Mth.floor(local.x);
		int z = Mth.floor(local.z);
		int top = Mth.floor(local.y - 0.05);
		int bottom = Mth.floor(local.y - reach);

		for (int y = top; y >= bottom; y--) {
			if (occupied.contains(new RelativeBlockPos(x, y, z))) return true;
		}
		return false;
	}

	/** Rebuilt only when blocks are added to or taken off the ship. */
	private void indexBlocks(MultiBlockShipEntity ship, List<ShipBlock> blocks) {
		if (ship.blockLayout() == knownLayout) return;

		Set<RelativeBlockPos> rebuilt = new HashSet<>(blocks.size());
		for (ShipBlock block : blocks) rebuilt.add(block.relativePos());

		occupied = rebuilt;
		knownLayout = ship.blockLayout();
	}

	private static boolean moved(ShipPose from, ShipPose to) {
		return Math.abs(to.helmX() - from.helmX()) > MOVED_EPSILON
			|| Math.abs(to.helmY() - from.helmY()) > MOVED_EPSILON
			|| Math.abs(to.helmZ() - from.helmZ()) > MOVED_EPSILON
			|| Math.abs(to.yawRadians() - from.yawRadians()) > MOVED_EPSILON;
	}
}
