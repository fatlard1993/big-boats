package justfatlard.big_boats.mixin;

import justfatlard.big_boats.util.PlayerInputStorage;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to capture player WASD input for custom vehicle controls.
 * This allows server-side access to movement input even when riding non-boat entities.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "handlePlayerInput", at = @At("HEAD"))
	private void capturePlayerInput(ServerboundPlayerInputPacket packet, CallbackInfo ci) {
		if (player != null) {
			PlayerInputStorage.setInput(player.getUUID(), packet.input());
		}
	}
}
