package justfatlard.big_boats.mixin.client;

import justfatlard.big_boats.client.BigBoatsClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.passive.PigEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Client-side mixin to adjust camera distance when riding ships.
 * Ships appear as invisible saddled pigs to vanilla clients via Polymer.
 * Camera distance scales dynamically based on ship size.
 */
@Environment(EnvType.CLIENT)
@Mixin(Camera.class)
public abstract class CameraMixin {
	private static final Logger LOGGER = LoggerFactory.getLogger(CameraMixin.class);

	@Shadow
	private Entity focusedEntity;

	// These are the canonical camera constants. ShipConfig references this location.
	// They live here because CameraMixin runs on the client classloader where ShipConfig is unavailable.
	private static final float MIN_CAMERA_DISTANCE = 6.0f;
	private static final float MAX_CAMERA_DISTANCE = 20.0f;
	private static final float CAMERA_DISTANCE_PER_BLOCK = 0.15f;
	private static final int FALLBACK_BLOCK_COUNT = 10;

	@ModifyArg(
		method = "update",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/render/Camera;clipToSpace(F)F"
		),
		index = 0
	)
	private float modifyThirdPersonDistance(float originalDistance) {
		if (!BigBoatsClient.isRidingShip() || focusedEntity == null) {
			return originalDistance;
		}

		Entity vehicle = focusedEntity.getVehicle();
		if (vehicle instanceof PigEntity pig) {
			int blockCount = getShipBlockCount(pig);
			float distance = MIN_CAMERA_DISTANCE + (blockCount * CAMERA_DISTANCE_PER_BLOCK);
			return Math.min(MAX_CAMERA_DISTANCE, Math.max(MIN_CAMERA_DISTANCE, distance));
		}

		return originalDistance;
	}

	/**
	 * Gets the ship block count from the pig's boost time tracked data.
	 * The server encodes block count in this field via Polymer.
	 */
	private int getShipBlockCount(PigEntity pig) {
		try {
			TrackedData<Integer> boostTimeData = PigEntityAccessor.getBoostTimeData();
			return pig.getDataTracker().get(boostTimeData);
		} catch (RuntimeException e) {
			// Broad catch intentional: tracked data access can fail if Polymer encoding is
			// mismatched or Minecraft changes PigEntity's layout. Silent fallback is better
			// than a render-thread crash — the camera just won't scale optimally.
			LOGGER.debug("Failed to read ship block count from tracked data", e);
			return FALLBACK_BLOCK_COUNT;
		}
	}
}
