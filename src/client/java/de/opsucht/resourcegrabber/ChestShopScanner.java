package de.opsucht.resourcegrabber;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public final class ChestShopScanner {
    private static final double ENTITY_SCAN_RADIUS = 24.0D;
    private static final int SIGN_SCAN_RADIUS = 4;

    public static void scan(MinecraftClient client, String serverName) {
        if (client.player == null || client.world == null) {
            return;
        }
        List<Entity> nearby = client.world.getOtherEntities(client.player,
            client.player.getBoundingBox().expand(ENTITY_SCAN_RADIUS));
        for (Entity entity : nearby) {
            if (entity instanceof DisplayEntity.ItemDisplayEntity display) {
                observeHologram(client, serverName, entity, display.getItemStack());
            } else if (entity instanceof ItemEntity itemEntity) {
                observeHologram(client, serverName, entity, itemEntity.getStack());
            } else if (entity instanceof ArmorStandEntity armorStand) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    observeHologram(client, serverName, entity,
                        armorStand.getEquippedStack(slot));
                }
            }
        }
    }

    private static void observeHologram(
        MinecraftClient client, String serverName, Entity entity, ItemStack stack
    ) {
        if (stack.isEmpty()) {
            return;
        }
        String signLabel = findNearestShopLabel(client, entity.getBlockPos());
        if (signLabel != null) {
            LearnedItemNames.observe(serverName, stack, signLabel);
        }
    }

    private static String findNearestShopLabel(MinecraftClient client, BlockPos center) {
        String nearestLabel = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(
            center.add(-SIGN_SCAN_RADIUS, -SIGN_SCAN_RADIUS, -SIGN_SCAN_RADIUS),
            center.add(SIGN_SCAN_RADIUS, SIGN_SCAN_RADIUS, SIGN_SCAN_RADIUS))) {
            BlockEntity blockEntity = client.world.getBlockEntity(pos);
            if (!(blockEntity instanceof SignBlockEntity sign)) {
                continue;
            }
            String label = shopLabel(sign.getFrontText().getMessages(false));
            if (label == null) {
                label = shopLabel(sign.getBackText().getMessages(false));
            }
            double distance = pos.getSquaredDistance(center);
            if (label != null && distance < nearestDistance) {
                nearestLabel = label;
                nearestDistance = distance;
            }
        }
        return nearestLabel;
    }

    private static String shopLabel(Text[] lines) {
        if (lines.length < 4) {
            return null;
        }
        List<String> text = new ArrayList<>(4);
        for (Text line : lines) {
            text.add(line.getString().strip());
        }
        String amount = text.get(1);
        String transaction = text.get(2).toUpperCase(java.util.Locale.ROOT);
        String item = text.get(3);
        boolean hasAmount = amount.chars().anyMatch(Character::isDigit);
        boolean hasTransaction = transaction.indexOf('B') >= 0
            || transaction.indexOf('S') >= 0;
        return hasAmount && hasTransaction && !item.isBlank() ? item : null;
    }

    private ChestShopScanner() {
    }
}
