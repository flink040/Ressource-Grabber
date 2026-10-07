package de.opsucht.resourcegrabber;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;

public final class ChestShopScanner {
    private static final double ENTITY_SCAN_RADIUS = 24.0D;
    private static final int SIGN_SCAN_RADIUS = 4;

    public static LearnedItemNames.LearningResult scan(
        Minecraft client, String serverName
    ) {
        if (client.player == null || client.level == null) {
            return LearnedItemNames.LearningResult.NONE;
        }
        LearnedItemNames.LearningResult learned = LearnedItemNames.LearningResult.NONE;
        List<Entity> nearby = client.level.getEntities(client.player,
            client.player.getBoundingBox().inflate(ENTITY_SCAN_RADIUS));
        for (Entity entity : nearby) {
            if (entity instanceof Display.ItemDisplay display) {
                learned = learned.merge(observeHologram(
                    client, serverName, entity, display.getItemStack()));
            } else if (entity instanceof ItemEntity itemEntity) {
                learned = learned.merge(observeHologram(
                    client, serverName, entity, itemEntity.getItem()));
            } else if (entity instanceof ArmorStand armorStand) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    learned = learned.merge(observeHologram(
                        client, serverName, entity, armorStand.getItemBySlot(slot)));
                }
            }
        }
        return learned;
    }

    private static LearnedItemNames.LearningResult observeHologram(
        Minecraft client, String serverName, Entity entity, ItemStack stack
    ) {
        if (stack.isEmpty()) {
            return LearnedItemNames.LearningResult.NONE;
        }
        String signLabel = findNearestShopLabel(client, entity.blockPosition());
        if (signLabel != null) {
            return LearnedItemNames.observe(serverName, stack, signLabel);
        }
        return LearnedItemNames.LearningResult.NONE;
    }

    private static String findNearestShopLabel(Minecraft client, BlockPos center) {
        String nearestLabel = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
            center.offset(-SIGN_SCAN_RADIUS, -SIGN_SCAN_RADIUS, -SIGN_SCAN_RADIUS),
            center.offset(SIGN_SCAN_RADIUS, SIGN_SCAN_RADIUS, SIGN_SCAN_RADIUS))) {
            BlockEntity blockEntity = client.level.getBlockEntity(pos);
            if (!(blockEntity instanceof SignBlockEntity sign)) {
                continue;
            }
            String label = shopLabel(sign.getFrontText().getMessages(false));
            if (label == null) {
                label = shopLabel(sign.getBackText().getMessages(false));
            }
            double distance = pos.distSqr(center);
            if (label != null && distance < nearestDistance) {
                nearestLabel = label;
                nearestDistance = distance;
            }
        }
        return nearestLabel;
    }

    private static String shopLabel(Component[] lines) {
        if (lines.length < 4) {
            return null;
        }
        List<String> text = new ArrayList<>(4);
        for (Component line : lines) {
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
