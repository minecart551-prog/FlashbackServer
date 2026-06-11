package com.moulberry.flashback.compat.tacz;

import com.moulberry.flashback.Flashback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Registers Flashback as a recording-compat provider for TACZ.
 *
 * <p>This redirects TACZ's first-person gun rendering and animation ticking to use
 * the Flashback spectated player (if any) instead of {@code Minecraft.getInstance().player}.
 * The provider is only registered when both Flashback <i>and</i> TACZ are present.
 */
public final class TaczRecordingCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("flashback-tacz");

    private TaczRecordingCompat() {}

    public static void tryRegister() {
        if (!FabricLoader.getInstance().isModLoaded("tacz")) {
            return;
        }
        try {
            Class<?> recordingCompatClass = Class.forName("com.tacz.guns.api.client.IRecordingCompat");
            // Look up the static register(IRecordingCompat) method
            Method register = recordingCompatClass.getMethod("register", recordingCompatClass);

            // Create a dynamic implementation of IRecordingCompat by reflection (so we
            // don't have a compile-time dependency on TACZ).
            Object provider = java.lang.reflect.Proxy.newProxyInstance(
                    recordingCompatClass.getClassLoader(),
                    new Class<?>[]{recordingCompatClass},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getViewPlayer")
                                && method.getParameterCount() == 0) {
                            return getViewPlayer();
                        }
                        // Default: for any other method, return default value
                        Class<?> returnType = method.getReturnType();
                        if (returnType == boolean.class) return false;
                        if (returnType == int.class) return 0;
                        if (returnType == long.class) return 0L;
                        if (returnType == double.class) return 0.0;
                        if (returnType == float.class) return 0f;
                        if (returnType == short.class) return (short) 0;
                        if (returnType == byte.class) return (byte) 0;
                        if (returnType == char.class) return (char) 0;
                        return null;
                    });

            register.invoke(null, provider);
            LOGGER.info("Registered Flashback as a TACZ recording-compat provider");
        } catch (Exception e) {
            LOGGER.debug("Failed to register Flashback as a TACZ recording-compat provider: {}", e.toString());
        }
    }

    /**
     * Returns the Flashback spectated player (if in replay) or {@code null} to indicate
     * "use the regular local player". We only return a value when we are actually
     * spectating a player, so normal gameplay (and the editor when not spectating) is
     * unaffected.
     */
    @Nullable
    private static Player getViewPlayer() {
        AbstractClientPlayer spectatingPlayer = Flashback.getSpectatingPlayer();
        return spectatingPlayer;
    }
}
