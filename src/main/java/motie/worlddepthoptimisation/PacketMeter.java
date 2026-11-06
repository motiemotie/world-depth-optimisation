package motie.worlddepthoptimisation;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;

/**
 * How many bytes world packets would take on a real connection. Singleplayer hands packets straight across without encoding them,
 * so while a benchmark listens, each one is also encoded and compressed the way a server would, on a thread of its own. When that
 * thread falls behind a packet is counted but not sized, and totals are scaled up from those that were.
 */
public final class PacketMeter {
	/** Averages in bytes; totals scaled up to every packet counted. */
	public record Row(long count, long sized, double rawAvg, double compressedAvg, double sectionsAvg, double lightAvg, double rawTotal, double compressedTotal) {}

	private static final class Tally {
		final LongAdder count = new LongAdder();
		final LongAdder sized = new LongAdder();
		final LongAdder raw = new LongAdder();
		final LongAdder compressed = new LongAdder();
		final LongAdder sections = new LongAdder();
		final LongAdder light = new LongAdder();
	}

	public static volatile boolean listening;

	private static final int QUEUE = 4096;
	private static final ConcurrentHashMap<String, Tally> TALLIES = new ConcurrentHashMap<>();
	private static final LongAdder QUEUED = new LongAdder();
	private static final LongAdder DONE = new LongAdder();
	private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(Deflater::new);
	private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE), runnable -> {
		Thread thread = new Thread(runnable, "worlddepthoptimisation-packet-meter");
		thread.setDaemon(true);
		return thread;
	});

	private static volatile RegistryAccess registries;

	private PacketMeter() {}

	public static void start(RegistryAccess access) {
		registries = access;
		reset();
		listening = true;
	}

	public static void reset() {
		TALLIES.clear();
	}

	/** Whether every packet seen so far has been sized. */
	public static boolean idle() {
		return QUEUED.sum() == DONE.sum();
	}

	public static void observe(Packet<?> packet) {
		if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
			measure("chunk", buffer -> ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, chunk), tally -> {
				tally.sections.add(chunk.chunkData().getReadBuffer().readableBytes());
				tally.light.add(lightBytes(chunk));
			});
		} else if (packet instanceof ClientboundLightUpdatePacket light) {
			measure("light_update", buffer -> ClientboundLightUpdatePacket.STREAM_CODEC.encode(buffer, light), tally -> {});
		} else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
			measure("section_update", buffer -> ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.encode(buffer, section), tally -> {});
		} else if (packet instanceof ClientboundBlockUpdatePacket block) {
			measure("block_update", buffer -> ClientboundBlockUpdatePacket.STREAM_CODEC.encode(buffer, block), tally -> {});
		}
	}

	private static long lightBytes(ClientboundLevelChunkWithLightPacket chunk) {
		long bytes = 0L;
		for (byte[] layer : chunk.lightData().skyUpdates()) {
			bytes += layer.length;
		}
		for (byte[] layer : chunk.lightData().blockUpdates()) {
			bytes += layer.length;
		}
		return bytes;
	}

	private static void measure(String type, Consumer<RegistryFriendlyByteBuf> encode, Consumer<Tally> extra) {
		Tally tally = TALLIES.computeIfAbsent(type, k -> new Tally());
		tally.count.increment();
		QUEUED.increment();
		try {
			WORKER.execute(() -> {
				try {
					size(tally, encode, extra);
				} catch (RuntimeException e) {
					// A packet that cannot be sized is simply not counted in the averages.
				} finally {
					DONE.increment();
				}
			});
		} catch (RejectedExecutionException e) {
			DONE.increment();
		}
	}

	private static void size(Tally tally, Consumer<RegistryFriendlyByteBuf> encode, Consumer<Tally> extra) {
		ByteBuf raw = Unpooled.buffer();
		try {
			encode.accept(new RegistryFriendlyByteBuf(raw, registries));
			byte[] bytes = new byte[raw.readableBytes()];
			raw.readBytes(bytes);
			Deflater deflater = DEFLATER.get();
			deflater.reset();
			deflater.setInput(bytes);
			deflater.finish();
			byte[] out = new byte[8192];
			long compressed = 0L;
			while (!deflater.finished()) {
				compressed += deflater.deflate(out);
			}
			tally.raw.add(bytes.length);
			tally.compressed.add(compressed);
			extra.accept(tally);
			tally.sized.increment();
		} finally {
			raw.release();
		}
	}

	public static Map<String, Row> snapshot() {
		Map<String, Row> rows = new TreeMap<>();
		TALLIES.forEach((type, t) -> {
			long sized = Math.max(1L, t.sized.sum());
			long count = t.count.sum();
			double rawAvg = t.raw.sum() / (double) sized;
			double compressedAvg = t.compressed.sum() / (double) sized;
			rows.put(type, new Row(count, t.sized.sum(), rawAvg, compressedAvg, t.sections.sum() / (double) sized, t.light.sum() / (double) sized, rawAvg * count, compressedAvg * count));
		});
		return rows;
	}
}
