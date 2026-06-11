package com.moulberry.flashback.compat.tacz;

import com.moulberry.flashback.Flashback;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Injects TACZ (Timeless and Classics: Zero) gun animation state-machine triggers
 * and first-person sounds into Flashback's replay playback.
 *
 * <p>TACZ broadcasts gun state changes to nearby clients via Fabric custom-payload
 * packets (e.g. {@code tacz:s2c_gun_shoot}, {@code tacz:s2c_gundraw}, ...). These
 * packets are captured by Flashback's packet-recording hook. During replay the
 * original {@code ClientboundCustomPayloadPacket} handler still runs and posts the
 * TACZ event callbacks (which the regular TACZ code listens to), but the
 * <i>animation state-machine</i> triggers and direct sound-playback driven by
 * those packets are only fired locally for the real local player — not for the
 * Flashback spectated player.
 *
 * <p>This injector re-fires the state-machine triggers and the appropriate
 * first-person sound calls for the gun involved in each event packet, so the gun
 * model animates and the first-person sound effects play correctly during replay.
 *
 * <p>3rd-person sounds are <i>not</i> re-fired here: TACZ's own
 * {@code ServerMessageSound.handle -> SoundPlayManager.playMessageSound} already
 * plays the 3rd-person sounds for any nearby entity (including the spectated
 * player, which is a regular {@code LivingEntity} in the world during replay).
 */
public class TaczEventInjector {
    private static final Logger LOGGER = LoggerFactory.getLogger("flashback-tacz");
    private static boolean initialized = false;
    private static boolean taczPresent = false;

    // Reflection handles for animation triggering
    private static Method taczGetGunDisplay;             // TimelessAPI.getGunDisplay(ItemStack)
    private static Method taczGetAnimationStateMachine;  // GunDisplayInstance.getAnimationStateMachine()
    private static Method taczStateMachineTrigger;       // AnimationStateMachine.trigger(String)

    // Reflection handles for direct sound playback
    private static Method taczGetGunIndex;               // TimelessAPI.getClientGunIndex(ResourceLocation)
    private static Class<?> taczGunDataClass;            // GunData

    private static boolean animationMethodsResolved = false;
    private static boolean soundMethodsResolved = false;

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

            animationMethodsResolved = true;
            taczPresent = true;
        } catch (Exception e) {
            LOGGER.debug("TACZ not present (animation reflection failed): {}", e.toString());
            taczPresent = false;
        }

        try {
            Class<?> taczTimelessApi = Class.forName("com.tacz.guns.api.TimelessAPI");
            taczGetGunIndex = taczTimelessApi.getMethod("getClientGunIndex", ResourceLocation.class);
            taczGunDataClass = Class.forName("com.tacz.guns.resource.pojo.data.gun.GunData");
            soundMethodsResolved = true;
        } catch (Exception e) {
            LOGGER.debug("TACZ not present (sound reflection failed): {}", e.toString());
        }
    }

    public static boolean isTaczPresent() {
        init();
        return taczPresent;
    }

    /**
     * Handle a TACZ custom-payload packet during Flashback replay.
     *
     * <p>Always fires the state-machine trigger and the first-person sound, even
     * if no player is currently being spectated. The state machine is per
     * gun-display (a singleton per display id) so the trigger will affect the
     * right gun regardless of who the local "viewer" is. The sound is played
     * via {@code SoundPlayManager}, which uses the gun item's position
     * (or entity-tracking) to localize it.
     *
     * @param id  the packet identifier (e.g. {@code tacz:s2c_gun_shoot})
     * @param buf a {@link FriendlyByteBuf} positioned at the start of the packet data
     */
    public static void handleTaczEvent(ResourceLocation id, FriendlyByteBuf buf) {
        init();
        if (!taczPresent) return;

        String path = id.getPath();
        String animInput = mapToAnimationInput(path);
        if (animInput == null) {
            return;
        }

        // Read the entity id (always first field) and the gun item.
        // The entity id is used as the listener for the first-person sound; if the
        // entity cannot be found in the level, we fall back to the spectated
        // player (if any) or the local player, so the sound still plays.
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

        if (gunItem == null || gunItem.isEmpty()) {
            return;
        }

        // Resolve the listener entity for the first-person sound.
        LivingEntity listener = resolveListener(entityId);

        try {
            triggerAnimation(gunItem, animInput);
        } catch (Exception e) {
            LOGGER.debug("TaczEventInjector: failed to trigger animation '{}' for event '{}': {}",
                    animInput, path, e.toString());
        }

        if (listener != null) {
            try {
                playSoundForEvent(path, gunItem, listener);
            } catch (Exception e) {
                LOGGER.debug("TaczEventInjector: failed to play sound for event '{}': {}", path, e.toString());
            }
        }
    }

    private static LivingEntity resolveListener(int entityId) {
        // 1. Try to find the entity in the level by the recorded entity id
        if (net.minecraft.client.Minecraft.getInstance().level != null) {
            var entity = net.minecraft.client.Minecraft.getInstance().level.getEntity(entityId);
            if (entity instanceof LivingEntity le) {
                return le;
            }
        }
        // 2. Fall back to the spectated player (Flashback replay)
        var spectating = Flashback.getSpectatingPlayer();
        if (spectating != null) {
            return spectating;
        }
        // 3. Fall back to the real local player
        var local = net.minecraft.client.Minecraft.getInstance().player;
        return local;
    }

    private static String mapToAnimationInput(String path) {
        // Map TACZ s2c packet path -> AnimationStateMachine trigger input.
        // See com.tacz.guns.client.animation.statemachine.GunAnimationConstant.
        if (path.equals("s2c_gun_shoot"))      return "shoot";
        if (path.equals("s2c_gun_reload"))     return "reload";
        if (path.equals("s2c_gundraw"))        return "draw";
        if (path.equals("s2c_gun_melee"))      return "bayonet_muzzle";
        if (path.equals("s2c_gunfire_select")) return "fire_select";
        if (path.equals("s2c_gunfire"))        return "shoot";
        return null;
    }

    /**
     * Read the gun {@link ItemStack} from the packet payload, if it has one.
     * TACZ event packet formats:
     * <ul>
     *     <li>{@code s2c_gundraw}     : int entityId, ItemStack previousGunItem, ItemStack currentGunItem</li>
     *     <li>{@code s2c_gun_shoot}   : int shooterId, ItemStack gunItemStack</li>
     *     <li>{@code s2c_gun_reload}  : int entityId, ItemStack gunItemStack</li>
     *     <li>{@code s2c_gun_melee}   : int entityId, ItemStack gunItemStack</li>
     *     <li>{@code s2c_gunfire_select}: int entityId, ItemStack gunItemStack</li>
     *     <li>{@code s2c_gunfire}     : int shooterId, ItemStack gunItemStack, ...</li>
     * </ul>
     */
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
        if (!animationMethodsResolved) return;
        if (gunItem == null || gunItem.isEmpty()) return;

        try {
            // TimelessAPI.getGunDisplay(ItemStack) returns Optional<GunDisplayInstance>.
            Object displayOpt = taczGetGunDisplay.invoke(null, gunItem);
            if (displayOpt == null) return;
            // Unwrap the Optional: call .get() (or .orElse(null)) via reflection.
            Object display = displayOpt.getClass().getMethod("orElse", Object.class).invoke(displayOpt, (Object) null);
            if (display == null) return;

            Object stateMachine = taczGetAnimationStateMachine.invoke(display);
            if (stateMachine == null) return;

            taczStateMachineTrigger.invoke(stateMachine, animInput);
        } catch (IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
            LOGGER.debug("TaczEventInjector: failed to invoke animation '{}': {}",
                    animInput, e.toString());
        } catch (Exception e) {
            LOGGER.debug("TaczEventInjector: reflection error in triggerAnimation: {}", e.toString());
        }
    }

    /**
     * Play the first-person sound effect for the given gun event.
     */
    private static void playSoundForEvent(String path, ItemStack gunItem, LivingEntity player) {
        if (!soundMethodsResolved) return;
        if (gunItem == null || gunItem.isEmpty()) return;
        if (player == null) return;

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
        } catch (IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
            LOGGER.debug("TaczEventInjector: failed to play sound for event '{}': {}",
                    path, e.toString());
        } catch (Exception e) {
            LOGGER.debug("TaczEventInjector: reflection error in playSoundForEvent: {}", e.toString());
        }
    }
}
