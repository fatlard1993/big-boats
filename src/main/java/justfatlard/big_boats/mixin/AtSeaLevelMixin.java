package justfatlard.big_boats.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import justfatlard.big_boats.ship.sea.AtSea;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Blocks above the build height that belong to a ship at sea are her blocks: read, written and
 * holding block entities as they would in the world. See {@link AtSea}. Everything below the floor
 * is untouched after one comparison.
 *
 * <p>Answered on the way out rather than at the head: a stretch is only ever opened where the
 * world stops below it, so the game has already turned the position away as out of bounds,
 * touching nothing, by the time it gets here.
 */
@Mixin(Level.class)
public abstract class AtSeaLevelMixin {
	@ModifyReturnValue(method = "getBlockState", at = @At("RETURN"))
	private BlockState bigBoats$shipState(BlockState original, @Local(argsOnly = true) BlockPos pos) {
		if (!AtSea.above(pos)) return original;
		BlockState state = AtSea.getBlockState((Level) (Object) this, pos);
		return state != null ? state : original;
	}

	@ModifyReturnValue(method = "getBlockEntity", at = @At("RETURN"))
	private BlockEntity bigBoats$shipBlockEntity(BlockEntity original, @Local(argsOnly = true) BlockPos pos) {
		if (!AtSea.above(pos)) return original;
		BlockEntity blockEntity = AtSea.getBlockEntity((Level) (Object) this, pos);
		return blockEntity != null ? blockEntity : original;
	}

	@ModifyReturnValue(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
		at = @At("RETURN"))
	private boolean bigBoats$shipSetBlock(boolean original, @Local(argsOnly = true) BlockPos pos,
			@Local(argsOnly = true) BlockState state, @Local(argsOnly = true, ordinal = 0) int flags,
			@Local(argsOnly = true, ordinal = 1) int limit) {
		if (!AtSea.above(pos)) return original;
		Level level = (Level) (Object) this;
		return AtSea.at(level, pos) != null ? AtSea.setBlock(level, pos, state, flags, limit) : original;
	}
}
