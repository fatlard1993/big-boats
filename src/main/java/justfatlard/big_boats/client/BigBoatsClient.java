package justfatlard.big_boats.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.passive.PigEntity;

/**
 * Client-side initializer for Big Boats.
 * Provides optional client features like adjusted camera for larger ships.
 */
@Environment(EnvType.CLIENT)
public class BigBoatsClient implements ClientModInitializer {
	// Whether the player is currently riding a ship (volatile: read from render thread in CameraMixin)
	private static volatile boolean ridingShip = false;
	// Store the perspective before we switched to ship mode
	private static Perspective previousPerspective = null;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

		// Reset state on disconnect to prevent stale camera perspective on reconnect
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ridingShip = false;
			previousPerspective = null;
		});
	}

	private void onClientTick(MinecraftClient client) {
		if (client.player == null) {
			ridingShip = false;
			previousPerspective = null;
			return;
		}

		boolean isRidingShip = isPlayerRidingShip(client);

		// Detect state change: just mounted a ship
		if (isRidingShip && !ridingShip) {
			// Save current perspective before switching
			previousPerspective = client.options.getPerspective();

			// Switch to 3rd person rear view for ship driving
			if (previousPerspective == Perspective.FIRST_PERSON) {
				client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
			}
		}

		// Detect state change: just dismounted from ship
		if (!isRidingShip && ridingShip) {
			// Restore previous perspective
			if (previousPerspective != null) {
				client.options.setPerspective(previousPerspective);
				previousPerspective = null;
			}
		}

		ridingShip = isRidingShip;
	}

	/**
	 * Checks if the player is currently riding a ship.
	 * Ships appear as invisible pigs via Polymer.
	 */
	private boolean isPlayerRidingShip(MinecraftClient client) {
		if (client.player == null) return false;

		var vehicle = client.player.getVehicle();
		if (vehicle == null) return false;

		// Ships appear as invisible saddled pigs via Polymer's entity disguise.
		// See MultiBlockShipEntity.modifyRawTrackedData() for the server-side encoding.
		// False-positive risk with other invisible pigs is low in practice.
		if (vehicle instanceof PigEntity pig) {
			return pig.isInvisible();
		}

		return false;
	}

	/**
	 * Returns true if player is currently riding a ship (for use by camera mixin).
	 */
	public static boolean isRidingShip() {
		return ridingShip;
	}
}
