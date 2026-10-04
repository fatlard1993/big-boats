package justfatlard.big_boats.mixin;

import justfatlard.big_boats.ship.sea.AtSea;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A chest or crafting table aboard a ship at sea is in reach where it really is, on her deck, not
 * where it stands in her stretch of {@link AtSea}: what keeps a screen open is the game asking this.
 */
@Mixin(Player.class)
public abstract class AtSeaReachMixin {
	@Inject(method = "isWithinBlockInteractionRange", at = @At("HEAD"), cancellable = true)
	private void bigBoats$shipReach(BlockPos pos, double buffer, CallbackInfoReturnable<Boolean> cir) {
		if (AtSea.inStretches(pos)) cir.setReturnValue(AtSea.inReach((Player) (Object) this, pos, buffer));
	}
}
