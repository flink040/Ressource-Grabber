package de.opsucht.resourcegrabber;

import de.opsucht.resourcegrabber.mixin.DisplayEntityAccessor;
import de.opsucht.resourcegrabber.mixin.ItemDisplayEntityAccessor;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
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

    static void register() {
        UseBlockCallback.EVENT.register(PlacementController::useBlock);
    }

    static boolean begin(CustomItemEntry entry) {
        Minecraft client = Minecraft.getInstance();
        if (!client.hasSingleplayerServer() || client.getSingleplayerServer() == null
            || client.player == null) {
            ResourceGrabberClient.notifyClient(Component.translatable(
                "message.resourcegrabber.placement_singleplayer"));
            return false;
        }
        pendingEntry = entry;
        client.gui.setScreen(null);
        client.gui.hud.setOverlayMessage(Component.translatable(
            "message.resourcegrabber.placement_ready", entry.displayName()), false);
        return true;
    }

    static void cancel() {
        pendingEntry = null;
    }

    static void update(UUID entityId, PlacementState state) {
        runOnDisplay(entityId, display -> applyState(display, state));
    }

    static void remove(UUID entityId) {
        runOnDisplay(entityId, Entity::discard);
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
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            pendingEntry = null;
            return InteractionResult.PASS;
        }

        CustomItemEntry entry = pendingEntry;
        pendingEntry = null;
        ItemStack stack = entry.createStack(false, false);
        UUID playerId = client.player.getUUID();
        Vec3 faceOffset = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i())
            .scale(0.01D);
        Vec3 position = hit.getLocation().add(faceOffset);
        float yaw = Math.round(client.player.getYRot() / 15.0F) * 15.0F;
        PlacementState initial = new PlacementState(
            position.x, position.y, position.z, yaw, 1.0F);

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
            display.setPos(initial.x(), initial.y(), initial.z());
            applyState(display, initial);
            if (!serverWorld.addFreshEntity(display)) {
                ResourceGrabberClient.notifyClient(Component.translatable(
                    "message.resourcegrabber.placement_failed"));
                return;
            }
            UUID entityId = display.getUUID();
            client.execute(() -> client.gui.setScreen(
                new PlacedModelEditorScreen(entityId, entry.displayName(), initial)));
        });
        return InteractionResult.SUCCESS;
    }

    private static void runOnDisplay(
        UUID entityId, java.util.function.Consumer<Display.ItemDisplay> action
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
        Quaternionf rotation = new Quaternionf()
            .rotationY((float)Math.toRadians(-state.yaw()));
        Vector3f scale = new Vector3f(state.scale(), state.scale(), state.scale());
        ((DisplayEntityAccessor)display).resourcegrabber$setTransformation(
            new Transformation(new Vector3f(), rotation, scale, new Quaternionf()));
    }

    record PlacementState(double x, double y, double z, float yaw, float scale) {
    }

    private PlacementController() {
    }
}
