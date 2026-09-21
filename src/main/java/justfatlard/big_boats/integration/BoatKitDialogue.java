package justfatlard.big_boats.integration;

import justfatlard.big_boats.BigBoats;
import justfatlard.village_quests.api.DialogueRegistry;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;

/**
 * A fisherman who will sell you the start of a ship.
 *
 * <p>Not a quest, because nothing here needs doing: this is a shop, and the
 * thing being sold is mostly the knowledge that a ship is possible at all.
 * Multiblock boats have no entry in any recipe book, no ore to find and nothing
 * in the world that hints at them, so the first hull anyone builds is the one
 * somebody told them they could build.
 *
 * <p>The kit is deliberately not a ship. It is planks, a helm and one bottle:
 * enough to make the idea concrete and not nearly enough to skip the building.
 *
 * <p>The christening bottle is the real gift. It is a heart of the sea and a
 * glass bottle, and a heart of the sea is otherwise a trophy that sits in a
 * chest for the length of a world because vanilla gives it exactly one use.
 */
public final class BoatKitDialogue {
	private BoatKitDialogue() {}

	private static final String OPTION_ID = "big-boats:kit";

	/** Late. A fisherman does not fit out a stranger. */
	private static final int MIN_REPUTATION = 50;

	private static final int PRICE = 24;
	private static final int PLANKS = 32;

	private static final ResourceKey<Recipe<?>> HELM_RECIPE = recipe("helm");
	private static final ResourceKey<Recipe<?>> BOTTLE_RECIPE = recipe("christening_bottle");

	private static ResourceKey<Recipe<?>> recipe(String path) {
		return ResourceKey.create(Registries.RECIPE,
			Identifier.fromNamespaceAndPath(BigBoats.MOD_ID, path));
	}

	public static void register() {
		DialogueRegistry.registerProfessionDialogue("fisherman", (villager, player, reputation) ->
			List.of(new DialogueRegistry.DialogueOption(
				OPTION_ID,
				Component.literal("Is there anything bigger than a rowboat?"),
				MIN_REPUTATION, Integer.MAX_VALUE)));

		// Asking is asking and paying is paying. The old handler charged the
		// moment the question was clicked; now the fisherman makes the offer,
		// and the emeralds only move on the button that says they will.
		DialogueRegistry.registerRichDialogueHandler(OPTION_ID, (villager, player, optionId) ->
			DialogueRegistry.Reply.of("There is. It is not free, mind - " + PRICE
					+ " emeralds and I will set you up with the makings and tell you the rest.")
				.option("*pay the " + PRICE + " emeralds*", BoatKitDialogue::sell)
				.walkAway("Not today, then."));
	}

	private static DialogueRegistry.Reply sell(net.minecraft.world.entity.npc.villager.Villager villager,
			ServerPlayer player, String optionId) {
		if (countEmeralds(player) < PRICE) {
			return DialogueRegistry.Reply.of("That is not " + PRICE + " emeralds. Come back when it is - the water is patient.")
				.walkAway("*count your pockets*");
		}

		if (!takeEmeralds(player)) {
			return DialogueRegistry.Reply.of("Something moved in your pockets while I was counting. Try me again.")
				.walkAway("*frown*");
		}

		give(player, new ItemStack(Items.OAK_PLANKS, PLANKS));
		give(player, new ItemStack(BigBoats.HELM_ITEM));
		give(player, new ItemStack(BigBoats.CHRISTENING_BOTTLE));

		// The recipes are the half worth paying for: neither is discoverable, and
		// one of them finally answers what a heart of the sea is for.
		player.awardRecipesByKey(List.of(HELM_RECIPE, BOTTLE_RECIPE));

		return DialogueRegistry.Reply.of(
			"Build the hull like a house, then put the helm where you would stand. "
			+ "Break the bottle on it and it stops being a building. "
			+ "The bottle is a heart of the sea in glass - you have been carrying one about for years, I expect.")
			.walkAway("To the water, then.");
	}

	private static int countEmeralds(ServerPlayer player) {
		int found = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (stack.is(Items.EMERALD)) found += stack.getCount();
		}
		return found;
	}

	/**
	 * @return whether the full price was actually taken. The caller used to assume it was, on the
	 *         strength of a count taken earlier in the same tick; anything that made the count and
	 *         the take see different inventories handed out a free kit.
	 */
	private static boolean takeEmeralds(ServerPlayer player) {
		int remaining = PRICE;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (remaining <= 0) return true;
			if (!stack.is(Items.EMERALD)) continue;

			int taken = Math.min(remaining, stack.getCount());
			stack.shrink(taken);
			remaining -= taken;
		}
		return remaining <= 0;
	}

	private static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
	}
}
