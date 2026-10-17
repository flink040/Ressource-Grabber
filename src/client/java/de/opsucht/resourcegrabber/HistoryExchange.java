package de.opsucht.resourcegrabber;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;

final class HistoryExchange {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FORMAT = "resourcegrabber-history";
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_IMPORT_SIZE = 64L * 1024L * 1024L;
    private static final Path HISTORY_DIRECTORY = FabricLoader.getInstance().getConfigDir()
        .resolve("resourcegrabber-history");

    static boolean exportInteractive() {
        String serverKey = ResourceGrabberClient.getCurrentServerName();
        String serverName = ResourceGrabberClient.getCurrentServerDisplayName();
        try {
            Files.createDirectories(HISTORY_DIRECTORY);
            Path suggested = HISTORY_DIRECTORY.resolve("ResourceGrabber History - "
                + safeFileName(serverName) + " - " + LocalDate.now() + ".json");
            String selected = TinyFileDialogs.tinyfd_saveFileDialog(
                Component.translatable("dialog.resourcegrabber.history_export").getString(),
                suggested.toAbsolutePath().toString(), null, "JSON");
            if (selected == null) {
                return false;
            }
            Path target = Path.of(selected);
            if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                target = target.resolveSibling(target.getFileName() + ".json");
            }
            Map<String, String> names = LearnedItemNames.exportNames(serverKey);
            Map<String, List<String>> lore = LearnedItemNames.exportLore(serverKey);
            Map<String, Map<String, List<String>>> chestShopIds =
                LearnedChestShopIds.exportServer(serverKey);
            HistoryFile history = new HistoryFile(FORMAT, SCHEMA_VERSION,
                Instant.now().toString(), serverKey, serverName, names, lore, chestShopIds);
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(target)) {
                GSON.toJson(history, writer);
            }
            ResourceGrabberClient.notifyClient(Component.translatable(
                "message.resourcegrabber.history_exported", names.size(), lore.size(),
                countIds(chestShopIds), target.getFileName().toString()));
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not export Resource Grabber history", exception);
            ResourceGrabberClient.notifyClient(Component.translatable(
                "message.resourcegrabber.history_export_failed"));
            return false;
        }
    }

    static boolean importInteractive() {
        try {
            Files.createDirectories(HISTORY_DIRECTORY);
            String selected = TinyFileDialogs.tinyfd_openFileDialog(
                Component.translatable("dialog.resourcegrabber.history_import").getString(),
                HISTORY_DIRECTORY.toAbsolutePath().toString(), null, "JSON", false);
            if (selected == null) {
                return false;
            }
            Path source = Path.of(selected);
            if (!Files.isRegularFile(source) || Files.size(source) > MAX_IMPORT_SIZE) {
                throw new IOException("History file is missing or larger than 64 MiB");
            }
            HistoryFile history;
            try (Reader reader = Files.newBufferedReader(source)) {
                history = GSON.fromJson(reader, HistoryFile.class);
            }
            if (history == null || !FORMAT.equals(history.format())
                || history.schemaVersion() != SCHEMA_VERSION) {
                ResourceGrabberClient.notifyClient(Component.translatable(
                    "message.resourcegrabber.history_incompatible"));
                return false;
            }
            String serverKey = ResourceGrabberClient.getCurrentServerName();
            LearnedItemNames.ImportCounts itemCounts = LearnedItemNames.importHistory(
                serverKey, history.names(), history.lore());
            int importedIds = LearnedChestShopIds.importServer(
                serverKey, history.chestShopIds());
            LearnedItemNames.saveIfDirty();
            LearnedChestShopIds.saveIfDirty();
            ResourceGrabberClient.notifyClient(Component.translatable(
                "message.resourcegrabber.history_imported", itemCounts.names(),
                itemCounts.lore(), importedIds));
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not import Resource Grabber history", exception);
            ResourceGrabberClient.notifyClient(Component.translatable(
                "message.resourcegrabber.history_import_failed"));
            return false;
        }
    }

    private static int countIds(Map<String, Map<String, List<String>>> scopes) {
        return scopes.values().stream().flatMap(items -> items.values().stream())
            .mapToInt(List::size).sum();
    }

    private static String safeFileName(String value) {
        String sanitized = value == null ? "Server"
            : value.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
        return sanitized.isBlank() ? "Server" : sanitized;
    }

    private record HistoryFile(
        String format,
        int schemaVersion,
        String exportedAt,
        String sourceServer,
        String sourceServerName,
        Map<String, String> names,
        Map<String, List<String>> lore,
        Map<String, Map<String, List<String>>> chestShopIds
    ) {
    }

    private HistoryExchange() {
    }
}
