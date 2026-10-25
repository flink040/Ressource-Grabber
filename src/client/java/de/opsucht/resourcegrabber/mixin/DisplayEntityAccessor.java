package de.opsucht.resourcegrabber.mixin;

import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DisplayEntity.class)
public interface DisplayEntityAccessor {
    @Invoker("getTransformation")
    static AffineTransformation resourcegrabber$getTransformation(DataTracker dataTracker) {
        throw new AssertionError();
    }

    @Invoker("setTransformation")
    void resourcegrabber$setTransformation(AffineTransformation transformation);
}
