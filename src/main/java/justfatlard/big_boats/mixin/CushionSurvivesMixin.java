package justfatlard.big_boats.mixin;

import justfatlard.big_boats.ship.ShipSeats;
import net.minecraft.world.entity.decoration.Cushion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A cushion aboard a sailing ship does not check for the floor.
 *
 * <p>Vanilla has a cushion test every hundred ticks that something solid is under it, and break
 * itself when nothing is - correct for a cushion whose table was mined out from under it, and
 * fatal on a ship, whose deck while under way is a rendered structure and invisible collision
 * entities rather than blocks. Without this every cushion aboard drops into the sea a few seconds
 * after leaving port.
 *
 * <p>Scoped to exactly the cushions a ship has taken aboard, so a cushion anywhere else is judged
 * by vanilla's rule as before, and one left behind by a ship that stopped existing goes back to
 * being judged that way too.
 */
@Mixin(Cushion.class)
public abstract class CushionSurvivesMixin {

	@Inject(method = "survives", at = @At("HEAD"), cancellable = true)
	private void bigboats$surviveWhileAboard(CallbackInfoReturnable<Boolean> cir) {
		Cushion self = (Cushion) (Object) this;
		if (ShipSeats.isCarried(self.getUUID())) {
			cir.setReturnValue(true);
		}
	}
}
