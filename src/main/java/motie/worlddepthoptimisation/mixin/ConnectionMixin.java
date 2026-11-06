package motie.worlddepthoptimisation.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.ChannelFutureListener;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

import motie.worlddepthoptimisation.PacketMeter;

/** Shows outgoing packets to the benchmark. Costs nothing unless one is listening. */
@Mixin(Connection.class)
public class ConnectionMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
	private void worlddepthoptimisation$meter(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		if (PacketMeter.listening) {
			PacketMeter.observe(packet);
		}
	}
}
