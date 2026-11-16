package justfatlard.big_boats.ship;

import justfatlard.big_boats.mixin.InteractionAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entities a sailing ship spawns for itself: the helm's {@link Interaction}, for mount clicks.
 *
 * <p>The deck needs none. Pandorical makes a walkable structure solid to whatever the server moves,
 * and a Pandorical client stands its own player on the deck it draws.
 *
 * <p>UUID tracking enables crash recovery: on load, entities a previous session left behind are
 * discarded. Ships saved before the deck stopped being made of entities list every hull box they
 * had, and are cleared out the same way.
 */
public class ShipChildEntities {
	private static final Logger LOGGER = LoggerFactory.getLogger(ShipChildEntities.class);

	private Interaction helmInteraction;

	public void spawnHelm(ServerLevel world, ShipPose pose) {
		discardAll();
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
	}

	public void updatePositions(ShipPose pose) {
		if (helmInteraction != null && !helmInteraction.isRemoved()) {
			Vec3 center = pose.helmCenter();
			helmInteraction.setPos(center.x, center.y, center.z);
		}
	}

	/** On docking and on removal. */
	public void discardAll() {
		try {
			if (helmInteraction != null && !helmInteraction.isRemoved()) {
				helmInteraction.discard();
			}
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to discard helm interaction entity", e);
		}
		helmInteraction = null;
	}

	/**
	 * Discard what is left of a previous session's entities, and report what is still missing.
	 *
	 * <p>Called from the ship's tick rather than from its deserialisation, because a UUID resolves
	 * against the level's live lookup and during a load the ship, its entities and the chunk they
	 * share are all still arriving. Asked too early, every lookup returned null, which this read as
	 * "already gone" - and the list was a local that died with the method, so the only record of
	 * those entities went with it.
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

	/** For saving, so a crash does not leave the helm's entity behind. */
	public List<UUID> getTrackedChildEntityUUIDs() {
		return helmInteraction == null ? List.of() : List.of(helmInteraction.getUUID());
	}

	/** Whether this entity is one the ship spawned. */
	public boolean ownsChildEntity(UUID id) {
		return helmInteraction != null && helmInteraction.getUUID().equals(id);
	}
}
