package de.opsucht.resourcegrabber;

import de.opsucht.resourcegrabber.mixin.DisplayEntityAccessor;
import de.opsucht.resourcegrabber.mixin.ItemDisplayEntityAccessor;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class PlacementController {
    private static CustomItemEntry pendingEntry;
    private static UUID previewEntityId;
    private static PlacementState previewState;
    private static boolean commitRequested;

    static void register() {
        UseBlockCallback.EVENT.register(PlacementController::useBlock);
        ClientTickEvents.END_CLIENT_TICK.register(PlacementController::tickPreview);
    }

    static boolean begin(CustomItemEntry entry) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isInSingleplayer() || client.getServer() == null || client.player == null) {
            ResourceGrabberClient.notifyClient(Text.translatable(
                "message.resourcegrabber.placement_singleplayer"));
            return false;
        }
        cancel();
        pendingEntry = entry;
        commitRequested = false;
        float yaw = snap(client.player.getYaw(), 15.0F);
        Vec3d start = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ())
            .add(client.player.getRotationVec(1.0F).multiply(2));
        previewState = new PlacementState(start.x, start.y, start.z,
            0.0F, yaw, 0.0F, 1.0F);
        client.setScreen(null);
        client.inGameHud.setOverlayMessage(Text.translatable(
            "message.resourcegrabber.placement_preview", entry.displayName()), false);
        spawnDisplay(entry, previewState, true, entityId -> {
            if (pendingEntry != entry) {
                remove(entityId);
                return;
            }
            previewEntityId = entityId;
            if (commitRequested) {
                finishCommit();
            }
        });
        return true;
    }

    static void cancel() {
        if (previewEntityId != null) {
            remove(previewEntityId);
        }
        pendingEntry = null;
        previewEntityId = null;
        previewState = null;
        commitRequested = false;
    }

    static void update(UUID entityId, PlacementState state) {
        runOnDisplay(entityId, display -> applyState(display, state));
    }

    static void remove(UUID entityId) {
        runOnDisplay(entityId, Entity::discard);
    }

    static void duplicate(CustomItemEntry entry, PlacementState state) {
        spawnDisplay(entry, state.offset(0.25D, 0.0D, 0.25D), false, entityId ->
            ResourceGrabberClient.notifyClient(Text.translatable(
                "message.resourcegrabber.model_duplicated")));
    }

    static boolean equipAsHat(CustomItemEntry entry) {
        MinecraftClient client = MinecraftClient.getInstance();
        MinecraftServer server = client.getServer();
        if (!client.isInSingleplayer() || server == null || client.player == null) {
            client.setScreen(new ChatScreen("/hat", false));
            return true;
        }
        UUID playerId = client.player.getUuid();
        ItemStack stack = entry.createStack(false, false);
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                player.equipStack(EquipmentSlot.HEAD, stack.copy());
            }
        });
        return true;
    }

    private static void tickPreview(MinecraftClient client) {
        if (pendingEntry == null) {
            return;
        }
        if (client.player == null || client.world == null || client.getServer() == null) {
            cancel();
            return;
        }
        if (client.currentScreen != null) {
            cancel();
            client.inGameHud.setOverlayMessage(Text.translatable(
                "message.resourcegrabber.placement_cancelled"), false);
            return;
        }
        if (!(client.crosshairTarget instanceof BlockHitResult hit)) {
            return;
        }
        PlacementState next = stateAt(hit, client.player.getYaw());
        if (!next.closeTo(previewState)) {
            previewState = next;
            if (previewEntityId != null) {
                update(previewEntityId, next);
            }
        }
    }

    private static ActionResult useBlock(
        net.minecraft.entity.player.PlayerEntity player,
        net.minecraft.world.World world,
        Hand hand,
        BlockHitResult hit
    ) {
        if (!world.isClient() || hand != Hand.MAIN_HAND || pendingEntry == null) {
            return ActionResult.PASS;
        }
        previewState = stateAt(hit, player.getYaw());
        commitRequested = true;
        if (previewEntityId != null) {
            update(previewEntityId, previewState);
            finishCommit();
        }
        return ActionResult.SUCCESS;
    }

    private static void finishCommit() {
        MinecraftClient client = MinecraftClient.getInstance();
        UUID entityId = previewEntityId;
        CustomItemEntry entry = pendingEntry;
        PlacementState state = previewState;
        if (entityId == null || entry == null || state == null) {
            return;
        }
        runOnDisplay(entityId, display -> display.setGlowing(false));
        pendingEntry = null;
        previewEntityId = null;
        previewState = null;
        commitRequested = false;
        client.execute(() -> client.setScreen(
            new AdvancedModelEditorScreen(entityId, entry, state)));
    }

    private static PlacementState stateAt(BlockHitResult hit, float playerYaw) {
        Vec3d faceOffset = Vec3d.of(hit.getSide().getVector()).multiply(0.01D);
        Vec3d position = hit.getPos().add(faceOffset);
        return new PlacementState(position.x, position.y, position.z,
            0.0F, snap(playerYaw, 15.0F), 0.0F, 1.0F);
    }

    private static float snap(float value, float step) {
        return Math.round(value / step) * step;
    }

    private static void spawnDisplay(
        CustomItemEntry entry,
        PlacementState state,
        boolean preview,
        Consumer<UUID> onSpawned
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        MinecraftServer server = client.getServer();
        if (server == null || client.player == null) {
            return;
        }
        UUID playerId = client.player.getUuid();
        ItemStack stack = entry.createStack(false, false);
        server.execute(() -> {
            ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerId);
            if (serverPlayer == null) {
                return;
            }
            ServerWorld serverWorld = serverPlayer.getEntityWorld();
            DisplayEntity.ItemDisplayEntity display = new DisplayEntity.ItemDisplayEntity(
                EntityType.ITEM_DISPLAY, serverWorld);
            ((ItemDisplayEntityAccessor)display).resourcegrabber$setItemStack(stack.copy());
            ((ItemDisplayEntityAccessor)display).resourcegrabber$setItemDisplayContext(
                ItemDisplayContext.FIXED);
            display.setGlowing(preview);
            applyState(display, state);
            if (!serverWorld.spawnEntity(display)) {
                ResourceGrabberClient.notifyClient(Text.translatable(
                    "message.resourcegrabber.placement_failed"));
                return;
            }
            UUID entityId = display.getUuid();
            client.execute(() -> onSpawned.accept(entityId));
        });
    }

    private static void runOnDisplay(
        UUID entityId, Consumer<DisplayEntity.ItemDisplayEntity> action
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        MinecraftServer server = client.getServer();
        if (server == null || client.player == null) {
            return;
        }
        UUID playerId = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                return;
            }
            Entity entity = player.getEntityWorld().getEntity(entityId);
            if (entity instanceof DisplayEntity.ItemDisplayEntity display) {
                action.accept(display);
            }
        });
    }

    private static void applyState(
        DisplayEntity.ItemDisplayEntity display, PlacementState state
    ) {
        display.setPosition(state.x(), state.y(), state.z());
        Quaternionf rotation = new Quaternionf().rotationXYZ(
            (float)Math.toRadians(state.rotationX()),
            (float)Math.toRadians(-state.rotationY()),
            (float)Math.toRadians(state.rotationZ()));
        Vector3f scale = new Vector3f(state.scale(), state.scale(), state.scale());
        ((DisplayEntityAccessor)display).resourcegrabber$setTransformation(
            new AffineTransformation(new Vector3f(), rotation, scale, new Quaternionf()));
    }

    record PlacementState(
        double x,
        double y,
        double z,
        float rotationX,
        float rotationY,
        float rotationZ,
        float scale
    ) {
        PlacementState(double x, double y, double z, float yaw, float scale) {
            this(x, y, z, 0.0F, yaw, 0.0F, scale);
        }

        float yaw() {
            return this.rotationY;
        }

        PlacementState offset(double x, double y, double z) {
            return new PlacementState(this.x + x, this.y + y, this.z + z,
                this.rotationX, this.rotationY, this.rotationZ, this.scale);
        }

        boolean closeTo(PlacementState other) {
            return other != null
                && MathHelper.approximatelyEquals((float)this.x, (float)other.x)
                && MathHelper.approximatelyEquals((float)this.y, (float)other.y)
                && MathHelper.approximatelyEquals((float)this.z, (float)other.z)
                && MathHelper.approximatelyEquals(this.rotationY, other.rotationY);
        }
    }

    private PlacementController() {
    }
}
