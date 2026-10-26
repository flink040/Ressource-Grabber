package de.opsucht.resourcegrabber.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayEntityAccessor {
    @Invoker("getItemStack")
    ItemStack resourcegrabber$getItemStack();

    @Invoker("setItemStack")
    void resourcegrabber$setItemStack(ItemStack stack);

    @Invoker("setItemTransform")
    void resourcegrabber$setItemDisplayContext(ItemDisplayContext context);
}
