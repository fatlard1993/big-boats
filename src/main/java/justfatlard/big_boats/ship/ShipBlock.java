package justfatlard.big_boats.ship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * Represents a single block within a ship structure.
 * Stores the block's position relative to the helm, its block state,
 * and optional block entity data (for chests, furnaces, signs, etc.).
 */
public record ShipBlock(RelativeBlockPos relativePos, BlockState blockState,
						Optional<CompoundTag> blockEntityData, Optional<String> paint, Optional<CompoundTag> lock) {
	public static final Codec<ShipBlock> CODEC = RecordCodecBuilder.create(instance ->
		instance.group(
			RelativeBlockPos.CODEC.fieldOf("pos").forGetter(ShipBlock::relativePos),
			BlockState.CODEC.fieldOf("state").forGetter(ShipBlock::blockState),
			CompoundTag.CODEC.optionalFieldOf("nbt").forGetter(ShipBlock::blockEntityData),
			// Optional so a ship saved before chests could sail painted still reads.
			Codec.STRING.optionalFieldOf("paint").forGetter(ShipBlock::paint),
			// A chest-utils lock, carried with the chest the way its paint is.
			CompoundTag.CODEC.optionalFieldOf("lock").forGetter(ShipBlock::lock)
		).apply(instance, ShipBlock::new)
	);

	public ShipBlock(RelativeBlockPos relativePos, BlockState blockState) {
		this(relativePos, blockState, Optional.empty(), Optional.empty(), Optional.empty());
	}

	public ShipBlock(RelativeBlockPos relativePos, BlockState blockState,
			Optional<CompoundTag> blockEntityData) {
		this(relativePos, blockState, blockEntityData, Optional.empty(), Optional.empty());
	}

	public static ShipBlock fromWorld(Level world, BlockPos pos, BlockPos origin) {
		BlockState state = world.getBlockState(pos);
		RelativeBlockPos relativePos = RelativeBlockPos.fromWorldPos(pos, origin);
		Optional<CompoundTag> blockEntityData = Optional.empty();

		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (blockEntity != null) {
			var output = justfatlard.big_boats.util.ShipBlockNbtUtil.newOutput(world);
			blockEntity.saveWithId(output);
			blockEntityData = Optional.of(output.buildResult());
		}

		return new ShipBlock(relativePos, state, blockEntityData);
	}

	public ShipBlock withBlockEntityData(CompoundTag nbt) {
		return new ShipBlock(relativePos, blockState, Optional.ofNullable(nbt), paint, lock);
	}

	public ShipBlock withState(BlockState state) {
		return new ShipBlock(relativePos, state, blockEntityData, paint, lock);
	}

	/** The same block, remembering the colour it was painted where it stood. */
	public ShipBlock withPaint(String colour) {
		return new ShipBlock(relativePos, blockState, blockEntityData, Optional.ofNullable(colour), lock);
	}

	/** The same block, carrying the chest-utils lock it had where it stood, or none. */
	public ShipBlock withLock(CompoundTag carried) {
		return new ShipBlock(relativePos, blockState, blockEntityData, paint, Optional.ofNullable(carried));
	}

	/**
	 * Check if this block is at the origin (helm position).
	 */
	public boolean isHelm() {
		return relativePos.equals(RelativeBlockPos.ORIGIN);
	}

	public boolean hasBlockEntityData() {
		return blockEntityData.isPresent();
	}
}
