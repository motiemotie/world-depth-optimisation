package motie.worlddepthoptimisation.client;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class WorldDepthOptimisationClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			AutoBench.clientTick(client);
			BenchRun.clientTick(client);
		});
		ServerTickEvents.START_SERVER_TICK.register(server -> BenchRun.serverTickStart());
		ServerTickEvents.END_SERVER_TICK.register(BenchRun::serverTickEnd);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("fbench")
						.then(Commands.literal("run")
								.executes(ctx -> run(ctx.getSource().getServer().getWorldData().getLevelName()))
								.then(Commands.argument("label", StringArgumentType.word())
										.executes(ctx -> run(StringArgumentType.getString(ctx, "label")))))
						.then(Commands.literal("stop").executes(ctx -> {
							Minecraft.getInstance().execute(BenchRun::cancel);
							ctx.getSource().sendSuccess(() -> Component.literal("Benchmark stopped: nothing was saved."), false);
							return 1;
						}))));
	}

	private static int run(String label) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> BenchRun.start(client, label));
		return 1;
	}
}
