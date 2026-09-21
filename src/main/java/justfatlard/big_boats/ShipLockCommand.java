package justfatlard.big_boats;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipLock;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/**
 * Who may sail your ship: the chest lock's three states, said to a hull instead of a lid.
 *
 * <p>The ship is the one you are sailing, or the nearest one to hand - not the one under your
 * crosshair, because a sailing ship's blocks are a drawn structure and a crosshair finds nothing
 * out there to point at. Standing on the deck is the clearest way to say "this one".
 */
public final class ShipLockCommand {
	private ShipLockCommand() {}

	/** How far from a ship you can stand and still be talking about it. */
	private static final double REACH = 16.0;

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) ->
			dispatcher.register(Commands.literal("ship-lock")
				.then(Commands.literal("lock").executes(ShipLockCommand::lock))
				.then(Commands.literal("unlock").executes(ShipLockCommand::unlock))
				.then(Commands.literal("share")
					.then(Commands.argument("player", EntityArgument.player())
						.executes(ShipLockCommand::share)))
				.then(Commands.literal("unshare")
					.then(Commands.argument("player", EntityArgument.player())
						.executes(ShipLockCommand::unshare)))
				.then(Commands.literal("list").executes(ShipLockCommand::list))));
	}

	/** The ship this player means, or null having already said why not. */
	private static MultiBlockShipEntity shipInHand(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();

		if (player.getVehicle() instanceof MultiBlockShipEntity sailing) return sailing;

		MultiBlockShipEntity closest = null;
		double closestDistance = Double.MAX_VALUE;
		ServerLevel level = (ServerLevel) player.level();
		AABB near = player.getBoundingBox().inflate(REACH);
		for (MultiBlockShipEntity ship : level.getEntities(
				EntityTypeTest.forClass(MultiBlockShipEntity.class), near, s -> !s.isRemoved())) {
			double distance = ship.distanceToSqr(player);
			if (distance < closestDistance) {
				closestDistance = distance;
				closest = ship;
			}
		}

		if (closest == null) {
			source.sendFailure(Component.translatable("big-boats.lock.no_ship"));
		}
		return closest;
	}

	/** The ship, if this player may change its lock; null having already said why not. */
	private static MultiBlockShipEntity ownedShip(CommandSourceStack source) throws CommandSyntaxException {
		MultiBlockShipEntity ship = shipInHand(source);
		if (ship == null) return null;

		ShipLock lock = ship.getLock();
		if (lock == null) {
			// Nobody owns it, so nobody can lock it. A ship christened before locks existed is
			// the only way to get here, and saying so is kinder than a flat refusal.
			source.sendFailure(Component.translatable("big-boats.lock.unowned"));
			return null;
		}
		if (!lock.permits(source.getPlayerOrException().getUUID(), ShipLock.Use.LOCK)) {
			source.sendFailure(Component.translatable("big-boats.lock.not_yours", lock.ownerName()));
			return null;
		}
		return ship;
	}

	private static int lock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		MultiBlockShipEntity ship = ownedShip(source);
		if (ship == null) return 0;

		ship.setLock(ship.getLock().published(false));
		source.sendSuccess(() -> Component.translatable("big-boats.lock.locked"), false);
		return 1;
	}

	private static int unlock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		MultiBlockShipEntity ship = ownedShip(source);
		if (ship == null) return 0;

		ship.setLock(ship.getLock().published(true));
		source.sendSuccess(() -> Component.translatable("big-boats.lock.unlocked"), false);
		return 1;
	}

	private static int share(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		MultiBlockShipEntity ship = ownedShip(source);
		if (ship == null) return 0;

		ServerPlayer guest = EntityArgument.getPlayer(context, "player");
		if (guest.getUUID().equals(ship.getLock().owner())) {
			source.sendFailure(Component.translatable("big-boats.lock.already_owner"));
			return 0;
		}

		ship.setLock(ship.getLock().with(new ShipLock.Share(guest.getUUID(), guest.getGameProfile().name())));
		source.sendSuccess(() -> Component.translatable("big-boats.lock.shared", guest.getName()), false);
		return 1;
	}

	private static int unshare(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		MultiBlockShipEntity ship = ownedShip(source);
		if (ship == null) return 0;

		ServerPlayer guest = EntityArgument.getPlayer(context, "player");
		ship.setLock(ship.getLock().without(guest.getUUID()));
		source.sendSuccess(() -> Component.translatable("big-boats.lock.unshared", guest.getName()), false);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		MultiBlockShipEntity ship = shipInHand(source);
		if (ship == null) return 0;

		ShipLock lock = ship.getLock();
		if (lock == null) {
			source.sendSuccess(() -> Component.translatable("big-boats.lock.list_unowned"), false);
			return 1;
		}
		if (!lock.permits(source.getPlayerOrException().getUUID(), ShipLock.Use.PILOT)) {
			source.sendFailure(Component.translatable("big-boats.lock.not_yours", lock.ownerName()));
			return 0;
		}

		StringBuilder crew = new StringBuilder();
		for (ShipLock.Share share : lock.shared()) {
			if (!crew.isEmpty()) crew.append(", ");
			crew.append(share.name());
		}

		String state = lock.isPublic() ? "unlocked" : "locked";
		String guests = crew.isEmpty() ? "nobody" : crew.toString();
		source.sendSuccess(() -> Component.translatable("big-boats.lock.list",
			lock.ownerName(), state, guests), false);
		return 1;
	}
}
