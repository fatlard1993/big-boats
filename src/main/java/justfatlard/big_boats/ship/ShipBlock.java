package justfatlard.big_boats.ship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.block.BlockState;

/**
 * Represents a single block within a ship structure.
 * Stores the block's position relative to the helm and its block state.
 */
public record ShipBlock(RelativeBlockPos relativePos, BlockState blockState) {
	public static final Codec<ShipBlock> CODEC = RecordCodecBuilder.create(instance ->
		instance.group(
			RelativeBlockPos.CODEC.fieldOf("pos").forGetter(ShipBlock::relativePos),
			BlockState.CODEC.fieldOf("state").forGetter(ShipBlock::blockState)
		).apply(instance, ShipBlock::new)
	);

	/**
	 * Creates a ShipBlock that represents the helm position (origin).
	 */
	public static ShipBlock helm(BlockState state) {
		return new ShipBlock(RelativeBlockPos.ORIGIN, state);
	}

	/**
	 * Check if this block is at the origin (helm position).
	 */
	public boolean isHelm() {
		return relativePos.equals(RelativeBlockPos.ORIGIN);
	}
}
