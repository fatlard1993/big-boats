package justfatlard.big_boats.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.passive.PigEntity;

/**
 * Client-side initializer for Big Boats.
 * Provides optional client features like adjusted camera for larger ships.
 */
@Environment(EnvType.CLIENT)
public class BigBoatsClient implements ClientModInitializer {
	// Track if we're currently riding a ship
	private static boolean wasRidingShip = false;
	// Store the perspective before we switched to ship mode
	private static Perspective previousPerspective = null;

	@Override
	public void onInitializeClient() {
		// Register tick handler to detect mount/dismount
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

		System.out.println("[big-boats] Client features loaded - ship camera adjustment enabled!");
	}

	private void onClientTick(MinecraftClient client) {
		if (client.player == null) {
			wasRidingShip = false;
			previousPerspective = null;
			return;
		}

		boolean isRidingShip = isPlayerRidingShip(client);

		// Detect state change: just mounted a ship
		if (isRidingShip && !wasRidingShip) {
			// Save current perspective before switching
			previousPerspective = client.options.getPerspective();

			// Switch to 3rd person rear view for ship driving
			if (previousPerspective == Perspective.FIRST_PERSON) {
				client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
			}
		}

		// Detect state change: just dismounted from ship
		if (!isRidingShip && wasRidingShip) {
			// Restore previous perspective
			if (previousPerspective != null) {
				client.options.setPerspective(previousPerspective);
				previousPerspective = null;
			}
		}

		wasRidingShip = isRidingShip;
	}

	/**
	 * Checks if the player is currently riding a ship.
	 * Ships appear as invisible pigs via Polymer.
	 */
	private boolean isPlayerRidingShip(MinecraftClient client) {
		if (client.player == null) return false;

		var vehicle = client.player.getVehicle();
		if (vehicle == null) return false;

		// Ships appear as invisible pigs to vanilla clients
		if (vehicle instanceof PigEntity pig) {
			return pig.isInvisible();
		}

		return false;
	}

	/**
	 * Returns true if player is currently riding a ship (for use by camera mixin).
	 */
	public static boolean isRidingShip() {
		return wasRidingShip;
	}
}
