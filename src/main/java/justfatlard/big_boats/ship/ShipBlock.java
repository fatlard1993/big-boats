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
public record ShipBlock(RelativeBlockPos relativePos, BlockState blockState, Optional<CompoundTag> blockEntityData) {
	public static final Codec<ShipBlock> CODEC = RecordCodecBuilder.create(instance ->
		instance.group(
			RelativeBlockPos.CODEC.fieldOf("pos").forGetter(ShipBlock::relativePos),
			BlockState.CODEC.fieldOf("state").forGetter(ShipBlock::blockState),
			CompoundTag.CODEC.optionalFieldOf("nbt").forGetter(ShipBlock::blockEntityData)
		).apply(instance, ShipBlock::new)
	);

	public ShipBlock(RelativeBlockPos relativePos, BlockState blockState) {
		this(relativePos, blockState, Optional.empty());
	}

	/**
	 * Creates a ShipBlock from a world position, capturing block state and optional block entity data.
	 */
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
		return new ShipBlock(relativePos, blockState, Optional.ofNullable(nbt));
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
