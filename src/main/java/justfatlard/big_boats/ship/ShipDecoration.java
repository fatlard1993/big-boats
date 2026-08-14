package justfatlard.big_boats.ship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/**
 * Snapshot of a decoration entity (item frame, painting) attached to a ship block.
 *
 * <p>Captured during undock by scanning for {@link net.minecraft.world.entity.decoration.BlockAttachedEntity}
 * instances attached to ship block positions. Stores the attachment position in local ship space,
 * facing direction, and full entity NBT. Restored during dock with rotated coordinates and
 * fresh UUIDs. Serialized via codec for crash recovery.</p>
 */
public record ShipDecoration(
	RelativeBlockPos attachmentPos,
	Direction facing,
	CompoundTag entityNbt
) {
	public static final Codec<ShipDecoration> CODEC = RecordCodecBuilder.create(instance ->
		instance.group(
			RelativeBlockPos.CODEC.fieldOf("pos").forGetter(ShipDecoration::attachmentPos),
			Direction.CODEC.fieldOf("facing").forGetter(ShipDecoration::facing),
			CompoundTag.CODEC.fieldOf("nbt").forGetter(ShipDecoration::entityNbt)
		).apply(instance, ShipDecoration::new)
	);
}
