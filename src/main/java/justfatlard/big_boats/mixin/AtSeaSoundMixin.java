package justfatlard.big_boats.mixin;

import justfatlard.big_boats.ship.sea.AtSea;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A sound made by a block aboard a ship at sea - a door, a chest lid, a furnace crackling - is heard
 * where the block really is, on her deck. Without this it would be made in her stretch of
 * {@link AtSea}, far above anyone's hearing.
 */
@Mixin(ServerLevel.class)
public abstract class AtSeaSoundMixin {
	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
		at = @At("HEAD"), cancellable = true)
	private void bigBoats$shipSound(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound,
			SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
		if (!AtSea.above(y)) return;
		ServerLevel self = (ServerLevel) (Object) this;
		Vec3 real = AtSea.toWorld(self, x, y, z);
		if (real == null) return;
		ci.cancel();
		// Heard by everyone, the player who did it included: the game leaves them out because their
		// own client already played it, and at sea their client played nothing.
		self.playSeededSound(null, real.x, real.y, real.z, sound, source, volume, pitch, seed);
	}

	/**
	 * A block event up here - a chest lid, a bell, a note block - run now, on the block it is for.
	 * The game holds block events back until their chunk ticks, which up here it never does, so
	 * they would wait forever and the note block would never sound.
	 */
	@Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
	private void bigBoats$shipBlockEvent(BlockPos pos, net.minecraft.world.level.block.Block block, int b0, int b1, CallbackInfo ci) {
		if (!AtSea.inStretches(pos)) return;
		ci.cancel();
		ServerLevel self = (ServerLevel) (Object) this;
		if (AtSea.at(self, pos) == null) return;
		net.minecraft.world.level.block.state.BlockState state = self.getBlockState(pos);
		if (state.is(block)) state.triggerEvent(self, pos, b0, b1);
	}

	@Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
	private void bigBoats$shipEvent(@Nullable Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
		if (!AtSea.above(pos)) return;
		ServerLevel self = (ServerLevel) (Object) this;
		Vec3 real = AtSea.toWorld(self, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		if (real == null) return;
		ci.cancel();
		self.levelEvent(null, type, BlockPos.containing(real), data);
	}
}
