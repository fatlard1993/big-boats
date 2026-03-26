package justfatlard.big_boats.ship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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
	 * Convenience constructor without block entity data.
	 */
	public ShipBlock(RelativeBlockPos relativePos, BlockState blockState) {
		this(relativePos, blockState, Optional.empty());
	}

	/**
	 * Creates a ShipBlock from a world position, capturing block state and optional block entity data.
	 */
	public static ShipBlock fromWorld(World world, BlockPos pos, BlockPos origin) {
		BlockState state = world.getBlockState(pos);
		RelativeBlockPos relativePos = RelativeBlockPos.fromWorldPos(pos, origin);
		Optional<NbtCompound> blockEntityData = Optional.empty();

		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (blockEntity != null) {
			blockEntityData = Optional.of(blockEntity.createNbtWithIdentifyingData(world.getRegistryManager()));
		}

		return new ShipBlock(relativePos, state, blockEntityData);
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
