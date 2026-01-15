package justfatlard.big_boats.mixin;

import justfatlard.big_boats.util.PlayerInputStorage;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to capture player WASD input for custom vehicle controls.
 * This allows server-side access to movement input even when riding non-boat entities.
 */
@Mixin(ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {
	@Shadow
	public ServerPlayerEntity player;

	@Inject(method = "onPlayerInput", at = @At("HEAD"))
	private void capturePlayerInput(PlayerInputC2SPacket packet, CallbackInfo ci) {
		if (player != null) {
			PlayerInputStorage.setInput(player.getUuid(), packet.input());
		}
	}
}
