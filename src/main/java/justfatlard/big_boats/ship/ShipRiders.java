package justfatlard.big_boats.ship;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import justfatlard.big_boats.util.PlayerInputStorage;
import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Everything standing on the deck, carried along as the ship moves under it.
 *
 * <p>A seated rider needs none of this: sit on a cushion and you are that cushion's passenger, and
 * vanilla moves passengers with their vehicle. Standing is the hard case, because a deck is not a
 * vehicle and the thing standing on it has no idea it is on a ship at all.
 *
 * <h2>Why velocity and not position</h2>
 * <p>A player's position is the client's to decide; the server is told where they are, not the
 * other way round. Moving one server-side does nothing - the next movement packet arrives with the
 * old position and quietly undoes it. The sanctioned way to say otherwise is a teleport, and a
 * teleport stops the server accepting the player's own movement until the client acknowledges it,
 * which at twenty a second means never: the deck would carry them and they would not be able to
 * walk on it.
 *
 * <p>So the ship's movement is handed over as velocity, which the client applies on top of whatever
 * it was already doing. It is the one channel that moves a player without taking control away from
 * them. Anything that is not a player is positioned outright, since for those the server's word is
 * final.
 */
public final class ShipRiders {

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

	/** A rider's own vertical speed, held inside what jumping and falling can actually produce. */
	private static final double OWN_RISE_LIMIT = 1.0;
	private static final double OWN_FALL_LIMIT = -3.0;

	/** The ship dropping faster than this is the deck falling away, not the rider falling. */
	private static final double SHIP_DESCENDING = -0.05;

	private List<ShipBlock> knownBlocks;
	private Set<RelativeBlockPos> occupied = Set.of();

	/** What is known about one rider between ticks. */
	private static final class Carried {
		/** Where they are meant to be standing, in the ship's own unrotated frame. */
		Vec3 anchor;
		/** Where we last told them to be, so a shortfall can be told from a step taken. */
		Vec3 commanded;
		/** Their position last tick, and the vertical part of what we asked of them. */
		Vec3 lastPosition;
		double commandedRise;
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
		if (!moved(from, to)) return;

		indexBlocks(blocks);
		if (occupied.isEmpty()) return;

		float turn = Mth.wrapDegrees((float) Math.toDegrees(to.yawRadians() - from.yawRadians()));
		Set<UUID> aboard = new HashSet<>();

		for (Entity rider : world.getEntities(ship, searchBox, ShipRiders::couldRide)) {
			// A passenger is already being carried by whatever it is riding - the pilot by the
			// ship, a seated player by their cushion - and moving it again would double the trip.
			if (rider.getVehicle() != null) continue;
			if (ship.ownsChildEntity(rider)) continue;

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
			if (state.anchor == null || movedItself(rider, position, state.commanded)) {
				state.anchor = local;
			}

			// Aimed at the anchor rather than fed the ship's displacement, so a rider that is
			// behind is brought back rather than merely moved along in parallel with its gap.
			Vec3 target = toWorld(state.anchor, to);
			Vec3 delta = target.subtract(position);

			if (rider instanceof ServerPlayer player) {
				carryPlayer(player, state, position, delta, turn);
			} else {
				rider.setPos(target.x, target.y, target.z);
			}

			state.commanded = target;
			state.lastPosition = position;
		}

		riders.keySet().retainAll(aboard);
	}

	private void carryPlayer(ServerPlayer player, Carried state, Vec3 position, Vec3 delta,
							 float turn) {
		// The rider's own vertical motion, kept.
		//
		// Commanding all three axes outright looked right and quietly took jumping away: the client
		// sets its motion from what we send, so a jump's upward push was replaced by the ship's
		// flat nothing on the very next tick, and gravity was overwritten as fast as it applied.
		// What we mean to say is "the floor moved", not "here is your velocity" - so the vertical
		// sent is the floor's movement plus whatever the rider was doing under their own steam,
		// measured as the part of last tick's movement we did not ask for.
		double own = 0;
		if (state.lastPosition != null) {
			own = Mth.clamp((position.y - state.lastPosition.y) - state.commandedRise,
				OWN_FALL_LIMIT, OWN_RISE_LIMIT);
		}
		state.commandedRise = delta.y;

		Vec3 motion = new Vec3(delta.x, delta.y + own, delta.z);
		player.setDeltaMovement(motion);
		player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), motion));

		// A deck dropping out from under someone is not a fall they took.
		if (delta.y < SHIP_DESCENDING) player.resetFallDistance();

		// And the deck turns them with it. Relative, so it adds to wherever they were already
		// looking rather than seizing the view, and on its own packet rather than a teleport -
		// which would have blocked their movement until the client answered it.
		if (turn != 0) {
			player.connection.send(new ClientboundPlayerRotationPacket(turn, true, 0, true));
		}
	}

	/** Entities worth considering; the ship's own furniture and hardware move by other means. */
	private static boolean couldRide(Entity entity) {
		return !entity.isRemoved()
			&& !(entity instanceof Cushion)
			&& !(entity instanceof MultiBlockShipEntity);
	}

	/**
	 * Whether this rider went somewhere under its own power since we last placed it.
	 *
	 * <p>For anything but a player the test is exact: we set their position outright, so any
	 * difference from what we set is theirs. A player is the awkward one, because the same
	 * difference covers both a step they took and a step they failed to take. Their input settles
	 * it - a player pressing nothing did not walk, so whatever moved them is drift and gets
	 * corrected, and a player pressing something is walking and gets to keep it.
	 */
	private static boolean movedItself(Entity rider, Vec3 position, Vec3 lastCommanded) {
		if (rider instanceof ServerPlayer player) return isWalking(player);
		if (lastCommanded == null) return true;
		return position.distanceToSqr(lastCommanded) > SELF_MOVED * SELF_MOVED;
	}

	private static boolean isWalking(ServerPlayer player) {
		var input = PlayerInputStorage.getInput(player);
		return input.forward() || input.backward() || input.left() || input.right() || input.jump();
	}

	private Vec3 toLocal(Vec3 worldPos, ShipPose pose) {
		Vec3 flat = ShipBlockUtils.rotateXZ(
			worldPos.x - pose.helmX(), worldPos.z - pose.helmZ(), -pose.yawRadians());
		return new Vec3(flat.x, worldPos.y - pose.helmY(), flat.z);
	}

	private Vec3 toWorld(Vec3 local, ShipPose pose) {
		Vec3 flat = ShipBlockUtils.rotateXZ(local.x, local.z, pose.yawRadians());
		return new Vec3(pose.helmX() + flat.x, pose.helmY() + local.y, pose.helmZ() + flat.z);
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

	/** Rebuilt only when the ship's block list is replaced, which is a dock or an absorb. */
	private void indexBlocks(List<ShipBlock> blocks) {
		if (blocks == knownBlocks) return;

		Set<RelativeBlockPos> rebuilt = new HashSet<>(blocks.size());
		for (ShipBlock block : blocks) rebuilt.add(block.relativePos());

		occupied = rebuilt;
		knownBlocks = blocks;
	}

	private static boolean moved(ShipPose from, ShipPose to) {
		return Math.abs(to.helmX() - from.helmX()) > MOVED_EPSILON
			|| Math.abs(to.helmY() - from.helmY()) > MOVED_EPSILON
			|| Math.abs(to.helmZ() - from.helmZ()) > MOVED_EPSILON
			|| Math.abs(to.yawRadians() - from.yawRadians()) > MOVED_EPSILON;
	}
}
