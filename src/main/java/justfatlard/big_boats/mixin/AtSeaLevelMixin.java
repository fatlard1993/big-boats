package justfatlard.big_boats.mixin;

import justfatlard.big_boats.ship.sea.AtSea;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks above the build height that belong to a ship at sea are her blocks: read, written and
 * holding block entities as they would in the world. See {@link AtSea}. Everything below the floor
 * is untouched after one comparison.
 */
@Mixin(Level.class)
public abstract class AtSeaLevelMixin {
	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void bigBoats$shipState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (!AtSea.above(pos)) return;
		BlockState state = AtSea.getBlockState((Level) (Object) this, pos);
		if (state != null) cir.setReturnValue(state);
	}

	@Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
	private void bigBoats$shipBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
		if (!AtSea.above(pos)) return;
		BlockEntity blockEntity = AtSea.getBlockEntity((Level) (Object) this, pos);
		if (blockEntity != null) cir.setReturnValue(blockEntity);
	}

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
		at = @At("HEAD"), cancellable = true)
	private void bigBoats$shipSetBlock(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
		if (!AtSea.above(pos)) return;
		Level level = (Level) (Object) this;
		if (AtSea.at(level, pos) != null) cir.setReturnValue(AtSea.setBlock(level, pos, state, flags, limit));
	}
}
