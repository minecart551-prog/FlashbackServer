package com.moulberry.flashback.compat.tacz;

import com.moulberry.flashback.Flashback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Injects TACZ (Timeless and Classics: Zero) gun animation state-machine triggers
 * and first-person sounds into Flashback's replay playback.
 */
public class TaczEventInjector {
    private static final Logger LOGGER = LoggerFactory.getLogger("flashback-tacz");
    private static boolean initialized = false;
    private static boolean taczPresent = false;

    // Cached reflection handles for animation + sounds (resolved once in init())
    private static Method taczGetGunDisplay;
    private static Method taczGetAnimationStateMachine;
    private static Method taczStateMachineTrigger;
    private static Method taczGetGunIndex;
    private static Class<?> taczGunDataClass;

    // Cached handles for aim state tick callback (resolved once, reused every tick)
    private static Method aimFromLocalPlayer;
    private static Method aimGetDataHolder;
    private static Field aimIsAimingField;
    private static Field aimProgressField;
    private static Field aimOldProgressField;
    private static boolean aimFieldsResolved = false;

    // Aim state + once-only tick registration
    private static volatile Boolean pendingAimState = null;
    private static boolean tickCallbackRegistered = false;

    private TaczEventInjector() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> taczTimelessApi = Class.forName("com.tacz.guns.api.TimelessAPI");
            taczGetGunDisplay = taczTimelessApi.getMethod("getGunDisplay", ItemStack.class);

            Class<?> gunDisplay = Class.forName("com.tacz.guns.client.resource.GunDisplayInstance");
            taczGetAnimationStateMachine = gunDisplay.getMethod("getAnimationStateMachine");

            Class<?> stateMachine = Class.forName("com.tacz.guns.api.client.animation.statemachine.AnimationStateMachine");
            taczStateMachineTrigger = stateMachine.getMethod("trigger", String.class);

            taczPresent = true;
        } catch (Exception e) {
            LOGGER.debug("TACZ not present (animation reflection failed): {}", e.toString());
            taczPresent = false;
        }

        try {
            Class<?> taczTimelessApi = Class.forName("com.tacz.guns.api.TimelessAPI");
            taczGetGunIndex = taczTimelessApi.getMethod("getClientGunIndex", ResourceLocation.class);
            taczGunDataClass = Class.forName("com.tacz.guns.resource.pojo.data.gun.GunData");
        } catch (Exception e) {
            LOGGER.debug("TACZ not present (sound reflection failed): {}", e.toString());
        }
    }

    public static boolean isTaczPresent() {
        init();
        return taczPresent;
    }

    public static void handleTaczEvent(ResourceLocation id, FriendlyByteBuf buf) {
        init();
        if (!taczPresent || buf == null) return;

        String path = id.getPath();

        // Handle aim state packet
        if (path.equals("s2c_gun_aim")) {
            handleAimEvent(buf);
            return;
        }

        String animInput = mapToAnimationInput(path);
        if (animInput == null) return;

        int entityId;
        ItemStack gunItem;
        try {
            buf.markReaderIndex();
            entityId = buf.readVarInt();
            gunItem = readGunItemForEvent(buf, path);
            buf.resetReaderIndex();
        } catch (Exception e) {
            LOGGER.debug("TaczEventInjector: failed to read packet '{}': {}", path, e.toString());
            return;
        }

        if (gunItem == null || gunItem.isEmpty()) return;

        triggerAnimation(gunItem, animInput);

        LivingEntity listener = resolveListener(entityId);
        if (listener != null) {
            playSoundForEvent(path, gunItem, listener);
        }
    }

    /**
     * Handle the s2c_gun_aim packet during replay.
     * Stores the aim state and registers a tick callback that re-applies it
     * AFTER tickAimingProgress() has run (which would otherwise reset it).
     */
    private static void handleAimEvent(FriendlyByteBuf buf) {
        try {
            buf.markReaderIndex();
            boolean isAiming = buf.readBoolean();
            buf.resetReaderIndex();
            pendingAimState = isAiming;

            if (!tickCallbackRegistered) {
                tickCallbackRegistered = true;
                ClientTickEvents.END_CLIENT_TICK.register(client -> {
                    Boolean state = pendingAimState;
                    if (state == null || client.player == null) return;

                    try {
                        if (!aimFieldsResolved) {
                            resolveAimFields(client);
                        }
                        if (aimIsAimingField == null || aimProgressField == null || aimOldProgressField == null) return;

                        Object gunOperator = aimFromLocalPlayer.invoke(null, client.player);
                        if (gunOperator == null) return;

                        Object dataHolder = aimGetDataHolder.invoke(gunOperator);
                        if (dataHolder == null) return;

                        aimIsAimingField.setBoolean(dataHolder, state);
                        aimProgressField.setFloat(dataHolder, state ? 1.0f : 0.0f);
                        aimOldProgressField.set(null, state ? 1.0f : 0.0f);
                    } catch (Exception e) {
                        LOGGER.debug("TaczEventInjector: failed to apply aim state in tick: {}", e.toString());
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.debug("TaczEventInjector: failed to handle aim event: {}", e.toString());
        }
    }

    /** Resolve reflection handles ONCE and cache them for all subsequent ticks. */
    private static void resolveAimFields(net.minecraft.client.Minecraft mc) {
        try {
            Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
            aimFromLocalPlayer = gunOperatorClass.getMethod("fromLocalPlayer", mc.player.getClass());
            aimGetDataHolder = gunOperatorClass.getMethod("getDataHolder");

            Object gunOperator = aimFromLocalPlayer.invoke(null, mc.player);
            if (gunOperator == null) return;
            Object dataHolder = aimGetDataHolder.invoke(gunOperator);
            if (dataHolder == null) return;

            aimIsAimingField = dataHolder.getClass().getField("clientIsAiming");
            aimProgressField = dataHolder.getClass().getField("clientAimingProgress");
            aimOldProgressField = Class.forName("com.tacz.guns.client.gameplay.LocalPlayerDataHolder")
                    .getDeclaredField("oldAimingProgress");
            aimOldProgressField.setAccessible(true);
            aimFieldsResolved = true;
        } catch (Exception e) {
            LOGGER.debug("TaczEventInjector: failed to resolve aim fields: {}", e.toString());
        }
    }

    private static LivingEntity resolveListener(int entityId) {
        if (net.minecraft.client.Minecraft.getInstance().level != null) {
            var entity = net.minecraft.client.Minecraft.getInstance().level.getEntity(entityId);
            if (entity instanceof LivingEntity le) return le;
        }
        var spectating = Flashback.getSpectatingPlayer();
        return spectating != null ? spectating : net.minecraft.client.Minecraft.getInstance().player;
    }

    private static String mapToAnimationInput(String path) {
        if (path.equals("s2c_gun_shoot"))      return "shoot";
        if (path.equals("s2c_gun_reload"))     return "reload";
        if (path.equals("s2c_gundraw"))        return "draw";
        if (path.equals("s2c_gun_melee"))      return "bayonet_muzzle";
        if (path.equals("s2c_gunfire_select")) return "fire_select";
        if (path.equals("s2c_gunfire"))        return "shoot";
        return null;
    }

    private static ItemStack readGunItemForEvent(FriendlyByteBuf buf, String path) {
        return switch (path) {
            case "s2c_gundraw", "s2c_gun_shoot", "s2c_gun_reload", "s2c_gun_melee",
                 "s2c_gunfire_select", "s2c_gunfire" -> {
                buf.readVarInt();
                yield buf.readItem();
            }
            default -> ItemStack.EMPTY;
        };
    }

    private static void triggerAnimation(ItemStack gunItem, String animInput) {
        if (taczGetGunDisplay == null || taczStateMachineTrigger == null) return;
        if (gunItem == null || gunItem.isEmpty()) return;

        try {
            Object displayOpt = taczGetGunDisplay.invoke(null, gunItem);
            if (displayOpt == null) return;
            Object display = displayOpt.getClass().getMethod("orElse", Object.class).invoke(displayOpt, (Object) null);
            if (display == null) return;

            Object stateMachine = taczGetAnimationStateMachine.invoke(display);
            if (stateMachine == null) return;

            taczStateMachineTrigger.invoke(stateMachine, animInput);
        } catch (Exception e) {
            LOGGER.debug("TaczEventInjector: failed to trigger animation '{}': {}", animInput, e.toString());
        }
    }

    private static void playSoundForEvent(String path, ItemStack gunItem, LivingEntity player) {
        if (taczGetGunIndex == null || taczGetGunDisplay == null) return;
        if (gunItem == null || gunItem.isEmpty() || player == null) return;

        try {
            Class<?> taczIGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            Method iGunGetGunId = taczIGunClass.getMethod("getGunId", ItemStack.class);
            ResourceLocation gunId = (ResourceLocation) iGunGetGunId.invoke(null, gunItem);
            if (gunId == null) return;

            Object gunIndex = taczGetGunIndex.invoke(null, gunId);
            if (gunIndex == null) return;

            Class<?> soundPlayManager = Class.forName("com.tacz.guns.client.sound.SoundPlayManager");
            Class<?> livingEntityClass = Class.forName("net.minecraft.world.entity.LivingEntity");
            Class<?> gunDisplayInstanceClass = Class.forName("com.tacz.guns.client.resource.GunDisplayInstance");

            Object displayOpt2 = taczGetGunDisplay.invoke(null, gunItem);
            if (displayOpt2 == null) return;
            Object display = displayOpt2.getClass().getMethod("orElse", Object.class).invoke(displayOpt2, (Object) null);
            if (display == null) return;

            switch (path) {
                case "s2c_gun_shoot", "s2c_gunfire" -> {
                    Method getGunData = gunIndex.getClass().getMethod("getGunData");
                    Object gunData = getGunData.invoke(gunIndex);
                    if (gunData == null) return;
                    Method playShootSound = soundPlayManager.getMethod("playShootSound",
                            livingEntityClass, gunDisplayInstanceClass, taczGunDataClass);
                    playShootSound.invoke(null, player, display, gunData);
                }
                case "s2c_gun_reload" -> {
                    Method playReloadSound = soundPlayManager.getMethod("playReloadSound",
                            livingEntityClass, gunDisplayInstanceClass, boolean.class);
                    playReloadSound.invoke(null, player, display, false);
                }
                case "s2c_gundraw" -> {
                    Method playDrawSound = soundPlayManager.getMethod("playDrawSound",
                            livingEntityClass, gunDisplayInstanceClass);
                    playDrawSound.invoke(null, player, display);
                }
                case "s2c_gun_melee" -> {
                    Method playMeleeSound = soundPlayManager.getMethod("playMeleeBayonetSound",
                            livingEntityClass, gunDisplayInstanceClass);
                    playMeleeSound.invoke(null, player, display);
                }
                case "s2c_gunfire_select" -> {
                    Method playFireSelectSound = soundPlayManager.getMethod("playFireSelectSound",
                            livingEntityClass, gunDisplayInstanceClass);
                    playFireSelectSound.invoke(null, player, display);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("TaczEventInjector: failed to play sound for event '{}': {}", path, e.toString());
        }
    }
}