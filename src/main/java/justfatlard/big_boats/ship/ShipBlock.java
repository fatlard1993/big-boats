package justfatlard.big_boats.ship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;

import java.util.Optional;

/**
 * Represents a single block within a ship structure.
 * Stores the block's position relative to the helm, its block state,
 * and optional block entity data (for chests, furnaces, signs, etc.).
 */
public record ShipBlock(RelativeBlockPos relativePos, BlockState blockState, Optional<NbtCompound> blockEntityData) {
	public static final Codec<ShipBlock> CODEC = RecordCodecBuilder.create(instance ->
		instance.group(
			RelativeBlockPos.CODEC.fieldOf("pos").forGetter(ShipBlock::relativePos),
			BlockState.CODEC.fieldOf("state").forGetter(ShipBlock::blockState),
			NbtCompound.CODEC.optionalFieldOf("nbt").forGetter(ShipBlock::blockEntityData)
		).apply(instance, ShipBlock::new)
	);

	/**
	 * Constructor without block entity data (for backwards compatibility).
	 */
	public ShipBlock(RelativeBlockPos relativePos, BlockState blockState) {
		this(relativePos, blockState, Optional.empty());
	}

	/**
	 * Creates a ShipBlock that represents the helm position (origin).
	 */
	public static ShipBlock helm(BlockState state) {
		return new ShipBlock(RelativeBlockPos.ORIGIN, state, Optional.empty());
	}

	/**
	 * Creates a copy of this ShipBlock with updated block entity data.
	 */
	public ShipBlock withBlockEntityData(NbtCompound nbt) {
		return new ShipBlock(relativePos, blockState, Optional.ofNullable(nbt));
	}

	/**
	 * Check if this block is at the origin (helm position).
	 */
	public boolean isHelm() {
		return relativePos.equals(RelativeBlockPos.ORIGIN);
	}

	/**
	 * Check if this block has block entity data.
	 */
	public boolean hasBlockEntityData() {
		return blockEntityData.isPresent();
	}
}
