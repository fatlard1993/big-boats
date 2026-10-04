package justfatlard.big_boats.mixin;

import justfatlard.big_boats.ship.sea.AtSea;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A block entity aboard a ship at sea that changed is written back to her blocks; see {@link AtSea}. */
@Mixin(BlockEntity.class)
public abstract class AtSeaBlockEntityMixin {
	/**
	 * The static form, which the instance one calls and which furnaces, hoppers and brewing stands
	 * call themselves as they work: hooking only the instance one missed every change they made.
	 */
	@Inject(method = "setChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
		at = @At("HEAD"))
	private static void bigBoats$shipChanged(Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
		if (AtSea.above(pos)) AtSea.changed(level, pos);
	}
}
