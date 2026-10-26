package de.opsucht.resourcegrabber.mixin;

import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ArmorStand.class)
public interface ArmorStandEntityAccessor {
    @Invoker("setMarker")
    void resourcegrabber$setMarker(boolean marker);
}
