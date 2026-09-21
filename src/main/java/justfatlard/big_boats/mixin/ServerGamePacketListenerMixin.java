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

	// After the thread hop, not at HEAD. PacketUtils.ensureRunningOnSameThread reschedules the
	// packet onto the server thread by throwing, so a HEAD injection runs once on the Netty I/O
	// thread and again on the server thread when the packet is re-handled - writing shared state
	// from a thread that has no business holding it, and doing everything twice.
	@Inject(method = "handlePlayerInput",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
			shift = At.Shift.AFTER))
	private void capturePlayerInput(ServerboundPlayerInputPacket packet, CallbackInfo ci) {
		if (player != null) {
			PlayerInputStorage.setInput(player.getUUID(), packet.input());
		}
	}
}
