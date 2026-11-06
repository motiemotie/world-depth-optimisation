package motie.worlddepthoptimisation;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class WorldDepthOptimisationMod implements ModInitializer {
	@Override
	public void onInitialize() {
		// Read now rather than at the first chunk, so the config file exists from the first launch.
		int depth = Basement.MAX_DEPTH;
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("worlddepth")
						.executes(ctx -> report(ctx.getSource()))
						.then(Commands.literal("on").executes(ctx -> set(ctx.getSource(), true)))
						.then(Commands.literal("off").executes(ctx -> set(ctx.getSource(), false)))));
	}

	private static int set(CommandSourceStack source, boolean on) {
		FloatingFloor.enabled = on;
		return report(source);
	}

	private static int report(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal("World depth optimisation is " + (FloatingFloor.enabled ? "on" : "off") + " for new chunks, at most "
				+ Basement.MAX_DEPTH + " blocks below the surface."), false);
		return 1;
	}
}
