package justfatlard.big_boats.mixin;

import net.minecraft.world.entity.Interaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link Interaction}'s private width/height/response setters. These were public in
 * the old Yarn mappings but are private in the current Mojang mappings; the interaction
 * entity's hitbox is still fully configurable, just not from outside the class without this
 * invoker mixin. Used by {@link justfatlard.big_boats.ship.ShipCollisionEntities} to size the
 * per-ship helm interaction entity.
 */
@Mixin(Interaction.class)
public interface InteractionAccessor {
	@Invoker("setWidth")
	void invokeSetWidth(float width);

	@Invoker("setHeight")
	void invokeSetHeight(float height);

	@Invoker("setResponse")
	void invokeSetResponse(boolean response);
}
