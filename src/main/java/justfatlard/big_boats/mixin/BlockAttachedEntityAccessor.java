package justfatlard.big_boats.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link BlockAttachedEntity}'s protected {@code pos} field (the attached block
 * position) for mutation. Used by {@link justfatlard.big_boats.ship.ShipDocking} to
 * reposition a captured item frame/painting snapshot onto its new (rotated, translated)
 * attachment point when restoring decorations on dock, without needing to know the exact
 * NBT tag/codec format the entity itself uses internally.
 */
@Mixin(BlockAttachedEntity.class)
public interface BlockAttachedEntityAccessor {
	@Accessor("pos")
	@Mutable
	void setAttachedPos(BlockPos pos);
}
