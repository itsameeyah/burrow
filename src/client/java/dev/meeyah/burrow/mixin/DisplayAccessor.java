package dev.meeyah.burrow.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Display.class)
public interface DisplayAccessor {
    @Accessor("DATA_TRANSLATION_ID")
    static EntityDataAccessor<Vector3fc> burrow$translationId() {
        throw new AssertionError();
    }

    @Accessor("DATA_SCALE_ID")
    static EntityDataAccessor<Vector3fc> burrow$scaleId() {
        throw new AssertionError();
    }
}
