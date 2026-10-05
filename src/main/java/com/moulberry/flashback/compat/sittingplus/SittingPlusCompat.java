package com.moulberry.flashback.compat.sittingplus;

import com.moulberry.flashback.Flashback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SittingPlus lowers the camera by 0.7 blocks when the local player sits, but only does so when its
 * own private {@code sitAnimationPlayer} field is set - which never happens in a replay, since the
 * local player never presses the sit key. This mirrors that behaviour for whoever the replay camera
 * is attached to, so sitting players look the same in first and third person as they do in gameplay.
 */
public class SittingPlusCompat {

    private static final double CAMERA_LOWER_AMOUNT = 0.7;

    /**
     * Written from the replay server thread while packets are replayed, read from the render thread.
     */
    private static final Set<UUID> SITTING = ConcurrentHashMap.newKeySet();

    public static void track(ResourceLocation identifier, FriendlyByteBuf data) {
        if (!identifier.getNamespace().equals("sittingplus")) {
            return;
        }

        try {
            if (identifier.getPath().equals("start_sit")) {
                SITTING.add(data.readUUID());
            } else if (identifier.getPath().equals("stop_sit")) {
                SITTING.remove(data.readUUID());
            }
        } catch (Exception ignored) {
        }
    }

    public static void clear() {
        SITTING.clear();
    }

    public static void register() {
        WorldRenderEvents.START.register(context -> {
            if (!Flashback.isInReplay()) {
                return;
            }

            Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            Entity entity = camera.getEntity();
            if (entity == null || !SITTING.contains(entity.getUUID())) {
                return;
            }

            context.matrixStack().translate(0, CAMERA_LOWER_AMOUNT, 0);
        });
    }
}
