package de.opsucht.resourcegrabber;

import de.opsucht.resourcegrabber.mixin.ArmorStandEntityAccessor;
import de.opsucht.resourcegrabber.mixin.DisplayEntityAccessor;
import de.opsucht.resourcegrabber.mixin.ItemDisplayEntityAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.EulerAngle;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3fc;
import org.joml.Vector3f;

final class PlacementController {
    private static final double ARMOR_STAND_HEAD_OFFSET = 1.44D;
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isInSingleplayer() || client.getServer() == null
            || client.player == null || client.world == null) {
            ResourceGrabberClient.notifyClient(Text.translatable(
                "message.resourcegrabber.placement_singleplayer"));
            return false;
        }
        cancel();
        editMode = true;
        editTargetId = null;
        announcedEditTargetId = null;
        client.setScreen(null);
        client.inGameHud.setOverlayMessage(Text.translatable(
            "message.resourcegrabber.edit_mode"), false);
        return true;
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
        boolean attackDown = client.options.attackKey.isPressed();
        if (attackDown && !attackWasDown && !editMode && pendingEntry == null
            && client.currentScreen == null) {
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
        if (world.isClient() && hand == Hand.MAIN_HAND && editMode) {
            return selectEditTarget() ? ActionResult.SUCCESS : ActionResult.FAIL;
        }
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

    private static ActionResult useEntity(
        net.minecraft.entity.player.PlayerEntity player,
        net.minecraft.world.World world,
        Hand hand,
        Entity entity,
        EntityHitResult hit
    ) {
        if (!world.isClient() || hand != Hand.MAIN_HAND || !editMode
            || !isPlacedModel(entity)) {
            return ActionResult.PASS;
        }
        editTargetId = entity.getUuid();
        return selectEditTarget() ? ActionResult.SUCCESS : ActionResult.FAIL;
    }

    private static ActionResult attackBlock(
        net.minecraft.entity.player.PlayerEntity player,
        net.minecraft.world.World world,
        Hand hand,
        BlockPos pos,
        Direction direction
    ) {
        if (!world.isClient() || hand != Hand.MAIN_HAND
            || editMode || pendingEntry != null) {
            return ActionResult.PASS;
        }
        return tryBreakGoldenHorseArmor(findGoldenHorseArmorTarget(MinecraftClient.getInstance()))
            ? ActionResult.SUCCESS : ActionResult.PASS;
    }

    private static ActionResult attackEntity(
        net.minecraft.entity.player.PlayerEntity player,
        net.minecraft.world.World world,
        Hand hand,
        Entity entity,
        EntityHitResult hit
    ) {
        if (!world.isClient() || hand != Hand.MAIN_HAND
            || editMode || pendingEntry != null
            || !isPlacedModel(entity)) {
            return ActionResult.PASS;
        }
        return tryBreakGoldenHorseArmor(entity.getUuid())
            ? ActionResult.SUCCESS : ActionResult.PASS;
    }

    private static void tickEditTarget(MinecraftClient client) {
        if (client.player == null || client.world == null || client.getServer() == null
            || client.currentScreen != null) {
            editMode = false;
            editTargetId = null;
            return;
        }
        UUID bestId = findTarget(client);
        editTargetId = bestId;
        if (bestId != null && !bestId.equals(announcedEditTargetId)) {
            announcedEditTargetId = bestId;
            client.inGameHud.setOverlayMessage(Text.translatable(
                "message.resourcegrabber.edit_target"), false);
        }
    }

    private static UUID findTarget(MinecraftClient client) {
        return findTarget(client, false, 1.44D);
    }

    private static UUID findGoldenHorseArmorTarget(MinecraftClient client) {
        return findTarget(client, true, 4.0D);
    }

    private static UUID findTarget(
        MinecraftClient client, boolean goldenHorseArmorOnly, double toleranceSquared
    ) {
        if (client.player == null || client.world == null) {
            return null;
        }
        Vec3d eye = client.player.getEyePos();
        Vec3d look = client.player.getRotationVec(1.0F).normalize();
        double bestDistance = 8.0D;
        UUID bestId = null;
        List<Entity> candidates = new ArrayList<>();
        candidates.addAll(client.world.getEntitiesByClass(
            DisplayEntity.ItemDisplayEntity.class,
            client.player.getBoundingBox().expand(8.0D), entity -> true));
        candidates.addAll(client.world.getEntitiesByClass(ArmorStandEntity.class,
            client.player.getBoundingBox().expand(8.0D), entity -> true));
        for (Entity entity : candidates) {
            ItemStack stack = modelStack(entity);
            if (stack.isEmpty()
                || goldenHorseArmorOnly && !stack.isOf(Items.GOLDEN_HORSE_ARMOR)) {
                continue;
            }
            float scale = entity instanceof ArmorStandEntity stand ? stand.getScale() : 1.0F;
            Vec3d targetPosition = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
            if (entity instanceof ArmorStandEntity) {
                targetPosition = targetPosition.add(0.0D,
                    ARMOR_STAND_HEAD_OFFSET * scale, 0.0D);
            }
            Vec3d delta = targetPosition.subtract(eye);
            double distance = delta.dotProduct(look);
            if (distance < 0.0D || distance > bestDistance) {
                continue;
            }
            double perpendicularSquared = delta.lengthSquared() - distance * distance;
            if (perpendicularSquared <= toleranceSquared) {
                bestDistance = distance;
                bestId = entity.getUuid();
            }
        }
        return bestId;
    }

    private static boolean tryBreakGoldenHorseArmor(UUID targetId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (targetId == null || client.world == null || client.player == null
            || client.getServer() == null) {
            return false;
        }
        Entity clientEntity = client.world.getEntity(targetId);
        ItemStack clientStack = modelStack(clientEntity);
        if (!clientStack.isOf(Items.GOLDEN_HORSE_ARMOR)) {
            return false;
        }
        MinecraftServer server = client.getServer();
        UUID playerId = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                return;
            }
            Entity serverEntity = player.getEntityWorld().getEntity(targetId);
            ItemStack stack = modelStack(serverEntity).copy();
            if (!stack.isOf(Items.GOLDEN_HORSE_ARMOR)) {
                return;
            }
            serverEntity.discard();
            if (!player.getInventory().insertStack(stack)) {
                player.dropItem(stack, false);
            }
            ResourceGrabberClient.notifyClient(Text.translatable(
                "message.resourcegrabber.model_broken"));
        });
        return true;
    }

    private static boolean selectEditTarget() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (editTargetId == null || client.world == null) {
            client.inGameHud.setOverlayMessage(Text.translatable(
                "message.resourcegrabber.edit_no_target"), false);
            return false;
        }
        Entity entity = client.world.getEntity(editTargetId);
        if (!isPlacedModel(entity)) {
            return false;
        }
        ItemStack stack = modelStack(entity).copy();
        PlacementState state = readState(entity);
        String name = stack.isEmpty()
            ? Text.translatable("screen.resourcegrabber.model_editor").getString()
            : stack.getName().getString();
        UUID entityId = entity.getUuid();
        editMode = false;
        editTargetId = null;
        announcedEditTargetId = null;
        client.execute(() -> client.setScreen(
            new AdvancedModelEditorScreen(entityId, name, stack, state)));
        return true;
    }

    private static PlacementState readState(Entity entity) {
        if (entity instanceof ArmorStandEntity stand) {
            float scale = stand.getScale();
            EulerAngle head = stand.getHeadRotation();
            return new PlacementState(stand.getX(),
                stand.getY() + ARMOR_STAND_HEAD_OFFSET * scale, stand.getZ(),
                head.pitch(), stand.getYaw(), head.roll(), scale);
        }
        if (entity instanceof DisplayEntity.ItemDisplayEntity display) {
            AffineTransformation transformation = DisplayEntityAccessor
                .resourcegrabber$getTransformation(display.getDataTracker());
            Vector3fc scaleVector = transformation.getScale();
            float scale = (scaleVector.x() + scaleVector.y() + scaleVector.z()) / 3.0F;
            Vector3f rotation = new Quaternionf(transformation.getLeftRotation())
                .getEulerAnglesXYZ(new Vector3f());
            return new PlacementState(display.getX(), display.getY(), display.getZ(),
                (float)Math.toDegrees(rotation.x),
                (float)-Math.toDegrees(rotation.y),
                (float)Math.toDegrees(rotation.z), scale);
        }
        return new PlacementState(entity.getX(), entity.getY(), entity.getZ(),
            0.0F, entity.getYaw(), 0.0F, 1.0F);
    }

    private static void finishCommit() {
        MinecraftClient client = MinecraftClient.getInstance();
        UUID entityId = previewEntityId;
        CustomItemEntry entry = pendingEntry;
        PlacementState state = previewState;
        if (entityId == null || entry == null || state == null) {
            return;
        }
        runOnModel(entityId, entity -> entity.setGlowing(false));
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
        spawnDisplay(entry.createStack(false, false), state, preview, onSpawned);
    }

    private static void spawnDisplay(
        ItemStack sourceStack,
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
        ItemStack stack = sourceStack.copy();
        server.execute(() -> {
            ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerId);
            if (serverPlayer == null) {
                return;
            }
            ServerWorld serverWorld = serverPlayer.getEntityWorld();
            if (stack.isOf(Items.GOLDEN_HORSE_ARMOR)) {
                ArmorStandEntity stand = new ArmorStandEntity(serverWorld,
                    state.x(), state.y() - ARMOR_STAND_HEAD_OFFSET * state.scale(), state.z());
                stand.setInvisible(true);
                stand.setNoGravity(true);
                stand.setSilent(true);
                stand.setInvulnerable(true);
                ((ArmorStandEntityAccessor)stand).resourcegrabber$setMarker(true);
                stand.equipStack(EquipmentSlot.HEAD, stack.copy());
                stand.addCommandTag(MODEL_TAG);
                stand.setGlowing(preview);
                applyState(stand, state);
                if (!serverWorld.spawnEntity(stand)) {
                    ResourceGrabberClient.notifyClient(Text.translatable(
                        "message.resourcegrabber.placement_failed"));
                    return;
                }
                UUID entityId = stand.getUuid();
                client.execute(() -> onSpawned.accept(entityId));
                return;
            }
            DisplayEntity.ItemDisplayEntity display = new DisplayEntity.ItemDisplayEntity(
                EntityType.ITEM_DISPLAY, serverWorld);
            ((ItemDisplayEntityAccessor)display).resourcegrabber$setItemStack(stack.copy());
            ((ItemDisplayEntityAccessor)display).resourcegrabber$setItemDisplayContext(
                ItemDisplayContext.FIXED);
            display.setGlowing(preview);
            display.addCommandTag(MODEL_TAG);
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

    private static void runOnModel(
        UUID entityId, Consumer<Entity> action
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
            if (isPlacedModel(entity)) {
                action.accept(entity);
            }
        });
    }

    private static void applyState(
        Entity entity, PlacementState state
    ) {
        if (entity instanceof ArmorStandEntity stand) {
            var scaleAttribute = stand.getAttributeInstance(EntityAttributes.SCALE);
            if (scaleAttribute != null) {
                scaleAttribute.setBaseValue(state.scale());
            }
            stand.setPosition(state.x(),
                state.y() - ARMOR_STAND_HEAD_OFFSET * state.scale(), state.z());
            stand.setYaw(state.rotationY());
            stand.setBodyYaw(state.rotationY());
            stand.setHeadYaw(state.rotationY());
            stand.setHeadRotation(new EulerAngle(
                state.rotationX(), 0.0F, state.rotationZ()));
            return;
        }
        if (!(entity instanceof DisplayEntity.ItemDisplayEntity display)) {
            return;
        }
        display.setPosition(state.x(), state.y(), state.z());
        Quaternionf rotation = new Quaternionf().rotationXYZ(
            (float)Math.toRadians(state.rotationX()),
            (float)Math.toRadians(-state.rotationY()),
            (float)Math.toRadians(state.rotationZ()));
        Vector3f scale = new Vector3f(state.scale(), state.scale(), state.scale());
        ((DisplayEntityAccessor)display).resourcegrabber$setTransformation(
            new AffineTransformation(new Vector3f(), rotation, scale, new Quaternionf()));
    }

    private static boolean isPlacedModel(Entity entity) {
        return entity instanceof DisplayEntity.ItemDisplayEntity
            || entity instanceof ArmorStandEntity stand
                && stand.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.GOLDEN_HORSE_ARMOR);
    }

    private static ItemStack modelStack(Entity entity) {
        if (entity instanceof DisplayEntity.ItemDisplayEntity display) {
            return ((ItemDisplayEntityAccessor)display).resourcegrabber$getItemStack();
        }
        if (entity instanceof ArmorStandEntity stand) {
            return stand.getEquippedStack(EquipmentSlot.HEAD);
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
                && MathHelper.approximatelyEquals((float)this.x, (float)other.x)
                && MathHelper.approximatelyEquals((float)this.y, (float)other.y)
                && MathHelper.approximatelyEquals((float)this.z, (float)other.z)
                && MathHelper.approximatelyEquals(this.rotationY, other.rotationY);
        }
    }

    private PlacementController() {
    }
}
