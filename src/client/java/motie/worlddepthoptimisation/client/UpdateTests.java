package motie.worlddepthoptimisation.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import motie.worlddepthoptimisation.PacketMeter;

/**
 * Scripted edits near the player, the same in every world, one after another on the server thread. Each records how long the edit
 * took, how long lighting took to catch up, how the server coped in the seconds after, and how much was sent to the player.
 */
final class UpdateTests {
	private static final String[] NAMES = {"single_blocks", "shaft_to_bottom", "pit_32x32x8", "roof_64x64", "remove_roof"};
	/** Time for flowing water and late packets to show. */
	private static final int MIN_TICKS = 100;
	private static final int MAX_TICKS = 600;
	private static final int ROOF_ABOVE_SURFACE = 10;

	private final List<String> rows = new ArrayList<>();
	private int index;
	private boolean active;
	private int ticks;
	private int blocks;
	private double editMs;
	private long editEnd;
	private volatile long lightDone;
	private long tickSum;
	private long tickWorst;
	private int ticksSlow;
	private int roofY;

	boolean done() {
		return index >= NAMES.length;
	}

	List<String> rows() {
		return rows;
	}

	void step(ServerLevel level, int centerX, int centerZ, long lastTickNanos) {
		if (done()) {
			return;
		}
		if (!active) {
			begin(level, centerX, centerZ);
			return;
		}
		ticks++;
		tickSum += lastTickNanos;
		tickWorst = Math.max(tickWorst, lastTickNanos);
		ticksSlow += lastTickNanos > 50_000_000L ? 1 : 0;
		boolean settled = ticks >= MIN_TICKS && lightDone != 0L && PacketMeter.idle();
		if (settled || ticks >= MAX_TICKS) {
			end();
		}
	}

	private void begin(ServerLevel level, int centerX, int centerZ) {
		PacketMeter.reset();
		long start = System.nanoTime();
		blocks = edit(level, centerX, centerZ);
		editEnd = System.nanoTime();
		editMs = (editEnd - start) / 1e6;
		lightDone = 0L;
		ticks = 0;
		tickSum = 0L;
		tickWorst = 0L;
		ticksSlow = 0;
		active = true;
		// Queued behind the work this edit caused, so it completes once lighting has caught up.
		level.getChunkSource().getLightEngine().waitForPendingTasks(centerX >> 4, centerZ >> 4).thenRun(() -> lightDone = System.nanoTime());
	}

	private void end() {
		Map<String, PacketMeter.Row> sent = PacketMeter.snapshot();
		long done = lightDone;
		rows.add(String.format(Locale.ROOT, "%s,%d,%.2f,%s,%.2f,%.2f,%d,%s,%s,%s", NAMES[index], blocks, editMs, done == 0L ? "timed_out" : String.format(Locale.ROOT, "%.1f", (done - editEnd) / 1e6),
				ticks == 0 ? 0.0 : tickSum / 1e6 / ticks, tickWorst / 1e6, ticksSlow, sentText(sent, "light_update"), sentText(sent, "section_update"), sentText(sent, "block_update")));
		active = false;
		index++;
	}

	private static String sentText(Map<String, PacketMeter.Row> sent, String type) {
		PacketMeter.Row row = sent.get(type);
		return row == null ? "0,0.0" : String.format(Locale.ROOT, "%d,%.1f", row.count(), row.compressedTotal() / 1024.0);
	}

	private int edit(ServerLevel level, int x, int z) {
		BlockState air = Blocks.AIR.defaultBlockState();
		return switch (NAMES[index]) {
			case "single_blocks" -> {
				int changed = 0;
				for (int i = 0; i < 10; i++) {
					int bx = x - 96 + i * 3;
					changed += set(level, bx, surface(level, bx, z), z, air);
				}
				yield changed;
			}
			case "shaft_to_bottom" -> {
				int bx = x - 64;
				int bottom = level.getMinY();
				BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
				while (bottom < level.getMaxY() && level.getBlockState(pos.set(bx, bottom, z)).isAir()) {
					bottom++;
				}
				int changed = 0;
				// Leaves the bottom block, so the world stays sealed.
				for (int y = surface(level, bx, z); y > bottom; y--) {
					changed += set(level, bx, y, z, air);
				}
				yield changed;
			}
			case "pit_32x32x8" -> {
				int top = surface(level, x - 16, z + 16);
				yield fill(level, x - 32, z, 32, top - 7, top, air);
			}
			case "roof_64x64" -> {
				roofY = surface(level, x + 48, z + 32) + ROOF_ABOVE_SURFACE;
				yield fill(level, x + 16, z, 64, roofY, roofY, Blocks.STONE.defaultBlockState());
			}
			default -> fill(level, x + 16, z, 64, roofY, roofY, air);
		};
	}

	private static int surface(ServerLevel level, int x, int z) {
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
	}

	private static int fill(ServerLevel level, int x, int z, int size, int yLow, int yHigh, BlockState state) {
		int changed = 0;
		for (int y = yHigh; y >= yLow; y--) {
			for (int dz = 0; dz < size; dz++) {
				for (int dx = 0; dx < size; dx++) {
					changed += set(level, x + dx, y, z + dz, state);
				}
			}
		}
		return changed;
	}

	private static int set(ServerLevel level, int x, int y, int z, BlockState state) {
		return level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL) ? 1 : 0;
	}
}
