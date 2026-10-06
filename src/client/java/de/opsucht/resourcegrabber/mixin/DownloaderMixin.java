package de.opsucht.resourcegrabber.mixin;

import de.opsucht.resourcegrabber.ResourceGrabberClient;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.Downloader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Downloader.class)
abstract class DownloaderMixin {
    @Inject(method = "downloadAsync", at = @At("RETURN"))
    private void resourcegrabber$copyDownloadedPacks(
        Downloader.Config config,
        Map<UUID, Downloader.DownloadEntry> entries,
        CallbackInfoReturnable<CompletableFuture<Downloader.DownloadResult>> callback
    ) {
        String serverName = ResourceGrabberClient.getCurrentServerName();
        callback.getReturnValue().thenAccept(result ->
            ResourceGrabberClient.capture(result.downloaded(), serverName));
    }
}
