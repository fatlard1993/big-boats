package justfatlard.big_boats.mixin;

import justfatlard.big_boats.ship.ShipCollisionEntities;
import justfatlard.big_boats.ship.ShipRiders;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The invisible boxes holding a deck up are not solid to a player whose client walks the deck
 * itself.
 *
 * <p>That client stands on the ship it draws, which is where the ship was a moment ago; these boxes
 * are where the ship is now, and a little ahead. The server checks every step a player sends
 * against what is solid here, and a step onto a deck the boxes have already left, or into one they
 * have moved into, was sent back - the rubber-banding of anyone standing on a moving ship.
 */
@Mixin(Shulker.class)
public abstract class HullCollisionDeckWalkerMixin {

	@Inject(method = "canBeCollidedWith", at = @At("HEAD"), cancellable = true)
	private void bigboats$notSolidToDeckWalkers(Entity other, CallbackInfoReturnable<Boolean> cir) {
		if (other instanceof ServerPlayer player && ShipRiders.walksDecks(player)
				&& ShipCollisionEntities.isHullCollision(((Entity) (Object) this).getUUID())) {
			cir.setReturnValue(false);
		}
	}
}
