package justfatlard.big_boats.mixin;

import justfatlard.big_boats.ship.ShipCollisionEntities;
import justfatlard.big_boats.ship.ShipRiders;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A client that walks the deck itself is never sent the boxes standing in for it: it would collide
 * with them too, a tick or three away from the deck it can see, and be caught between the two.
 */
@Mixin(Entity.class)
public abstract class HullCollisionUnsentMixin {

	@Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
	private void bigboats$unsentToDeckWalkers(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
		if (ShipCollisionEntities.isHullCollision(((Entity) (Object) this).getUUID()) && ShipRiders.walksDecks(player)) {
			cir.setReturnValue(false);
		}
	}
}
