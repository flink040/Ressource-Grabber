package de.opsucht.resourcegrabber;

import de.opsucht.resourcegrabber.mixin.DisplayEntityAccessor;
import de.opsucht.resourcegrabber.mixin.ItemDisplayEntityAccessor;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
        Minecraft client = Minecraft.getInstance();
        if (!client.hasSingleplayerServer() || client.getSingleplayerServer() == null
            || client.player == null) {
            ResourceGrabberClient.notifyClient(Component.translatable(
                "message.resourcegrabber.placement_singleplayer"));
            return false;
        }
        cancel();
        pendingEntry = entry;
        commitRequested = false;
        float yaw = snap(client.player.getYRot(), 15.0F);
        Vec3 start = new Vec3(client.player.getX(), client.player.getY(), client.player.getZ())
            .add(client.player.getViewVector(1.0F).scale(2));
        previewState = new PlacementState(start.x, start.y, start.z,
            0.0F, yaw, 0.0F, 1.0F);
        client.gui.setScreen(null);
        client.gui.hud.setOverlayMessage(Component.translatable(
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
            ResourceGrabberClient.notifyClient(Component.translatable(
                "message.resourcegrabber.model_duplicated")));
    }

    static boolean equipAsHat(CustomItemEntry entry) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (!client.hasSingleplayerServer() || server == null || client.player == null) {
            client.gui.setScreen(new ChatScreen("/hat", false));
            return true;
        }
        UUID playerId = client.player.getUUID();
        ItemStack stack = entry.createStack(false, false);
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.setItemSlot(EquipmentSlot.HEAD, stack.copy());
            }
        });
        return true;
    }

    private static void tickPreview(Minecraft client) {
        if (pendingEntry == null) {
            return;
        }
        if (client.player == null || client.level == null
            || client.getSingleplayerServer() == null) {
            cancel();
            return;
        }
        if (!(client.hitResult instanceof BlockHitResult hit)) {
            return;
        }
        PlacementState next = stateAt(hit, client.player.getYRot());
        if (!next.closeTo(previewState)) {
            previewState = next;
            if (previewEntityId != null) {
                update(previewEntityId, next);
            }
        }
    }

    private static InteractionResult useBlock(
        Player player,
        Level world,
        InteractionHand hand,
        BlockHitResult hit
    ) {
        if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND || pendingEntry == null) {
            return InteractionResult.PASS;
        }
        previewState = stateAt(hit, player.getYRot());
        commitRequested = true;
        if (previewEntityId != null) {
            update(previewEntityId, previewState);
            finishCommit();
        }
        return InteractionResult.SUCCESS;
    }

    private static void finishCommit() {
        Minecraft client = Minecraft.getInstance();
        UUID entityId = previewEntityId;
        CustomItemEntry entry = pendingEntry;
        PlacementState state = previewState;
        if (entityId == null || entry == null || state == null) {
            return;
        }
        runOnDisplay(entityId, display -> display.setGlowingTag(false));
        pendingEntry = null;
        previewEntityId = null;
        previewState = null;
        commitRequested = false;
        client.execute(() -> client.gui.setScreen(
            new AdvancedModelEditorScreen(entityId, entry, state)));
    }

    private static PlacementState stateAt(BlockHitResult hit, float playerYaw) {
        Vec3 faceOffset = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i())
            .scale(0.01D);
        Vec3 position = hit.getLocation().add(faceOffset);
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
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            return;
        }
        UUID playerId = client.player.getUUID();
        ItemStack stack = entry.createStack(false, false);
        server.execute(() -> {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerId);
            if (serverPlayer == null) {
                return;
            }
            ServerLevel serverWorld = serverPlayer.level();
            Display.ItemDisplay display = new Display.ItemDisplay(
                EntityTypes.ITEM_DISPLAY, serverWorld);
            ((ItemDisplayEntityAccessor)display).resourcegrabber$setItemStack(stack.copy());
            ((ItemDisplayEntityAccessor)display).resourcegrabber$setItemDisplayContext(
                ItemDisplayContext.FIXED);
            display.setGlowingTag(preview);
            applyState(display, state);
            if (!serverWorld.addFreshEntity(display)) {
                ResourceGrabberClient.notifyClient(Component.translatable(
                    "message.resourcegrabber.placement_failed"));
                return;
            }
            UUID entityId = display.getUUID();
            client.execute(() -> onSpawned.accept(entityId));
        });
    }

    private static void runOnDisplay(
        UUID entityId, Consumer<Display.ItemDisplay> action
    ) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            return;
        }
        UUID playerId = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return;
            }
            Entity entity = player.level().getEntity(entityId);
            if (entity instanceof Display.ItemDisplay display) {
                action.accept(display);
            }
        });
    }

    private static void applyState(
        Display.ItemDisplay display, PlacementState state
    ) {
        display.setPos(state.x(), state.y(), state.z());
        Quaternionf rotation = new Quaternionf().rotationXYZ(
            (float)Math.toRadians(state.rotationX()),
            (float)Math.toRadians(-state.rotationY()),
            (float)Math.toRadians(state.rotationZ()));
        Vector3f scale = new Vector3f(state.scale(), state.scale(), state.scale());
        ((DisplayEntityAccessor)display).resourcegrabber$setTransformation(
            new Transformation(new Vector3f(), rotation, scale, new Quaternionf()));
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
                && Math.abs(this.x - other.x) < 0.0001D
                && Math.abs(this.y - other.y) < 0.0001D
                && Math.abs(this.z - other.z) < 0.0001D
                && Math.abs(this.rotationY - other.rotationY) < 0.0001F;
        }
    }

    private PlacementController() {
    }
}
