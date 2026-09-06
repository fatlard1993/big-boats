package justfatlard.big_boats.mixin;

import justfatlard.big_boats.ship.ShipCollisionEntities;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The invisible boxes holding a deck up are not things you can point at.
 *
 * <p>A sailing ship's blocks are rendered, not real, so something has to be solid enough to stand
 * on - that is what the shulkers are. They were solid to everything else too: the crosshair found
 * them, so attacking the deck swung at an invisible mob, and anything that names what you are
 * looking at named one. On a ship with block-tip installed the deck under your feet announced
 * itself as "Shulker".
 *
 * <p>Being unpickable does not make them any less solid. Collision is a bounding box question and
 * this is a raycast one: they still hold the deck up, they just stop being a target.
 */
@Mixin(LivingEntity.class)
public abstract class CollisionShulkerMixin {

	@Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
	private void bigboats$hullCollisionIsNotATarget(CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (ShipCollisionEntities.isHullCollision(self.getUUID())) {
			cir.setReturnValue(false);
		}
	}
}
