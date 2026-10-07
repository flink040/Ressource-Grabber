package de.opsucht.resourcegrabber;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.slf4j.Logger;

public final class ResourceGrabberClient implements ClientModInitializer {
    public static final String MOD_ID = "resourcegrabber";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
        .getConfigDir().resolve("resourcegrabber.json");
    private static final ExecutorService COPY_EXECUTOR = Executors.newSingleThreadExecutor(
        new DaemonThreadFactory());

    private static volatile Config config = new Config();
    private static volatile boolean customItemsMenuRequested;
    private static int itemNameScanTicks;
    private static int itemNameSaveTicks;
    private static int learningFeedbackCooldownTicks = 100;
    private static int pendingLearnedItems;
    private static String latestLearnedName = "";

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        LearnedItemNames.load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("customitems").executes(context -> {
                requestCustomItemsMenu();
                return 1;
            })));
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (!command.strip().equalsIgnoreCase("customitems")) {
                return true;
            }
            requestCustomItemsMenu();
            return false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (customItemsMenuRequested) {
                customItemsMenuRequested = false;
                openCustomItemsMenu();
            }
            observeVisibleCustomItems(client);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LearnedItemNames.saveIfDirty();
            COPY_EXECUTOR.shutdown();
        });
        LOGGER.info("Resource Grabber initialized (enabled: {})", config.enabled);
    }

    private static void requestCustomItemsMenu() {
        customItemsMenuRequested = true;
        LOGGER.info("Opening the custom items menu on the next client tick");
    }

    private static void observeVisibleCustomItems(MinecraftClient client) {
        if (learningFeedbackCooldownTicks < 100) {
            learningFeedbackCooldownTicks++;
        }
        if (client.player == null || ++itemNameScanTicks < 10) {
            return;
        }
        itemNameScanTicks = 0;
        String serverName = CustomItemCatalog.sourceServer();
        LearnedItemNames.LearningResult learned = LearnedItemNames.LearningResult.NONE;
        for (Slot slot : client.player.currentScreenHandler.slots) {
            learned = learned.merge(LearnedItemNames.observe(serverName, slot.getStack()));
        }
        learned = learned.merge(LearnedItemNames.observe(serverName,
            client.player.currentScreenHandler.getCursorStack()));
        learned = learned.merge(ChestShopScanner.scan(client, serverName));
        queueLearningFeedback(client, learned);
        if (++itemNameSaveTicks >= 10) {
            itemNameSaveTicks = 0;
            LearnedItemNames.saveIfDirty();
        }
    }

    private static void queueLearningFeedback(
        MinecraftClient client, LearnedItemNames.LearningResult learned
    ) {
        if (learned.count() > 0 && !learningFeedbackMode().equals("off")) {
            pendingLearnedItems += learned.count();
            if (!learned.latestName().isBlank()) {
                latestLearnedName = learned.latestName();
            }
        }
        if (pendingLearnedItems == 0 || learningFeedbackCooldownTicks < 100) {
            return;
        }
        Text message;
        if (learningFeedbackMode().equals("detailed")
            && pendingLearnedItems == 1 && !latestLearnedName.isBlank()) {
            message = Text.translatable(
                "message.resourcegrabber.learned_detailed", latestLearnedName);
        } else {
            message = Text.translatable(
                "message.resourcegrabber.learned", pendingLearnedItems);
        }
        client.inGameHud.setOverlayMessage(message, false);
        pendingLearnedItems = 0;
        latestLearnedName = "";
        learningFeedbackCooldownTicks = 0;
    }

    public static boolean showLearningCounter() {
        return !learningFeedbackMode().equals("off");
    }

    private static String learningFeedbackMode() {
        String mode = config.learningFeedback;
        if (mode == null) {
            return "subtle";
        }
        mode = mode.strip().toLowerCase(java.util.Locale.ROOT);
        return mode.equals("off") || mode.equals("detailed") ? mode : "subtle";
    }

    public static void openCustomItemsMenu() {
        MinecraftClient client = MinecraftClient.getInstance();
        List<CustomItemEntry> items = CustomItemCatalog.snapshot();
        if (!items.isEmpty()) {
            client.setScreen(new CustomItemsScreen(items));
            return;
        }

        notifyClient("message.resourcegrabber.custom_items_loading", null);
        String serverName = getCurrentServerName();
        COPY_EXECUTOR.execute(() -> {
            try {
                Optional<Path> savedPack = findNewestSavedPack(
                    client.getResourcePackDir(), serverName);
                if (savedPack.isEmpty()) {
                    notifyClient("message.resourcegrabber.custom_items_empty", null);
                    return;
                }

                List<CustomItemEntry> loadedItems = CustomItemCatalog.readPack(savedPack.get());
                CustomItemCatalog.replace(loadedItems, serverName);
                List<CustomItemEntry> loadedSnapshot = CustomItemCatalog.snapshot();
                if (loadedSnapshot.isEmpty()) {
                    notifyClient("message.resourcegrabber.custom_items_empty", null);
                    return;
                }

                LOGGER.info("Loaded {} custom-model items from {}",
                    loadedSnapshot.size(), savedPack.get());
                client.execute(() -> client.setScreen(new CustomItemsScreen(loadedSnapshot)));
            } catch (IOException | RuntimeException exception) {
                LOGGER.error("Could not load custom-model items from a saved resource pack", exception);
                notifyClient("message.resourcegrabber.custom_items_load_failed", null);
            }
        });
    }

    public static String getCurrentServerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        ServerInfo server = client.getCurrentServerEntry();
        if (server != null && server.address != null && !server.address.isBlank()) {
            return sanitize(server.address);
        }
        return client.isInSingleplayer() ? "singleplayer" : "server";
    }

    public static void capture(Map<UUID, Path> downloadedPacks, String serverName) {
        if (!config.enabled || downloadedPacks.isEmpty()) {
            return;
        }

        Map<UUID, Path> snapshot = Map.copyOf(downloadedPacks);
        COPY_EXECUTOR.execute(() -> {
            List<CustomItemEntry> customItems = new ArrayList<>();
            snapshot.forEach((id, source) -> {
                try {
                    copyPack(source, serverName);
                    customItems.addAll(CustomItemCatalog.readPack(source));
                } catch (IOException | NoSuchAlgorithmException exception) {
                    LOGGER.error("Could not process downloaded resource pack {} from {}",
                        id, source, exception);
                    notifyClient("message.resourcegrabber.failed", null);
                }
            });
            CustomItemCatalog.replace(customItems, serverName);
            int uniqueItemCount = CustomItemCatalog.snapshot().size();
            if (uniqueItemCount > 0 && config.showChatMessage) {
                notifyClient("message.resourcegrabber.custom_items_ready",
                    Integer.toString(uniqueItemCount));
            }
        });
    }

    private static void copyPack(Path source, String serverName)
        throws IOException, NoSuchAlgorithmException {
        Optional<String> packVersion = readPackVersion(source);
        if (!Files.isRegularFile(source) || packVersion.isEmpty()) {
            LOGGER.debug("Ignoring downloaded file because it is not a valid resource pack: {}", source);
            return;
        }

        String contentHash = sha256(source);
        String safeServerName = sanitize(serverName);
        Path resourcePackDirectory = MinecraftClient.getInstance().getResourcePackDir();
        Files.createDirectories(resourcePackDirectory);

        Optional<Path> existingCopy = findExistingCopy(
            resourcePackDirectory, safeServerName, contentHash);
        if (existingCopy.isPresent()) {
            LOGGER.debug("This resource pack version is already saved as {}", existingCopy.get());
            return;
        }

        String captureDate = LocalDate.now().format(FILE_DATE_FORMAT);
        String fileStem = "serverpack-" + safeServerName
            + "-" + captureDate
            + "-v" + packVersion.get()
            + "-" + contentHash.substring(0, 12);
        Path target = findAvailableTarget(resourcePackDirectory, fileStem);
        String fileName = target.getFileName().toString();

        Path temporary = Files.createTempFile(resourcePackDirectory, ".resourcegrabber-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveIntoPlace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }

        LOGGER.info("Saved server resource pack to {}", target);
        if (config.showChatMessage) {
            notifyClient("message.resourcegrabber.saved", fileName);
        }
    }

    private static Optional<String> readPackVersion(Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }

        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry metadata = zip.getEntry("pack.mcmeta");
            if (metadata == null || metadata.isDirectory()) {
                return Optional.empty();
            }

            try (Reader reader = new InputStreamReader(
                zip.getInputStream(metadata), StandardCharsets.UTF_8)) {
                JsonElement rootElement = JsonParser.parseReader(reader);
                if (!rootElement.isJsonObject()) {
                    return Optional.empty();
                }

                JsonObject root = rootElement.getAsJsonObject();
                JsonObject pack = root.has("pack") && root.get("pack").isJsonObject()
                    ? root.getAsJsonObject("pack") : null;
                if (pack == null) {
                    return Optional.empty();
                }

                String version = firstVersionValue(pack.get("version"), root.get("version"));
                if (version == null) {
                    version = versionValue(pack.get("pack_format"));
                }
                if (version == null) {
                    String minimum = versionValue(pack.get("min_format"));
                    String maximum = versionValue(pack.get("max_format"));
                    if (minimum != null && maximum != null) {
                        version = minimum.equals(maximum) ? minimum : minimum + "-" + maximum;
                    } else {
                        version = minimum != null ? minimum : maximum;
                    }
                }

                return Optional.of(sanitizeVersion(version == null ? "unknown" : version));
            }
        } catch (ZipException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static String firstVersionValue(JsonElement... candidates) {
        for (JsonElement candidate : candidates) {
            String value = versionValue(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String versionValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            JsonArray values = element.getAsJsonArray();
            StringBuilder result = new StringBuilder();
            for (JsonElement value : values) {
                String part = versionValue(value);
                if (part == null) {
                    return null;
                }
                if (!result.isEmpty()) {
                    result.append('.');
                }
                result.append(part);
            }
            return result.isEmpty() ? null : result.toString();
        }
        return null;
    }

    private static Optional<Path> findExistingCopy(
        Path directory, String serverName, String expectedHash
    ) throws IOException, NoSuchAlgorithmException {
        String prefix = "serverpack-" + serverName + "-";
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path candidate : paths
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith(prefix) && name.endsWith(".zip");
                })
                .toList()) {
                try {
                    if (sha256(candidate).equals(expectedHash)) {
                        return Optional.of(candidate);
                    }
                } catch (IOException exception) {
                    LOGGER.debug("Could not compare existing resource pack {}", candidate, exception);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> findNewestSavedPack(Path directory, String serverName)
        throws IOException {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }

        String currentServerPrefix = "serverpack-" + sanitize(serverName) + "-";
        try (Stream<Path> paths = Files.list(directory)) {
            List<Path> packs = paths
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith("serverpack-") && name.endsWith(".zip");
                })
                .toList();

            Optional<Path> currentServerPack = newestPack(packs.stream()
                .filter(path -> path.getFileName().toString().startsWith(currentServerPrefix)));
            return currentServerPack.isPresent() ? currentServerPack : newestPack(packs.stream());
        }
    }

    private static Optional<Path> newestPack(Stream<Path> packs) {
        return packs.max((left, right) -> lastModified(left).compareTo(lastModified(right)));
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return FileTime.fromMillis(0L);
        }
    }

    private static Path findAvailableTarget(Path directory, String fileStem) {
        Path candidate = directory.resolve(fileStem + ".zip");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(fileStem + "-" + suffix + ".zip");
            suffix++;
        }
        return candidate;
    }

    private static String sha256(Path source) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(source)) {
            byte[] buffer = new byte[16 * 1024];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void notifyClient(String translationKey, String argument) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player == null) {
                return;
            }
            Text message = argument == null
                ? Text.translatable(translationKey)
                : Text.translatable(translationKey, argument);
            client.inGameHud.getChatHud().addMessage(message);
        });
    }

    private static Config loadConfig() {
        Config defaults = new Config();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (Files.isRegularFile(CONFIG_PATH)) {
                try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                    Config loaded = GSON.fromJson(reader, Config.class);
                    if (loaded != null) {
                        return loaded;
                    }
                }
            }
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(defaults, writer);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not load or create {}. Using defaults.", CONFIG_PATH, exception);
        }
        return defaults;
    }

    private static String sanitize(String value) {
        String sanitized = value.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
        if (sanitized.isBlank()) {
            return "server";
        }
        return sanitized.substring(0, Math.min(sanitized.length(), 64));
    }

    private static String sanitizeVersion(String value) {
        String sanitized = value.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
        if (sanitized.isBlank()) {
            return "unknown";
        }
        return sanitized.substring(0, Math.min(sanitized.length(), 32));
    }

    private static final class Config {
        private boolean enabled = true;
        private boolean showChatMessage = true;
        private String learningFeedback = "subtle";
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "ResourceGrabber-Copy");
            thread.setDaemon(true);
            return thread;
        }
    }

    public ResourceGrabberClient() {
    }
}
