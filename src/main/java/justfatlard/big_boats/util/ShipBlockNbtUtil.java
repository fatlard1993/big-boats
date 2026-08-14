package justfatlard.big_boats.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

/**
 * Small helpers around {@link TagValueOutput}/{@link TagValueInput} for saving/loading
 * arbitrary NBT (block entity snapshots, decoration entity snapshots) outside the normal
 * entity/block-entity save pipeline. Problems are discarded, since these are best-effort
 * snapshot/restore paths.
 */
public final class ShipBlockNbtUtil {
	public static TagValueOutput newOutput(Level world) {
		return TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registryAccess());
	}

	public static TagValueOutput newOutputWithoutContext() {
		return TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
	}

	public static ValueInput newInput(Level world, CompoundTag nbt) {
		return TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), nbt);
	}

	private ShipBlockNbtUtil() {}
}
