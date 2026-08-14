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

	private final List<UUID> trackedChildEntityUUIDs = new ArrayList<>();

	// Tick-spreading: collision shulker updates
	private int ticksSinceUpdate = 0;
	private float lastUpdateYaw = 0;
	private double lastUpdateX = 0;
	private double lastUpdateZ = 0;

	/**
	 * Spawns collision shulkers for hull blocks and a helm interaction entity.
	 * Only hull blocks (exterior) get shulkers; interior blocks can never collide
	 * with the world and would waste the server entity budget.
	 */
	public void spawnAll(ServerLevel world, List<ShipBlock> blocks, ShipPose pose,
						 Collection<RelativeBlockPos> hullPositions) {
		trackedChildEntityUUIDs.clear();
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
			if (shulker.isRemoved()) continue;

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
	 * Checks whether collision positions need updating based on tick-spreading thresholds.
	 * If update is needed, performs it and resets tracking state.
	 *
	 * @return true if positions were updated
	 */
	public boolean tickUpdate(ShipPose pose) {
		ticksSinceUpdate++;
		boolean needsUpdate =
			Math.abs(pose.yawRadians() - lastUpdateYaw) > ShipConfig.COLLISION_UPDATE_YAW_THRESHOLD ||
			Math.abs(pose.helmX() - lastUpdateX) + Math.abs(pose.helmZ() - lastUpdateZ) > ShipConfig.COLLISION_UPDATE_POS_THRESHOLD ||
			ticksSinceUpdate >= ShipConfig.COLLISION_UPDATE_TICK_INTERVAL;

		if (needsUpdate) {
			updatePositions(pose);
			lastUpdateYaw = pose.yawRadians();
			lastUpdateX = pose.helmX();
			lastUpdateZ = pose.helmZ();
			ticksSinceUpdate = 0;
			return true;
		}
		return false;
	}

	/**
	 * Syncs tick-spread tracking state to current values.
	 * Call after undocking or any position reset.
	 */
	public void syncTrackingState(double helmX, double helmZ, float yawRadians) {
		lastUpdateYaw = yawRadians;
		lastUpdateX = helmX;
		lastUpdateZ = helmZ;
		ticksSinceUpdate = 0;
	}

	/**
	 * Removes shulkers for blocks no longer in the ship (e.g., broken while docked).
	 */
	public void removeStaleShulkers(Set<RelativeBlockPos> survivingPositions) {
		var iter = collisionShulkers.entrySet().iterator();
		while (iter.hasNext()) {
			var entry = iter.next();
			if (!survivingPositions.contains(entry.getKey())) {
				if (!entry.getValue().isRemoved()) entry.getValue().discard();
				collisionShulkerUUIDs.remove(entry.getValue().getUUID());
				iter.remove();
			}
		}
	}

	/**
	 * Discards all collision shulkers and the helm interaction entity.
	 * Call when docking (real blocks take over collision) or on entity removal.
	 */
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
	 * Cleans up orphaned entities from a previous session (crash recovery).
	 */
	public void cleanupOrphanedEntities(ServerLevel world, List<UUID> oldUUIDs) {
		if (oldUUIDs.isEmpty()) return;

		for (UUID uuid : oldUUIDs) {
			Entity entity = world.getEntity(uuid);
			if (entity != null && !entity.isRemoved()) {
				entity.discard();
			}
		}
	}

	public boolean isHelmInteraction(Entity entity) {
		return helmInteraction != null && helmInteraction.equals(entity);
	}

	public boolean isCollisionShulker(Entity entity) {
		return collisionShulkerUUIDs.contains(entity.getUUID());
	}

	public List<UUID> getTrackedChildEntityUUIDs() {
		return List.copyOf(trackedChildEntityUUIDs);
	}

	public Map<RelativeBlockPos, Shulker> getCollisionShulkers() {
		return Collections.unmodifiableMap(collisionShulkers);
	}
}
