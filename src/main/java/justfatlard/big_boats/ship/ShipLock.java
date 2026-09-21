package justfatlard.big_boats.ship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * Who may sail a ship, in the same three steps a locked chest uses.
 *
 * <p>A ship with no lock is anybody's, which is how every ship built before this existed stays
 * exactly as it was. Locking one names an owner; sharing it names guests; making it public opens
 * the helm to everyone while leaving the lock itself the owner's. The three states are the chest's
 * three states with the same words, because somebody who has locked a chest on this server has
 * already learnt this one.
 *
 * <p>The lock rides in the ship's own save data rather than in a table keyed by position, because
 * a ship has no position to be keyed by - it is the one thing here that moves.
 */
public record ShipLock(UUID owner, String ownerName, List<Share> shared, boolean isPublic) {

	public static final Codec<ShipLock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		UUIDUtil.CODEC.fieldOf("owner").forGetter(ShipLock::owner),
		Codec.STRING.fieldOf("owner_name").forGetter(ShipLock::ownerName),
		// Optional with empty defaults, so a ship locked before sharing or publishing existed
		// still loads. A ship locked yesterday must not become unreadable for gaining a feature.
		Share.CODEC.listOf().optionalFieldOf("shared", List.of()).forGetter(ShipLock::shared),
		Codec.BOOL.optionalFieldOf("public", false).forGetter(ShipLock::isPublic)
	).apply(instance, ShipLock::new));

	/** Somebody the owner has let aboard, kept by name as well so a list can be read. */
	public record Share(UUID id, String name) {
		static final Codec<Share> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(Share::id),
			Codec.STRING.fieldOf("name").forGetter(Share::name)
		).apply(instance, Share::new));
	}

	/** What somebody wants with a ship, in the order the lock lets go of them. */
	public enum Use {
		/** Take the helm. Anybody's on a public ship; the owner's and their guests' otherwise. */
		PILOT,
		/** Break its helm, or let it absorb what it touches. The owner's and their guests'. */
		ALTER,
		/** Lock it, unlock it, share it, make it public. The owner's alone. */
		LOCK
	}

	public static ShipLock of(UUID owner, String ownerName) {
		return new ShipLock(owner, ownerName, List.of(), false);
	}

	/** Whether this player is the owner or somebody the owner let aboard. */
	public boolean allows(UUID player) {
		if (this.owner.equals(player)) return true;
		for (Share share : this.shared) {
			if (share.id().equals(player)) return true;
		}
		return false;
	}

	public boolean permits(UUID player, Use use) {
		return switch (use) {
			case PILOT -> this.isPublic || allows(player);
			case ALTER -> allows(player);
			case LOCK -> this.owner.equals(player);
		};
	}

	public ShipLock with(Share share) {
		List<Share> next = new ArrayList<>(this.shared);
		next.removeIf(existing -> existing.id().equals(share.id()));
		next.add(share);
		return new ShipLock(this.owner, this.ownerName, List.copyOf(next), this.isPublic);
	}

	public ShipLock without(UUID player) {
		List<Share> next = new ArrayList<>(this.shared);
		next.removeIf(existing -> existing.id().equals(player));
		return new ShipLock(this.owner, this.ownerName, List.copyOf(next), this.isPublic);
	}

	public ShipLock published(boolean isPublic) {
		return new ShipLock(this.owner, this.ownerName, this.shared, isPublic);
	}
}
