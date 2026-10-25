package de.opsucht.resourcegrabber.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DisplayEntity.ItemDisplayEntity.class)
public interface ItemDisplayEntityAccessor {
    @Invoker("getItemStack")
    ItemStack resourcegrabber$getItemStack();

    @Invoker("setItemStack")
    void resourcegrabber$setItemStack(ItemStack stack);

    @Invoker("setItemDisplayContext")
    void resourcegrabber$setItemDisplayContext(ItemDisplayContext context);
}
