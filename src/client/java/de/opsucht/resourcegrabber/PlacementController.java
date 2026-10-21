package de.opsucht.resourcegrabber;

import de.opsucht.resourcegrabber.mixin.DisplayEntityAccessor;
import de.opsucht.resourcegrabber.mixin.ItemDisplayEntityAccessor;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
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
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class PlacementController {
    private static CustomItemEntry pendingEntry;

    static void register() {
        UseBlockCallback.EVENT.register(PlacementController::useBlock);
    }

    static boolean begin(CustomItemEntry entry) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isInSingleplayer() || client.getServer() == null || client.player == null) {
            ResourceGrabberClient.notifyClient(Text.translatable(
                "message.resourcegrabber.placement_singleplayer"));
            return false;
        }
        pendingEntry = entry;
        client.setScreen(null);
        client.inGameHud.setOverlayMessage(Text.translatable(
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

    private static ActionResult useBlock(
        net.minecraft.entity.player.PlayerEntity player,
        net.minecraft.world.World world,
        Hand hand,
        BlockHitResult hit
    ) {
        if (!world.isClient() || hand != Hand.MAIN_HAND || pendingEntry == null) {
            return ActionResult.PASS;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        MinecraftServer server = client.getServer();
        if (server == null || client.player == null) {
            pendingEntry = null;
            return ActionResult.PASS;
        }

        CustomItemEntry entry = pendingEntry;
        pendingEntry = null;
        ItemStack stack = entry.createStack(false, false);
        UUID playerId = client.player.getUuid();
        Vec3d faceOffset = Vec3d.of(hit.getSide().getVector()).multiply(0.01D);
        Vec3d position = hit.getPos().add(faceOffset);
        float yaw = Math.round(client.player.getYaw() / 15.0F) * 15.0F;
        PlacementState initial = new PlacementState(
            position.x, position.y, position.z, yaw, 1.0F);

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
            display.setPosition(initial.x(), initial.y(), initial.z());
            applyState(display, initial);
            if (!serverWorld.spawnEntity(display)) {
                ResourceGrabberClient.notifyClient(Text.translatable(
                    "message.resourcegrabber.placement_failed"));
                return;
            }
            UUID entityId = display.getUuid();
            client.execute(() -> client.setScreen(
                new PlacedModelEditorScreen(entityId, entry.displayName(), initial)));
        });
        return ActionResult.SUCCESS;
    }

    private static void runOnDisplay(
        UUID entityId, java.util.function.Consumer<DisplayEntity.ItemDisplayEntity> action
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
        Quaternionf rotation = new Quaternionf()
            .rotationY((float)Math.toRadians(-state.yaw()));
        Vector3f scale = new Vector3f(state.scale(), state.scale(), state.scale());
        ((DisplayEntityAccessor)display).resourcegrabber$setTransformation(
            new AffineTransformation(new Vector3f(), rotation, scale, new Quaternionf()));
    }

    record PlacementState(double x, double y, double z, float yaw, float scale) {
    }

    private PlacementController() {
    }
}
