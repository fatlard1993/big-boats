package justfatlard.big_boats.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores player input state captured from ServerboundPlayerInputPacket.
 * This allows server-side access to WASD input for custom vehicles.
 */
public class PlayerInputStorage {
	private static final Map<UUID, Input> playerInputs = new ConcurrentHashMap<>();

	public static void setInput(UUID playerId, Input input) {
		playerInputs.put(playerId, input);
	}

	public static Input getInput(ServerPlayer player) {
		return playerInputs.getOrDefault(player.getUUID(), Input.EMPTY);
	}

	public static void removePlayer(UUID playerId) {
		playerInputs.remove(playerId);
	}
}
