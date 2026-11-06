package motie.worlddepthoptimisation.client;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import motie.worlddepthoptimisation.ChunkStageTimer;
import motie.worlddepthoptimisation.PacketMeter;

/**
 * One benchmark, the same in any world: settle where you stand, sit still, fly in a straight line through new chunks at a fixed
 * speed, sit still again, save, fly back over the saved chunks so they load from disk, then run scripted block edits. Each phase
 * is recorded on its own, into benchmarks/ in the game folder.
 */
public final class BenchRun {
	private static final String[] PHASES = {"load", "idle", "fly", "settle", "revisit", "edit"};
	/** The edit phase lasts until its edits are done. */
	private static final int[] PHASE_SECONDS = {20, 30, 60, 20, 30, 0};
	private static final int FLY_PHASE = 2;
	private static final int SETTLE_PHASE = 3;
	private static final int REVISIT_PHASE = 4;
	private static final int EDIT_PHASE = 5;
	private static final int RENDER_DISTANCE = 16;
	private static final int SIMULATION_DISTANCE = 12;
	private static final double BLOCKS_PER_TICK = 32.0 / 20.0;
	private static final int FLY_ABOVE_SURFACE = 30;
	private static final float YAW = -90.0F;
	private static final float PITCH = 20.0F;
	private static final int CHECK_CHUNK_RADIUS = 2;
	private static final String FULL_STAGE = "minecraft:full";

	/** Also checks the chunks along the whole route, once a second. Off for real figures, because it takes time on the server thread. */
	private static final boolean CHECK_ROUTE = Boolean.getBoolean("worlddepthoptimisation.checkroute");

	private static volatile BenchRun current;
	private static long tickStart;

	private final Minecraft client;
	private final String label;
	private final List<String> rows = new ArrayList<>();
	private final boolean oldVsync;
	private final int oldFramerate;
	private final boolean oldPause;
	private final InactivityFpsLimit oldInactivity;
	private final int oldRenderDistance;
	private final int oldSimulationDistance;
	private boolean everUnfocused;

	private final List<String> networkRows = new ArrayList<>();
	private final UpdateTests updates = new UpdateTests();
	private volatile boolean saveRequested;
	private double saveMs;
	private String diskText = "0,0,0,0";
	private long totalChunks;
	private long diskLoads;
	private double diskLoadMs;
	private double startX;

	private volatile Phase phase;
	private volatile int phaseIndex;
	private volatile boolean flying;
	private volatile boolean checkRequested;
	private volatile String worldCheck = "not checked";
	private boolean placed;
	private int steerTicks;
	private long routeColumns;
	private long routeRaised;
	private long routeNotBedrock;
	private int routeHighestTop = Integer.MIN_VALUE;
	private int routeHighestBottom = Integer.MIN_VALUE;
	private int routeDeepest;
	private int routeFloorStepMax;
	private int routeSurfaceStepMax;
	private long routeFloorStepSum;
	private long routeSurfaceStepSum;
	private long routeSteps;
	private double x;
	private double y;
	private double z;

	private BenchRun(Minecraft client, String label) {
		this.client = client;
		this.label = label;
		oldVsync = client.options.enableVsync().get();
		oldFramerate = client.options.framerateLimit().get();
		oldPause = client.options.pauseOnLostFocus;
		oldInactivity = client.options.inactivityFpsLimit().get();
		oldRenderDistance = client.options.renderDistance().get();
		oldSimulationDistance = client.options.simulationDistance().get();
	}

	static boolean running() {
		return current != null;
	}

	static void start(Minecraft client, String label) {
		if (current != null || !client.hasSingleplayerServer()) {
			return;
		}
		BenchRun run = new BenchRun(client, label);
		// Uncapped, or every world would show the same FPS. Not saved: ending the run puts these back.
		client.options.enableVsync().set(false);
		client.options.framerateLimit().set(260);
		client.options.pauseOnLostFocus = false;
		// Otherwise a minute without input drops the game to 30 FPS.
		client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		// The same in every run, whatever the player last chose, so results compare.
		client.options.renderDistance().set(RENDER_DISTANCE);
		client.options.simulationDistance().set(SIMULATION_DISTANCE);
		run.beginPhase(0);
		PacketMeter.start(client.getSingleplayerServer().registryAccess());
		ChunkStageTimer.listening = true;
		current = run;
		say(client, "Benchmark started: " + label + ", about " + Arrays.stream(PHASE_SECONDS).sum() + " seconds. Leave the game alone.");
	}

	static void cancel() {
		BenchRun run = current;
		if (run != null) {
			run.finish(false);
		}
	}

	private void finish(boolean save) {
		current = null;
		ChunkStageTimer.listening = false;
		PacketMeter.listening = false;
		client.options.enableVsync().set(oldVsync);
		client.options.framerateLimit().set(oldFramerate);
		client.options.pauseOnLostFocus = oldPause;
		client.options.inactivityFpsLimit().set(oldInactivity);
		if (!save) {
			client.options.renderDistance().set(oldRenderDistance);
			client.options.simulationDistance().set(oldSimulationDistance);
		} else {
			// After the recording stops, so saving it does not count as a slow frame.
			Screenshot.grab(client, false);
			String where = write();
			client.options.renderDistance().set(oldRenderDistance);
			client.options.simulationDistance().set(oldSimulationDistance);
			say(client, "Benchmark finished: " + where);
		}
	}

	public static void frame(long nanos) {
		BenchRun run = current;
		if (run != null) {
			run.phase.frames.add(nanos);
		}
	}

	static void clientTick(Minecraft client) {
		BenchRun run = current;
		if (run == null) {
			return;
		}
		run.everUnfocused |= !client.isWindowActive();
		Phase phase = run.phase;
		long now = System.nanoTime();
		if (now - phase.lastSample > 1_000_000_000L) {
			phase.lastSample = now;
			phase.sampleMemory();
		}
		boolean over = run.phaseIndex == EDIT_PHASE ? run.updates.done() : now - phase.start >= PHASE_SECONDS[run.phaseIndex] * 1_000_000_000L;
		if (over) {
			run.rows.add(phase.finish());
			run.totalChunks += phase.chunksGenerated;
			if (run.phaseIndex == REVISIT_PHASE) {
				run.diskLoads = phase.diskLoads;
				run.diskLoadMs = phase.diskLoadMs;
			}
			if (run.phaseIndex != EDIT_PHASE) {
				run.recordNetwork(phase.name, (now - phase.start) / 1e9);
			}
			if (run.phaseIndex + 1 == PHASES.length) {
				run.finish(true);
			} else {
				run.beginPhase(run.phaseIndex + 1);
			}
		}
	}

	private void beginPhase(int index) {
		ChunkStageTimer.reset();
		PacketMeter.reset();
		phase = new Phase(PHASES[index]);
		phaseIndex = index;
		flying = index == FLY_PHASE || index == REVISIT_PHASE;
		checkRequested = index == SETTLE_PHASE;
		saveRequested = index == REVISIT_PHASE;
	}

	private void recordNetwork(String name, double seconds) {
		PacketMeter.snapshot().forEach((type, row) -> networkRows.add(String.format(Locale.ROOT, "%s,%s,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.3f",
				name, type, row.count(), row.sized(), row.rawAvg() / 1024.0, row.compressedAvg() / 1024.0, row.sectionsAvg() / 1024.0, row.lightAvg() / 1024.0,
				row.compressedTotal() / (1024.0 * 1024.0), row.compressedTotal() / (1024.0 * 1024.0) / seconds)));
	}

	/** Saves everything, and measures how long that took and what is on disk. Then returns to the start of the route. */
	private void saveAndReturn(MinecraftServer server) {
		long start = System.nanoTime();
		server.saveEverything(true, true, true);
		saveMs = (System.nanoTime() - start) / 1e6;
		long[] sizes = new long[3];
		try (java.util.stream.Stream<Path> files = Files.walk(server.getWorldPath(LevelResource.ROOT))) {
			files.filter(f -> f.toString().endsWith(".mca")).forEach(f -> {
				String folder = f.getParent().getFileName().toString();
				int at = folder.equals("region") ? 0 : folder.equals("entities") ? 1 : 2;
				try {
					sizes[at] += Files.size(f);
				} catch (IOException e) {
					// A file that cannot be read adds nothing.
				}
			});
		} catch (IOException e) {
			diskText = "unreadable,0,0,0";
			x = startX;
			return;
		}
		diskText = String.format(Locale.ROOT, "%.1f,%.1f,%.1f,%.1f", sizes[0] / (1024.0 * 1024.0), sizes[1] / (1024.0 * 1024.0), sizes[2] / (1024.0 * 1024.0), totalChunks == 0L ? 0.0 : sizes[0] / 1024.0 / totalChunks);
		x = startX;
	}

	static void serverTickStart() {
		tickStart = System.nanoTime();
	}

	static void serverTickEnd(MinecraftServer server) {
		BenchRun run = current;
		if (run == null) {
			return;
		}
		long tick = System.nanoTime() - tickStart;
		run.phase.tick(tick);
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (!players.isEmpty()) {
			run.steer(players.getFirst(), tick);
		}
	}

	private void steer(ServerPlayer player, long lastTickNanos) {
		if (!placed) {
			placed = true;
			player.setGameMode(GameType.SPECTATOR);
			x = player.getX();
			startX = x;
			z = player.getZ();
			y = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING, player.getBlockX(), player.getBlockZ()) + FLY_ABOVE_SURFACE;
		}
		if (saveRequested) {
			saveRequested = false;
			saveAndReturn(player.level().getServer());
		}
		if (flying) {
			x += BLOCKS_PER_TICK;
		}
		if (phaseIndex == EDIT_PHASE) {
			updates.step(player.level(), (int) Math.floor(x), (int) Math.floor(z), lastTickNanos);
		}
		player.connection.teleport(x, y, z, YAW, PITCH);
		if (CHECK_ROUTE && ++steerTicks % 20 == 0) {
			checkWorld(player);
		}
		if (checkRequested) {
			checkRequested = false;
			worldCheck = checkWorld(player);
		}
	}

	/** What the chunks around the player are really made of. */
	private String checkWorld(ServerPlayer player) {
		ServerLevel level = player.level();
		int cx = player.getBlockX() >> 4;
		int cz = player.getBlockZ() >> 4;
		int columns = 0;
		int topLow = Integer.MAX_VALUE;
		int topHigh = Integer.MIN_VALUE;
		int bottomLow = Integer.MAX_VALUE;
		int bottomHigh = Integer.MIN_VALUE;
		int deepest = 0;
		int notBedrock = 0;
		int raised = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dz = -CHECK_CHUNK_RADIUS; dz <= CHECK_CHUNK_RADIUS; dz++) {
			for (int dx = -CHECK_CHUNK_RADIUS; dx <= CHECK_CHUNK_RADIUS; dx++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
				if (chunk == null) {
					continue;
				}
				for (int z = 0; z < 16; z++) {
					int lastBottom = 0;
					int lastSolid = 0;
					for (int x = 0; x < 16; x++) {
						int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
						int solid = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
						int bottom = lowestBlock(chunk, x, z);
						if (x > 0) {
							// How sharply the floor and the ground each change from one column to the next.
							routeFloorStepMax = Math.max(routeFloorStepMax, Math.abs(bottom - lastBottom));
							routeSurfaceStepMax = Math.max(routeSurfaceStepMax, Math.abs(solid - lastSolid));
							routeFloorStepSum += Math.abs(bottom - lastBottom);
							routeSurfaceStepSum += Math.abs(solid - lastSolid);
							routeSteps++;
						}
						lastBottom = bottom;
						lastSolid = solid;
						BlockState block = chunk.getBlockState(pos.set(chunk.getPos().getMinBlockX() + x, bottom, chunk.getPos().getMinBlockZ() + z));
						columns++;
						topLow = Math.min(topLow, top);
						topHigh = Math.max(topHigh, top);
						bottomLow = Math.min(bottomLow, bottom);
						bottomHigh = Math.max(bottomHigh, bottom);
						deepest = Math.max(deepest, solid - bottom);
						notBedrock += block.is(Blocks.BEDROCK) ? 0 : 1;
						raised += bottom > level.getMinY() ? 1 : 0;
					}
				}
			}
		}
		routeColumns += columns;
		routeRaised += raised;
		routeNotBedrock += notBedrock;
		routeHighestTop = Math.max(routeHighestTop, topHigh);
		routeHighestBottom = Math.max(routeHighestBottom, bottomHigh);
		routeDeepest = Math.max(routeDeepest, deepest);
		return String.format(Locale.ROOT, "%d columns: top y %d to %d, lowest block y %d to %d, most blocks from solid surface to bottom %d, raised floor %d, bottom not bedrock %d",
				columns, topLow, topHigh, bottomLow, bottomHigh, deepest, raised, notBedrock);
	}

	private static int lowestBlock(LevelChunk chunk, int x, int z) {
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			if (sections[i].hasOnlyAir()) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				if (!sections[i].getBlockState(x, y, z).isAir()) {
					return chunk.getMinY() + i * 16 + y;
				}
			}
		}
		return chunk.getMinY();
	}

	private String write() {
		String header = "label,time,phase,seconds,fps_avg,fps_1pct_low,frame_p50_ms,frame_p99_ms,frames_over_50ms,frames_over_100ms,frames_over_250ms,worst_frame_ms,"
				+ "tick_avg_ms,ticks_over_50ms,worst_tick_ms,gc_count,gc_ms,heap_avg_mb,heap_peak_mb,heap_live_mb,direct_mb,committed_mb,"
				+ "chunks_generated,ms_per_chunk,stages";
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		Path dir = client.gameDirectory.toPath().resolve("benchmarks");
		try {
			Files.createDirectories(dir);
			Path csv = dir.resolve("results.csv");
			if (!Files.exists(csv)) {
				Files.writeString(csv, header + System.lineSeparator());
			}
			StringBuilder out = new StringBuilder();
			for (String row : rows) {
				out.append(label).append(',').append(stamp).append(',').append(row).append(System.lineSeparator());
			}
			Files.writeString(csv, out.toString(), StandardOpenOption.APPEND);
			appendRows(dir.resolve("network.csv"), "label,phase,type,packets,packets_sized,raw_kb_avg,compressed_kb_avg,block_data_kb_avg,light_data_kb_avg,compressed_mb_total,compressed_mb_per_s", networkRows);
			appendRows(dir.resolve("updates.csv"), "label,test,blocks,edit_ms,light_catchup_ms,tick_avg_ms,tick_worst_ms,ticks_over_50ms,light_packets,light_kb,section_packets,section_kb,block_packets,block_kb", updates.rows());
			appendRows(dir.resolve("disk.csv"), "label,save_ms,region_mb,entities_mb,poi_mb,region_kb_per_chunk,chunks_generated,chunks_loaded_from_disk,disk_load_ms_avg",
					List.of(String.format(Locale.ROOT, "%.0f,%s,%d,%d,%.2f", saveMs, diskText, totalChunks, diskLoads, diskLoadMs)));
			Files.writeString(dir.resolve("checks.txt"), label + " " + stamp + " renderDistance=" + client.options.renderDistance().get() + " simulationDistance="
					+ client.options.simulationDistance().get() + " windowLostFocus=" + everUnfocused + " | " + worldCheck + (CHECK_ROUTE ? String.format(Locale.ROOT, " | route: %d columns, highest top y %d, highest bottom y %d, raised floor %d, most blocks from solid surface to bottom %d, bottom not bedrock %d, floor step avg %.3f max %d, surface step avg %.3f max %d",
							routeColumns, routeHighestTop, routeHighestBottom, routeRaised, routeDeepest, routeNotBedrock, routeFloorStepSum / (double) Math.max(1L, routeSteps), routeFloorStepMax,
							routeSurfaceStepSum / (double) Math.max(1L, routeSteps), routeSurfaceStepMax) : "") + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			return csv.toString();
		} catch (IOException e) {
			return "could not write results: " + e.getMessage();
		}
	}

	private void appendRows(Path file, String header, List<String> lines) throws IOException {
		if (!Files.exists(file)) {
			Files.writeString(file, header + System.lineSeparator());
		}
		StringBuilder out = new StringBuilder();
		for (String line : lines) {
			out.append(label).append(',').append(line).append(System.lineSeparator());
		}
		Files.writeString(file, out.toString(), StandardOpenOption.APPEND);
	}

	private static void say(Minecraft client, String message) {
		if (client.player != null) {
			client.player.sendSystemMessage(Component.literal(message));
		}
		System.out.println("[worlddepthoptimisation] " + message);
	}

	/** Everything recorded in one phase. */
	private static final class Phase {
		final String name;
		final long start = System.nanoTime();
		final long gcCountStart = gcCount();
		final long gcMsStart = gcMs();
		final LongArrayList frames = new LongArrayList();
		final LongArrayList ticks = new LongArrayList();
		long lastSample = start;
		long chunksGenerated;
		long diskLoads;
		double diskLoadMs;
		long heapSum;
		long heapPeak;
		int heapSamples;

		Phase(String name) {
			this.name = name;
		}

		synchronized void tick(long nanos) {
			ticks.add(nanos);
		}

		void sampleMemory() {
			Runtime runtime = Runtime.getRuntime();
			long used = runtime.totalMemory() - runtime.freeMemory();
			heapSum += used;
			heapPeak = Math.max(heapPeak, used);
			heapSamples++;
		}

		synchronized String finish() {
			double seconds = (System.nanoTime() - start) / 1e9;
			long[] sorted = frames.toLongArray();
			Arrays.sort(sorted);
			int n = Math.max(1, sorted.length);
			int slowest = Math.max(1, n / 100);
			long slowSum = 0L;
			for (int i = sorted.length - slowest; i >= 0 && i < sorted.length; i++) {
				slowSum += sorted[i];
			}
			double low = slowSum == 0L ? 0.0 : slowest / (slowSum / 1e9);
			long tickSum = 0L;
			long tickWorst = 0L;
			int tickSlow = 0;
			for (int i = 0; i < ticks.size(); i++) {
				long t = ticks.getLong(i);
				tickSum += t;
				tickWorst = Math.max(tickWorst, t);
				tickSlow += t > 50_000_000L ? 1 : 0;
			}
			Map<String, ChunkStageTimer.Stage> stages = ChunkStageTimer.snapshot();
			double perChunk = 0.0;
			StringBuilder stageText = new StringBuilder();
			for (Map.Entry<String, ChunkStageTimer.Stage> e : stages.entrySet()) {
				if (e.getKey().equals(ChunkStageTimer.DISK_LOAD)) {
					diskLoads = e.getValue().count();
					diskLoadMs = e.getValue().meanMs();
				} else {
					perChunk += e.getValue().meanMs();
				}
				stageText.append(stageText.isEmpty() ? "" : " ").append(e.getKey().replace("minecraft:", "")).append('=').append(String.format(Locale.ROOT, "%.2f", e.getValue().meanMs()));
			}
			ChunkStageTimer.Stage full = stages.get(FULL_STAGE);
			chunksGenerated = full == null ? 0L : full.count();
			return String.format(Locale.ROOT, "%s,%.1f,%.1f,%.1f,%.2f,%.2f,%d,%d,%d,%.1f,%.2f,%d,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%s",
					name, seconds, frames.size() / seconds, low, percentile(sorted, 0.50), percentile(sorted, 0.99), over(sorted, 50), over(sorted, 100), over(sorted, 250),
					sorted.length == 0 ? 0.0 : sorted[sorted.length - 1] / 1e6, ticks.isEmpty() ? 0.0 : tickSum / 1e6 / ticks.size(), tickSlow, tickWorst / 1e6,
					gcCount() - gcCountStart, gcMs() - gcMsStart, heapSamples == 0 ? 0L : mb(heapSum / heapSamples), mb(heapPeak), mb(liveHeap()), mb(direct()), mb(committed()),
					full == null ? 0L : full.count(), perChunk, stageText);
		}

		private static double percentile(long[] sorted, double share) {
			return sorted.length == 0 ? 0.0 : sorted[Math.min(sorted.length - 1, (int) (sorted.length * share))] / 1e6;
		}

		private static int over(long[] sorted, int ms) {
			int count = 0;
			for (int i = sorted.length - 1; i >= 0 && sorted[i] > ms * 1_000_000L; i--) {
				count++;
			}
			return count;
		}

		private static long mb(long bytes) {
			return bytes / (1024L * 1024L);
		}

		private static long gcCount() {
			return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
		}

		private static long gcMs() {
			return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
		}

		/** Heap still in use right after the last collection: the world's real size, without garbage waiting to be collected. */
		private static long liveHeap() {
			return ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP && p.getCollectionUsage() != null)
					.mapToLong((MemoryPoolMXBean p) -> p.getCollectionUsage().getUsed()).sum();
		}

		private static long direct() {
			return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream().filter(p -> p.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
		}

		private static long committed() {
			return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os ? os.getCommittedVirtualMemorySize() : 0L;
		}
	}
}
