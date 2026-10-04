package justfatlard.big_boats.ship.sea;

import justfatlard.big_boats.ship.MultiBlockShipEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where a ship's blocks are while she sails: a stretch of world above anything that can be built,
 * one per ship, in which each of her blocks stands where it stands on her deck.
 *
 * <p>At sea a ship's blocks are drawn, not placed, so there is nothing in the world for a chest to
 * be, a furnace to burn in or a door to swing on. Rather than teach each block what it does at sea,
 * the ship's blocks are given a place in the world the game will believe: reads, writes and block
 * entities up here are answered from the ship ({@code mixin/AtSeaLevelMixin}), sounds made up here
 * are heard at the ship's real position, and reach is measured to where the block really is. Then
 * the game's own chests, furnaces, doors, anvils and enchanting tables run unchanged.
 *
 * <p>Nothing up here is ever a real block: it is far above every dimension's build height, so a
 * write that slipped past this would be refused by the game, and nothing here loads a chunk.
 */
public final class AtSea {
	private AtSea() {}

	/** The height a ship's helm is put at, up here. Her hull reaches some way either side. */
	public static final int HELM_Y = 1856;
	/** Anything lower is the world: one comparison, on the hottest paths in the game. */
	public static final int FLOOR = 1600;
	/** Each ship's stretch, along x; wider than any ship can be built. */
	private static final int SPACING = 2048;
	private static final int ORIGIN = 30_000_000;

	private static final List<Hold> holds = new CopyOnWriteArrayList<>();
	private static final Map<Integer, Hold> bySlot = new ConcurrentHashMap<>();

	public static boolean above(BlockPos pos) {
		return pos.getY() >= FLOOR;
	}

	public static boolean above(double y) {
		return y >= FLOOR;
	}

	/**
	 * Whether a position is out where the stretches are, held or not: high, and past the edge of
	 * every world. A datapack dimension may build above {@link #FLOOR}; it never reaches here.
	 */
	public static boolean inStretches(BlockPos pos) {
		return pos.getY() >= FLOOR && pos.getX() >= ORIGIN - SPACING / 2;
	}

	/** The hold this position is in, if it is in one. */
	public static @Nullable Hold at(Level level, BlockPos pos) {
		if (pos.getY() < FLOOR || !(level instanceof ServerLevel)) return null;
		int slot = Math.floorDiv(pos.getX() - ORIGIN + SPACING / 2, SPACING);
		Hold hold = bySlot.get(slot);
		return hold != null && hold.level() == level ? hold : null;
	}

	/** A ship setting sail takes a stretch of her own. */
	public static Hold open(MultiBlockShipEntity ship, ServerLevel level) {
		// Only where the world stops well short of the stretch; a dimension built that high keeps
		// its ships as they were, opened by nothing.
		if (level.getMaxY() >= FLOOR) return null;
		int slot = 0;
		while (bySlot.containsKey(slot)) slot++;
		Hold hold = new Hold(ship, level, ORIGIN + slot * SPACING, ORIGIN);
		bySlot.put(slot, hold);
		holds.add(hold);
		return hold;
	}

	/** A ship coming in hands back her stretch, with everything in it written back to her blocks. */
	public static void close(Hold hold) {
		if (hold == null) return;
		try {
			hold.release();
		} finally {
			holds.remove(hold);
			bySlot.values().remove(hold);
		}
	}

	/**
	 * The server is stopping: everything in use at sea is written back to the ships before they are
	 * saved, so what a player took in the last moments is not still in the chest when she loads.
	 */
	public static void closeAll() {
		for (Hold hold : List.copyOf(holds)) {
			try {
				close(hold);
			} catch (RuntimeException e) {
				org.slf4j.LoggerFactory.getLogger(AtSea.class).error("Failed writing a ship's hold back as the server stopped", e);
			}
			hold.ship().stretchClosed(hold);
		}
		holds.clear();
		bySlot.clear();
	}

	/** A write by the game's own code to a block up here: answered by the ship, or refused. */
	public static boolean setBlock(Level level, BlockPos pos, BlockState state, int flags, int limit) {
		Hold hold = at(level, pos);
		return hold != null && hold.setBlock(pos, state, flags, limit);
	}

	public static @Nullable BlockState getBlockState(Level level, BlockPos pos) {
		Hold hold = at(level, pos);
		return hold == null ? null : hold.stateAt(pos);
	}

	public static @Nullable BlockEntity getBlockEntity(Level level, BlockPos pos) {
		Hold hold = at(level, pos);
		return hold == null ? null : hold.blockEntityAt(pos);
	}

	/** A block entity up here changed: the ship writes it back to her blocks at the end of the tick. */
	public static void changed(Level level, BlockPos pos) {
		Hold hold = at(level, pos);
		if (hold != null) hold.markDirty(pos);
	}

	/** Whether a block up here is within this player's reach where it really is, on the ship. */
	public static boolean inReach(Player player, BlockPos pos, double buffer) {
		Hold hold = at(player.level(), pos);
		return hold != null && hold.inReach(player, pos, buffer);
	}

	/** Where a point up here really is: on the ship, as she lies now. Null if it is in no hold. */
	public static @Nullable Vec3 toWorld(Level level, double x, double y, double z) {
		Hold hold = at(level, BlockPos.containing(x, y, z));
		return hold == null ? null : hold.toWorld(x, y, z);
	}
}
