package de.opsucht.resourcegrabber.mixin;

import de.opsucht.resourcegrabber.ResourceGrabberClient;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.packs.DownloadQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DownloadQueue.class)
abstract class DownloaderMixin {
    @Inject(method = "downloadBatch", at = @At("RETURN"))
    private void resourcegrabber$copyDownloadedPacks(
        DownloadQueue.BatchConfig config,
        Map<UUID, DownloadQueue.DownloadRequest> entries,
        CallbackInfoReturnable<CompletableFuture<DownloadQueue.BatchResult>> callback
    ) {
        String serverName = ResourceGrabberClient.getCurrentServerName();
        callback.getReturnValue().thenAccept(result ->
            ResourceGrabberClient.capture(result.downloaded(), serverName));
    }
}
