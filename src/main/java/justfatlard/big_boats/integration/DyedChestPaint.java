package justfatlard.big_boats.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Carries a painted chest's colour across a voyage.
 *
 * <p>Chest Utils keeps paint in a per-level table keyed by block position, which is the right shape
 * for a chest that stays where it was put and the wrong one for a chest that goes to sea. A ship
 * lifts its blocks out of the world and sets them down somewhere else entirely, and nothing in that
 * told the paint table: the colour stayed behind at a coordinate with no chest at it, and the chest
 * arrived at the far end plain. Docking back in the same spot did not help either, because the
 * client is told about paint when it changes and nothing had changed.
 *
 * <p>So the ship takes the colour off the position as it lifts the chest, carries it as part of
 * that block, and puts it back on wherever the chest lands.
 *
 * <p>Guarded the way the rest of the suite's optional integrations are. The flag is a field on this
 * class, which always loads; every Chest Utils type is named inside a method body behind that flag,
 * so a server without the mod never reaches an instruction that would have to resolve one.
 */
public final class DyedChestPaint {
	private DyedChestPaint() {}

	private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("chest-utils");

	/**
	 * Take the paint off a position and say what colour it was, or null for an unpainted chest.
	 *
	 * <p>Removing it here is deliberate: the chest is about to stop existing at this position, and
	 * paint left behind would sit in the table describing a coordinate with nothing in it - and
	 * brand whatever gets built there later.
	 */
	public static String lift(ServerLevel world, BlockPos pos) {
		if (!PRESENT) return null;
		return justfatlard.chest_utils.block.DyedChests.get(world).strip(world, pos);
	}

	/**
	 * Hand the dye back as an item, for a painted chest that never made it back into the world.
	 *
	 * <p>The same trade breaking one makes: there is no dyed chest item, so a chest that comes
	 * apart gives up a plain chest and the dye that was on it.
	 */
	public static void refund(ServerLevel world, BlockPos pos, String colour) {
		if (!PRESENT || colour == null) return;
		justfatlard.chest_utils.block.DyeInteraction.giveBack(world, pos, colour);
	}

	/** Put a carried colour back onto a chest at its new home. */
	public static void lay(ServerLevel world, BlockPos pos, String colour) {
		if (!PRESENT || colour == null) return;
		justfatlard.chest_utils.block.DyedChests.get(world).paint(world, pos, colour);
	}
}
