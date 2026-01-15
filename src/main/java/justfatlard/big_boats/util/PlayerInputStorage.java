package justfatlard.big_boats.util;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.PlayerInput;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores player input state captured from PlayerInputC2SPacket.
 * This allows server-side access to WASD input for custom vehicles.
 */
public class PlayerInputStorage {
	private static final Map<UUID, PlayerInput> playerInputs = new ConcurrentHashMap<>();

	public static void setInput(UUID playerId, PlayerInput input) {
		playerInputs.put(playerId, input);
	}

	public static PlayerInput getInput(ServerPlayerEntity player) {
		return playerInputs.getOrDefault(player.getUuid(), PlayerInput.DEFAULT);
	}

	public static void removePlayer(UUID playerId) {
		playerInputs.remove(playerId);
	}
}
