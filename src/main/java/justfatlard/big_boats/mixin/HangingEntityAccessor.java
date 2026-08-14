package justfatlard.big_boats.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.HangingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link HangingEntity}'s protected {@code setDirection}/{@code recalculateBoundingBox}
 * methods. Used by {@link justfatlard.big_boats.ship.ShipDocking} to reorient a restored item
 * frame/painting to the ship's rotated facing after {@link BlockAttachedEntityAccessor} updates
 * its attachment position.
 */
@Mixin(HangingEntity.class)
public interface HangingEntityAccessor {
	@Invoker("setDirection")
	void invokeSetDirection(Direction direction);

	@Invoker("recalculateBoundingBox")
	void invokeRecalculateBoundingBox();
}
