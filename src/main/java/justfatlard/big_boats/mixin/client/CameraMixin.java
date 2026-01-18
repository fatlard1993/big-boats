package justfatlard.big_boats.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.world.BlockView;
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
	@Shadow
	private Entity focusedEntity;

	// Camera distance constants
	private static final float BASE_DISTANCE = 4.0f;      // Default Minecraft 3rd person distance
	private static final float MIN_SHIP_DISTANCE = 6.0f;  // Minimum distance for any ship
	private static final float MAX_SHIP_DISTANCE = 20.0f; // Maximum distance cap
	private static final float DISTANCE_PER_BLOCK = 0.15f; // Distance increase per block

	/**
	 * Modifies the camera distance argument passed to clipToSpace when in 3rd person.
	 * Detects if player is riding a ship and calculates distance based on ship size.
	 */
	@ModifyArg(
		method = "update",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/render/Camera;clipToSpace(F)F"
		),
		index = 0
	)
	private float modifyThirdPersonDistance(float originalDistance) {
		if (focusedEntity == null) {
			return originalDistance;
		}

		// Check if we're riding something
		Entity vehicle = focusedEntity.getVehicle();
		if (vehicle == null) {
			return originalDistance;
		}

		// Ships appear as invisible pigs to vanilla clients via Polymer
		if (vehicle instanceof PigEntity pig) {
			// Check if it looks like our ship disguise (invisible pig)
			if (pig.isInvisible()) {
				// Get ship block count from the repurposed boost time field
				int blockCount = getShipBlockCount(pig);

				// Calculate dynamic camera distance based on ship size
				// Larger ships get more distance for better visibility
				float distance = MIN_SHIP_DISTANCE + (blockCount * DISTANCE_PER_BLOCK);

				// Clamp to reasonable bounds
				return Math.min(MAX_SHIP_DISTANCE, Math.max(MIN_SHIP_DISTANCE, distance));
			}
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
		} catch (Exception e) {
			// Fallback if accessor fails
			return 10; // Assume medium ship
		}
	}
}
