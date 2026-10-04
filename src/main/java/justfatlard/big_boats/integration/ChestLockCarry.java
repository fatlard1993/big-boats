package justfatlard.big_boats.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

/**
 * Carries a locked chest's lock across a voyage, the way {@link DyedChestPaint} carries its colour.
 *
 * <p>Chest Utils keeps locks against the position a chest stands at. A ship lifts the chest out and
 * sets it down somewhere else, so without this the lock stayed behind on empty water and the chest
 * arrived open to anyone. The lock comes off as the chest is lifted, goes to sea with it - laid on
 * the chest's place in her stretch of the world, so it holds at sea too - and goes back on wherever
 * she docks.
 *
 * <p>Guarded as the paint is: every Chest Utils type is named behind the flag.
 */
public final class ChestLockCarry {
	private ChestLockCarry() {}

	private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("chest-utils");

	/** The lock off this block, or null. Taken off: the chest is about to be somewhere else. */
	public static CompoundTag lift(ServerLevel level, BlockPos pos) {
		if (!PRESENT) return null;
		try {
			Tag carried = justfatlard.chest_utils.block.ChestLocks.get(level).strip(pos);
			return carried instanceof CompoundTag compound ? compound : null;
		} catch (LinkageError e) {
			warnOnce(e);
			return null;
		}
	}

	/**
	 * A chest set down at its new place, with the lock it carries or none. None clears whatever lock
	 * was left at the place - one a crash left in her stretch, or one an explosion left on the shore -
	 * so the chest is locked exactly as it was carried and to nobody else.
	 */
	public static void lay(ServerLevel level, BlockPos pos, CompoundTag carried) {
		if (!PRESENT) return;
		try {
			var locks = justfatlard.chest_utils.block.ChestLocks.get(level);
			if (carried == null) locks.forget(pos);
			else locks.restore(pos, carried);
		} catch (LinkageError e) {
			warnOnce(e);
		}
	}

	private static boolean warned = false;

	private static void warnOnce(LinkageError e) {
		if (warned) return;
		warned = true;
		org.slf4j.LoggerFactory.getLogger(ChestLockCarry.class)
			.warn("chest-utils has changed under us; locked chests will travel unlocked", e);
	}
}
