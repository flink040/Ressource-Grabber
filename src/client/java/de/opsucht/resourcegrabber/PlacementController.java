package de.opsucht.resourcegrabber;

import de.opsucht.resourcegrabber.mixin.DisplayEntityAccessor;
import de.opsucht.resourcegrabber.mixin.ItemDisplayEntityAccessor;
import de.opsucht.resourcegrabber.mixin.ArmorStandEntityAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
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
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Rotations;
import org.joml.Quaternionf;
import org.joml.Vector3fc;
import org.joml.Vector3f;

final class PlacementController {
    private static final double ARMOR_STAND_HEAD_OFFSET = 1.44D;
    private static final double MAX_TARGET_DISTANCE = 16.0D;
    private static final double MAX_MODEL_SCALE = 20.0D;
    private static final double MAX_SCALED_TARGET_RADIUS = 12.0D;
    private static final double TARGET_SEARCH_RADIUS = MAX_TARGET_DISTANCE
        + ARMOR_STAND_HEAD_OFFSET * MAX_MODEL_SCALE;
    private static final String MODEL_TAG = "resourcegrabber_model";
    private static CustomItemEntry pendingEntry;
    private static UUID previewEntityId;
    private static PlacementState previewState;
    private static boolean commitRequested;
    private static boolean editMode;
    private static UUID editTargetId;
    private static UUID announcedEditTargetId;
    private static boolean attackWasDown;

    static void register() {
        UseBlockCallback.EVENT.register(PlacementController::useBlock);
        UseEntityCallback.EVENT.register(PlacementController::useEntity);
        AttackBlockCallback.EVENT.register(PlacementController::attackBlock);
        AttackEntityCallback.EVENT.register(PlacementController::attackEntity);
        ClientTickEvents.END_CLIENT_TICK.register(PlacementController::tickPreview);
    }

    static boolean beginEditMode() {
        Minecraft client = Minecraft.getInstance();
        if (!client.hasSingleplayerServer() || client.getSingleplayerServer() == null
            || client.player == null || client.level == null) {
            ResourceGrabberClient.notifyClient(Component.translatable(
                "message.resourcegrabber.placement_singleplayer"));
            return false;
        }
        cancel();
        editMode = true;
        editTargetId = null;
        announcedEditTargetId = null;
        client.gui.setScreen(null);
        client.gui.hud.setOverlayMessage(Component.translatable(
            "message.resourcegrabber.edit_mode"), false);
        return true;
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
        editMode = false;
        editTargetId = null;
        announcedEditTargetId = null;
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
        editMode = false;
        editTargetId = null;
        announcedEditTargetId = null;
    }

    static void update(UUID entityId, PlacementState state) {
        runOnModel(entityId, entity -> applyState(entity, state));
    }

    static void remove(UUID entityId) {
        runOnModel(entityId, Entity::discard);
    }

    static void duplicate(CustomItemEntry entry, PlacementState state) {
        duplicate(entry.createStack(false, false), state);
    }

    static void duplicate(ItemStack stack, PlacementState state) {
        spawnDisplay(stack, state.offset(0.25D, 0.0D, 0.25D), false, entityId ->
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
        boolean attackDown = client.options.keyAttack.isDown();
        if (attackDown && !attackWasDown && !editMode && pendingEntry == null
            && !client.isPaused()) {
            tryBreakGoldenHorseArmor(findGoldenHorseArmorTarget(client));
        }
        attackWasDown = attackDown;
        if (editMode) {
            tickEditTarget(client);
            return;
        }
        if (pendingEntry == null) {
            return;
        }
        if (client.player == null || client.level == null
            || client.getSingleplayerServer() == null) {
            cancel();
            return;
        }
        if (client.isPaused()) {
            cancel();
            client.gui.hud.setOverlayMessage(Component.translatable(
                "message.resourcegrabber.placement_cancelled"), false);
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
        if (world.isClientSide() && hand == InteractionHand.MAIN_HAND && editMode) {
            return selectEditTarget() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (pendingEntry == null) {
            return placeHeldGoldenHorseArmor(player, hand, hit)
                ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        previewState = stateAt(hit, player.getYRot());
        commitRequested = true;
        if (previewEntityId != null) {
            update(previewEntityId, previewState);
            finishCommit();
        }
        return InteractionResult.SUCCESS;
    }

    private static boolean placeHeldGoldenHorseArmor(
        Player player, InteractionHand hand, BlockHitResult hit
    ) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        ItemStack heldStack = player.getItemInHand(hand);
        if (server == null || client.player == null
            || !heldStack.is(Items.GOLDEN_HORSE_ARMOR)) {
            return false;
        }
        ItemStack placedStack = heldStack.copy();
        placedStack.setCount(1);
        PlacementState state = stateAt(hit, player.getYRot());
        UUID playerId = client.player.getUUID();
        boolean consume = !client.player.getAbilities().instabuild;
        spawnDisplay(placedStack, state, false, entityId -> {
            if (consume) {
                server.execute(() -> {
                    ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerId);
                    if (serverPlayer != null) {
                        ItemStack serverStack = serverPlayer.getItemInHand(hand);
                        if (serverStack.is(Items.GOLDEN_HORSE_ARMOR)) {
                            serverStack.shrink(1);
                        }
                    }
                });
            }
            String name = placedStack.getHoverName().getString();
            client.gui.setScreen(new AdvancedModelEditorScreen(
                entityId, name, placedStack, state));
        });
        return true;
    }

    private static InteractionResult useEntity(
        Player player,
        Level world,
        InteractionHand hand,
        Entity entity,
        EntityHitResult hit
    ) {
        if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND || !editMode
            || !isPlacedModel(entity)) {
            return InteractionResult.PASS;
        }
        editTargetId = entity.getUUID();
        return selectEditTarget() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    private static InteractionResult attackBlock(
        Player player,
        Level world,
        InteractionHand hand,
        BlockPos pos,
        Direction direction
    ) {
        if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND
            || editMode || pendingEntry != null) {
            return InteractionResult.PASS;
        }
        return tryBreakGoldenHorseArmor(findGoldenHorseArmorTarget(Minecraft.getInstance()))
            ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private static InteractionResult attackEntity(
        Player player,
        Level world,
        InteractionHand hand,
        Entity entity,
        EntityHitResult hit
    ) {
        if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND
            || editMode || pendingEntry != null
            || !isPlacedModel(entity)) {
            return InteractionResult.PASS;
        }
        return tryBreakGoldenHorseArmor(entity.getUUID())
            ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private static void tickEditTarget(Minecraft client) {
        if (client.player == null || client.level == null
            || client.getSingleplayerServer() == null || client.isPaused()) {
            editMode = false;
            editTargetId = null;
            return;
        }
        UUID bestId = findTarget(client);
        editTargetId = bestId;
        if (bestId != null && !bestId.equals(announcedEditTargetId)) {
            announcedEditTargetId = bestId;
            client.gui.hud.setOverlayMessage(Component.translatable(
                "message.resourcegrabber.edit_target"), false);
        }
    }

    private static UUID findTarget(Minecraft client) {
        return findTarget(client, false, 1.44D);
    }

    private static UUID findGoldenHorseArmorTarget(Minecraft client) {
        return findTarget(client, true, 4.0D);
    }

    private static UUID findTarget(
        Minecraft client, boolean goldenHorseArmorOnly, double toleranceSquared
    ) {
        if (client.player == null || client.level == null) {
            return null;
        }
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getViewVector(1.0F).normalize();
        double bestDistance = MAX_TARGET_DISTANCE;
        UUID bestId = null;
        List<Entity> candidates = new ArrayList<>();
        candidates.addAll(client.level.getEntitiesOfClass(Display.ItemDisplay.class,
            client.player.getBoundingBox().inflate(TARGET_SEARCH_RADIUS)));
        candidates.addAll(client.level.getEntitiesOfClass(ArmorStand.class,
            client.player.getBoundingBox().inflate(TARGET_SEARCH_RADIUS)));
        for (Entity entity : candidates) {
            ItemStack stack = modelStack(entity);
            if (stack.isEmpty()
                || goldenHorseArmorOnly && !stack.is(Items.GOLDEN_HORSE_ARMOR)) {
                continue;
            }
            Vec3 targetPosition = entity instanceof ArmorStand stand
                ? entity.position().add(0.0D, ARMOR_STAND_HEAD_OFFSET * stand.getScale(), 0.0D)
                : entity.position();
            Vec3 delta = targetPosition.subtract(eye);
            double distance = delta.dot(look);
            if (distance < 0.0D || distance > bestDistance) {
                continue;
            }
            double perpendicularSquared = delta.lengthSqr() - distance * distance;
            double targetRadius = Math.sqrt(toleranceSquared);
            if (entity instanceof ArmorStand stand) {
                targetRadius = Math.max(targetRadius, Math.min(MAX_SCALED_TARGET_RADIUS,
                    0.75D + stand.getScale() * 0.75D));
            }
            if (perpendicularSquared <= targetRadius * targetRadius) {
                bestDistance = distance;
                bestId = entity.getUUID();
            }
        }
        return bestId;
    }

    private static boolean tryBreakGoldenHorseArmor(UUID targetId) {
        Minecraft client = Minecraft.getInstance();
        if (targetId == null || client.level == null || client.player == null
            || client.getSingleplayerServer() == null) {
            return false;
        }
        Entity clientEntity = client.level.getEntity(targetId);
        ItemStack clientStack = modelStack(clientEntity);
        if (!clientStack.is(Items.GOLDEN_HORSE_ARMOR)) {
            return false;
        }
        MinecraftServer server = client.getSingleplayerServer();
        UUID playerId = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return;
            }
            Entity serverEntity = player.level().getEntity(targetId);
            ItemStack stack = modelStack(serverEntity).copy();
            if (!stack.is(Items.GOLDEN_HORSE_ARMOR)) {
                return;
            }
            serverEntity.discard();
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            ResourceGrabberClient.notifyClient(Component.translatable(
                "message.resourcegrabber.model_broken"));
        });
        return true;
    }

    private static boolean selectEditTarget() {
        Minecraft client = Minecraft.getInstance();
        if (editTargetId == null || client.level == null) {
            client.gui.hud.setOverlayMessage(Component.translatable(
                "message.resourcegrabber.edit_no_target"), false);
            return false;
        }
        Entity entity = client.level.getEntity(editTargetId);
        if (!isPlacedModel(entity)) {
            return false;
        }
        ItemStack stack = modelStack(entity).copy();
        PlacementState state = readState(entity);
        String name = stack.isEmpty()
            ? Component.translatable("screen.resourcegrabber.model_editor").getString()
            : stack.getHoverName().getString();
        UUID entityId = entity.getUUID();
        editMode = false;
        editTargetId = null;
        announcedEditTargetId = null;
        client.execute(() -> client.gui.setScreen(
            new AdvancedModelEditorScreen(entityId, name, stack, state)));
        return true;
    }

    private static PlacementState readState(Entity entity) {
        if (entity instanceof ArmorStand stand) {
            float scale = stand.getScale();
            Rotations head = stand.getHeadPose();
            return new PlacementState(stand.getX(),
                stand.getY() + ARMOR_STAND_HEAD_OFFSET * scale, stand.getZ(),
                head.x(), stand.getYRot(), head.z(), scale);
        }
        if (entity instanceof Display.ItemDisplay display) {
            Transformation transformation = DisplayEntityAccessor.resourcegrabber$getTransformation(
                display.getEntityData());
            Vector3fc scaleVector = transformation.scale();
            float scale = (scaleVector.x() + scaleVector.y() + scaleVector.z()) / 3.0F;
            Vector3f rotation = new Quaternionf(transformation.leftRotation())
                .getEulerAnglesXYZ(new Vector3f());
            return new PlacementState(display.getX(), display.getY(), display.getZ(),
                (float)Math.toDegrees(rotation.x),
                (float)-Math.toDegrees(rotation.y),
                (float)Math.toDegrees(rotation.z), scale);
        }
        return new PlacementState(entity.getX(), entity.getY(), entity.getZ(),
            0.0F, entity.getYRot(), 0.0F, 1.0F);
    }

    private static void finishCommit() {
        Minecraft client = Minecraft.getInstance();
        UUID entityId = previewEntityId;
        CustomItemEntry entry = pendingEntry;
        PlacementState state = previewState;
        if (entityId == null || entry == null || state == null) {
            return;
        }
        runOnModel(entityId, entity -> entity.setGlowingTag(false));
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
        spawnDisplay(entry.createStack(false, false), state, preview, onSpawned);
    }

    private static void spawnDisplay(
        ItemStack sourceStack,
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
        ItemStack stack = sourceStack.copy();
        server.execute(() -> {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerId);
            if (serverPlayer == null) {
                return;
            }
            ServerLevel serverWorld = serverPlayer.level();
            if (stack.is(Items.GOLDEN_HORSE_ARMOR)) {
                ArmorStand stand = new ArmorStand(serverWorld,
                    state.x(), state.y() - ARMOR_STAND_HEAD_OFFSET * state.scale(), state.z());
                stand.setInvisible(true);
                stand.setNoGravity(true);
                stand.setSilent(true);
                stand.setInvulnerable(true);
                ((ArmorStandEntityAccessor)stand).resourcegrabber$setMarker(true);
                stand.setItemSlot(EquipmentSlot.HEAD, stack.copy());
                stand.addTag(MODEL_TAG);
                stand.setGlowingTag(preview);
                applyState(stand, state);
                if (!serverWorld.addFreshEntity(stand)) {
                    ResourceGrabberClient.notifyClient(Component.translatable(
                        "message.resourcegrabber.placement_failed"));
                    return;
                }
                UUID entityId = stand.getUUID();
                client.execute(() -> onSpawned.accept(entityId));
                return;
            }
            Display.ItemDisplay display = new Display.ItemDisplay(
                EntityTypes.ITEM_DISPLAY, serverWorld);
            ((ItemDisplayEntityAccessor)display).resourcegrabber$setItemStack(stack.copy());
            ((ItemDisplayEntityAccessor)display).resourcegrabber$setItemDisplayContext(
                ItemDisplayContext.FIXED);
            display.setGlowingTag(preview);
            display.addTag(MODEL_TAG);
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

    private static void runOnModel(
        UUID entityId, Consumer<Entity> action
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
            if (isPlacedModel(entity)) {
                action.accept(entity);
            }
        });
    }

    private static void applyState(
        Entity entity, PlacementState state
    ) {
        if (entity instanceof ArmorStand stand) {
            var scaleAttribute = stand.getAttribute(Attributes.SCALE);
            if (scaleAttribute != null) {
                scaleAttribute.setBaseValue(state.scale());
            }
            stand.setPos(state.x(),
                state.y() - ARMOR_STAND_HEAD_OFFSET * state.scale(), state.z());
            stand.setYRot(state.rotationY());
            stand.setYBodyRot(state.rotationY());
            stand.setYHeadRot(state.rotationY());
            stand.setHeadPose(new Rotations(state.rotationX(), 0.0F, state.rotationZ()));
            return;
        }
        if (!(entity instanceof Display.ItemDisplay display)) {
            return;
        }
        display.setPos(state.x(), state.y(), state.z());
        Quaternionf rotation = new Quaternionf().rotationXYZ(
            (float)Math.toRadians(state.rotationX()),
            (float)Math.toRadians(-state.rotationY()),
            (float)Math.toRadians(state.rotationZ()));
        Vector3f scale = new Vector3f(state.scale(), state.scale(), state.scale());
        ((DisplayEntityAccessor)display).resourcegrabber$setTransformation(
            new Transformation(new Vector3f(), rotation, scale, new Quaternionf()));
    }

    private static boolean isPlacedModel(Entity entity) {
        return entity instanceof Display.ItemDisplay
            || entity instanceof ArmorStand stand
                && stand.getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HORSE_ARMOR);
    }

    private static ItemStack modelStack(Entity entity) {
        if (entity instanceof Display.ItemDisplay display) {
            return ((ItemDisplayEntityAccessor)display).resourcegrabber$getItemStack();
        }
        if (entity instanceof ArmorStand stand) {
            return stand.getItemBySlot(EquipmentSlot.HEAD);
        }
        return ItemStack.EMPTY;
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
