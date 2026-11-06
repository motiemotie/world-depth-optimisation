package motie.worlddepthoptimisation.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import motie.worlddepthoptimisation.client.BenchRun;

/** One call of runTick is one frame, so the time between calls is the true frame time, whatever else happened in it. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Unique
	private long worlddepthoptimisation$lastFrame;

	@Inject(method = "runTick", at = @At("HEAD"))
	private void worlddepthoptimisation$frame(boolean advanceGameTime, CallbackInfo ci) {
		long now = System.nanoTime();
		if (worlddepthoptimisation$lastFrame != 0L) {
			BenchRun.frame(now - worlddepthoptimisation$lastFrame);
		}
		worlddepthoptimisation$lastFrame = now;
	}
}
