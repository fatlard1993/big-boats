package justfatlard.big_boats.ship;

import justfatlard.big_boats.mixin.InteractionAccessor;
import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages collision shulker entities and the helm interaction entity for a ship.
 *
 * <p>Each hull block gets an invisible shulker for server-side collision. The helm gets an
 * {@link Interaction} entity for mount clicks. Interior blocks are skipped to conserve the
 * server entity budget. Shulker positions update via tick-spreading (threshold + interval).</p>
 *
 * <p>UUID tracking enables crash recovery: on load, orphaned entities from a previous session
 * are cleaned up before new ones are spawned.</p>
 */
public class ShipCollisionEntities {
	private static final Logger LOGGER = LoggerFactory.getLogger(ShipCollisionEntities.class);

	// Keyed by relative position to avoid index coupling
	private final Map<RelativeBlockPos, Shulker> collisionShulkers = new HashMap<>();
	private final Set<UUID> collisionShulkerUUIDs = new HashSet<>();

	private Interaction helmInteraction;

	private final Set<UUID> trackedChildEntityUUIDs = new LinkedHashSet<>();

	/**
	 * Spawns collision shulkers for hull blocks and a helm interaction entity.
	 * Only hull blocks (exterior) get shulkers; interior blocks can never collide
	 * with the world and would waste the server entity budget.
	 */
	public void spawnAll(ServerLevel world, List<ShipBlock> blocks, ShipPose pose,
						 Collection<RelativeBlockPos> hullPositions) {
		// Everything this spawns is tracked in four places; clearing one of them and spawning
		// over the rest left the previous set alive and unreferenced, with no way back to it.
		// Discarding first is the only clear that reaches all four.
		discardAll();
		int skipped = 0;

		for (ShipBlock block : blocks) {
			// Skip helm (entity itself handles helm collision) and interior blocks
			if (block.isHelm() || !hullPositions.contains(block.relativePos())) {
				skipped++;
				continue;
			}
			try {
				spawnShulkerForBlock(world, block, pose);
			} catch (RuntimeException e) {
				// Per-block catch: a single shulker spawn failure shouldn't abort all collision setup.
				LOGGER.warn("Failed to spawn collision shulker for block at {}", block.relativePos(), e);
			}
		}

		if (skipped > 0) {
			LOGGER.debug("Skipped {} interior blocks for collision shulkers", skipped);
		}

		spawnHelmInteraction(world, pose);
	}

	/**
	 * Spawns the invisible shulker providing server-side collision for one virtual ship block.
	 */
	private void spawnShulkerForBlock(ServerLevel world, ShipBlock block, ShipPose pose) {
		Shulker shulker = new Shulker(EntityTypes.SHULKER, world);

		// Position at block center so the shulker's 1x1 hitbox covers the full visual block.
		// toWorld gives the block corner; +0.5 on X/Z centers the shulker.
		Vec3 worldPos = pose.toWorld(block.relativePos());
		shulker.setPos(worldPos.x + 0.5, worldPos.y, worldPos.z + 0.5);

		shulker.addEffect(new MobEffectInstance(
			MobEffects.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, false
		));

		shulker.setNoAi(true);
		shulker.setNoGravity(true);
		shulker.setSilent(true);
		shulker.setPermanentlyInvulnerable(true);

		world.addFreshEntity(shulker);
		collisionShulkers.put(block.relativePos(), shulker);
		collisionShulkerUUIDs.add(shulker.getUUID());
		ALL_COLLISION_SHULKERS.add(shulker.getUUID());
		trackedChildEntityUUIDs.add(shulker.getUUID());
	}

	private void spawnHelmInteraction(ServerLevel world, ShipPose pose) {
		Interaction interaction = new Interaction(EntityTypes.INTERACTION, world);
		Vec3 center = pose.helmCenter();
		interaction.setPos(center.x, center.y, center.z);
		InteractionAccessor accessor = (InteractionAccessor) interaction;
		accessor.invokeSetWidth(1.0f);
		accessor.invokeSetHeight(2.0f);
		accessor.invokeSetResponse(true);
		world.addFreshEntity(interaction);
		// Assign AFTER successful spawn: if addFreshEntity throws, helmInteraction stays null
		// rather than pointing to an entity that doesn't exist in the world.
		helmInteraction = interaction;
		trackedChildEntityUUIDs.add(interaction.getUUID());
	}

	public void updatePositions(ShipPose pose) {
		for (var entry : collisionShulkers.entrySet()) {
			Shulker shulker = entry.getValue();
			// A shulker taken out from under us - by a chunk unload, a command, anything - used
			// to be skipped here and never thought about again, leaving a one-block hole in the
			// deck's collision that a rider standing on it falls straight through. It is noticed
			// here and refilled by the next spawnAll; the hole no longer outlives the voyage.
			if (shulker.isRemoved()) {
				missingCollision = true;
				continue;
			}

			// toWorld gives block corner; +0.5 on X/Z centers the shulker
			Vec3 worldPos = pose.toWorld(entry.getKey());
			shulker.setPos(worldPos.x + 0.5, worldPos.y, worldPos.z + 0.5);
		}

		if (helmInteraction != null && !helmInteraction.isRemoved()) {
			Vec3 center = pose.helmCenter();
			helmInteraction.setPos(center.x, center.y, center.z);
		}
	}

	/**
	 * Move the collision hull to where the ship now is. Every tick, without asking.
	 *
	 * <p>It used to ask, against a yaw threshold, a distance threshold and a tick interval. The
	 * interval is one, so the question always answered yes and the two thresholds decided nothing
	 * - dead policy that still read as live, and would have come back wrong if anyone revived it,
	 * because this is handed the lead pose and the thresholds were compared against the real one.
	 */
	public void tickUpdate(ShipPose pose) {
		updatePositions(pose);
	}

	/** For blocks broken off while the ship was docked. */
	public void removeStaleShulkers(Set<RelativeBlockPos> survivingPositions) {
		var iter = collisionShulkers.entrySet().iterator();
		while (iter.hasNext()) {
			var entry = iter.next();
			if (!survivingPositions.contains(entry.getKey())) {
				UUID id = entry.getValue().getUUID();
				if (!entry.getValue().isRemoved()) entry.getValue().discard();
				collisionShulkerUUIDs.remove(id);
				ALL_COLLISION_SHULKERS.remove(id);
				trackedChildEntityUUIDs.remove(id);
				iter.remove();
			}
		}
	}

	/** On docking, where real blocks take the collision back, and on removal. */
	public void discardAll() {
		for (Shulker shulker : collisionShulkers.values()) {
			try {
				if (!shulker.isRemoved()) {
					shulker.discard();
				}
			} catch (RuntimeException e) {
				// Per-entity catch: one shulker failing to discard shouldn't leave others alive
				LOGGER.warn("Failed to discard collision shulker {}", shulker.getUUID(), e);
			}
		}
		ALL_COLLISION_SHULKERS.removeAll(collisionShulkerUUIDs);
		collisionShulkers.clear();
		collisionShulkerUUIDs.clear();

		try {
			if (helmInteraction != null && !helmInteraction.isRemoved()) {
				helmInteraction.discard();
			}
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to discard helm interaction entity", e);
		}
		helmInteraction = null;

		trackedChildEntityUUIDs.clear();
	}

	/**
	 * Discard what is left of a previous session's collision hull, and report what is still missing.
	 *
	 * <p>Called from the ship's tick rather than from its deserialisation, because a UUID resolves
	 * against the level's live lookup and during a load the ship, its shulkers and the chunk they
	 * share are all still arriving. Asked too early, every lookup returned null, which this read as
	 * "already gone" - and the list was a local that died with the method, so the only record of
	 * those entities went with it. What it leaves behind is a permanently invulnerable, invisible,
	 * solid block per hull block, with no remaining handle to remove it, once per crash forever.
	 *
	 * @return the UUIDs that could not be resolved yet, to be asked about again
	 */
	public List<UUID> cleanupOrphanedEntities(ServerLevel world, List<UUID> oldUUIDs) {
		if (oldUUIDs.isEmpty()) return List.of();

		List<UUID> unresolved = new ArrayList<>();
		for (UUID uuid : oldUUIDs) {
			Entity entity = world.getEntity(uuid);
			if (entity == null) {
				unresolved.add(uuid);
			} else if (!entity.isRemoved()) {
				entity.discard();
			}
		}
		return List.copyOf(unresolved);
	}

	public boolean isHelmInteraction(Entity entity) {
		return helmInteraction != null && helmInteraction.equals(entity);
	}

	/**
	 * Every collision shulker on the server, by id.
	 *
	 * <p>Read by {@code CollisionShulkerMixin} to keep these out of anything that looks at what a
	 * player is pointing at. Static because a mixin has no way to reach the ship that owns one.
	 */
	private static final java.util.Set<UUID> ALL_COLLISION_SHULKERS =
		java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * Whether a collision box has gone missing since the last time the hull was rebuilt.
	 *
	 * <p>Read by the ship each tick; see {@link #updatePositions}. Not repaired in place because
	 * spawning inside a position loop would mutate the map being walked.
	 */
	public boolean hasMissingCollision() {
		return missingCollision;
	}

	public void clearMissingCollision() {
		missingCollision = false;
	}

	private boolean missingCollision = false;

	/** Forget every known collision box. Called when the server stops; see {@code BigBoats}. */
	public static void forgetAll() {
		ALL_COLLISION_SHULKERS.clear();
	}

	/** Whether this entity is one of the invisible boxes holding a deck up. */
	public static boolean isHullCollision(UUID id) {
		return !ALL_COLLISION_SHULKERS.isEmpty() && ALL_COLLISION_SHULKERS.contains(id);
	}

	public boolean isCollisionShulker(Entity entity) {
		return collisionShulkerUUIDs.contains(entity.getUUID());
	}

	/** For saving. The per-tick question is {@link #ownsChildEntity}, which copies nothing. */
	public List<UUID> getTrackedChildEntityUUIDs() {
		return List.copyOf(trackedChildEntityUUIDs);
	}

	/**
	 * Whether this entity is one the ship spawned.
	 *
	 * <p>Asked of every entity near the hull, every tick. It used to be asked by copying the whole
	 * child list - one UUID per hull block, up to a couple of thousand - and scanning it, so the
	 * cost of standing near a big ship was paid by the ship being big.
	 */
	public boolean ownsChildEntity(UUID id) {
		return trackedChildEntityUUIDs.contains(id);
	}

}
