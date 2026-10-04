package justfatlard.big_boats.mixin;

import justfatlard.big_boats.ship.sea.AtSea;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A block aboard a ship at sea asking for a tick later - a chest re-counting who has it open - asks
 * for it in her stretch of {@link AtSea}, where no chunk is loaded to hold it. The game would log
 * each one as an error; they are let go instead. What they re-check is kept right by the screens
 * themselves opening and closing.
 */
@Mixin(LevelTicks.class)
public abstract class AtSeaTicksMixin {
	@Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
	private void bigBoats$noTicksAtSea(ScheduledTick<?> tick, CallbackInfo ci) {
		if (AtSea.inStretches(tick.pos())) ci.cancel();
	}
}
