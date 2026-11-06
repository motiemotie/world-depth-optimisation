package motie.worlddepthoptimisation.client;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.OptionalLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Unattended benchmarks, used by bench.ps1. Started with -Dworlddepthoptimisation.autobench=&lt;world preset&gt;, the game creates a fresh
 * world of that preset, runs the benchmark, and closes.
 */
final class AutoBench {
	private static final String PRESET = System.getProperty("worlddepthoptimisation.autobench");
	private static final String LABEL = System.getProperty("worlddepthoptimisation.label", PRESET);
	private static final long SEED = 12345L;
	/** A world that will not load must not hang the run. */
	private static final long GIVE_UP_MS = 15L * 60L * 1000L;

	private enum Step { WAITING_FOR_TITLE, LOADING_WORLD, RUNNING, DONE }

	private static Step step = Step.WAITING_FOR_TITLE;
	private static int ticksSinceEnd;

	private AutoBench() {}

	static void clientTick(Minecraft client) {
		if (PRESET == null || step == Step.DONE) {
			return;
		}
		switch (step) {
			case WAITING_FOR_TITLE -> {
				if (client.isGameLoadFinished() && client.gui.screen() instanceof TitleScreen) {
					watchdog();
					createWorld(client);
					step = Step.LOADING_WORLD;
				}
			}
			case LOADING_WORLD -> {
				if (client.level != null && client.player != null && client.gui.screen() == null && client.hasSingleplayerServer()) {
					BenchRun.start(client, LABEL);
					step = Step.RUNNING;
				}
			}
			case RUNNING -> {
				// A moment before closing, for the screenshot to reach the disk.
				if (!BenchRun.running() && ++ticksSinceEnd > 60) {
					step = Step.DONE;
					client.stop();
				}
			}
			default -> {
			}
		}
	}

	private static void createWorld(Minecraft client) {
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		LevelSettings settings = new LevelSettings("bench-" + LABEL + "-" + stamp, GameType.CREATIVE,
				new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
		WorldOptions options = WorldOptions.defaultWithRandomSeed().withSeed(OptionalLong.of(SEED));
		ResourceKey<WorldPreset> key = ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse(PRESET));
		client.createWorldOpenFlows().createFreshLevel(settings.levelName(), settings, options,
				registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(key).value().createWorldDimensions(), client.gui.screen());
	}

	private static void watchdog() {
		Thread thread = new Thread(() -> {
			try {
				Thread.sleep(GIVE_UP_MS);
			} catch (InterruptedException e) {
				return;
			}
			System.err.println("[worlddepthoptimisation] Benchmark gave up after 15 minutes.");
			Runtime.getRuntime().halt(3);
		}, "worlddepthoptimisation-watchdog");
		thread.setDaemon(true);
		thread.start();
	}
}
