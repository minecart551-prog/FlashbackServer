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
    private static Method taczStateMachineIsInitialized;
    private static Method taczStateMachineSetContext;
    private static Method taczStateMachineInitialize;
    private static Method taczGetGunIndex;
    private static Method taczGetCommonGunIndex;
    private static Method taczGetGunData;
    private static Method taczGetAimTime;
    private static Class<?> taczGunDataClass;

    // Cached handles for aim state tick callback (resolved once, reused every tick)
    private static Method aimFromLocalPlayer;
    private static Method aimGetDataHolder;
    private static Field aimIsAimingField;
    private static Field aimProgressField;
    private static Field aimOldProgressField;
    private static Field aimTimestampField;
    private static boolean aimFieldsResolved = false;

    // Aim state + once-only tick registration
    private static volatile Boolean pendingAimState = null;
    private static boolean tickCallbackRegistered = false;
    private static long lastAimChangeTimestamp = -1;
    private static boolean lastAimState = false;

    // Track spectating player to detect player switches during replay
    private static int lastSpectatingPlayerId = -1;

    // Cached handles for reading synced entity data (IS_AIMING_KEY / AIMING_PROGRESS_KEY)
    private static Object syncedEntityDataInstance;
    private static Method syncedEntityDataGet;
    private static Object isAimingKeyInstance;
    private static Object aimingProgressKeyInstance;
    private static boolean syncedAimKeysResolved = false;

    // Cached handles for positioning state reset on weapon switch
    private static boolean positioningFieldsResolved = false;
    private static Field posCurrentViewIndex;
    private static Field posOldViewIndex;
    private static Field posOldAimingViewMatrix;
    private static Field posSwitchViewDynamics;
    private static Field posShootTimeStamp;
    private static Field posJumpingSwayProgress;
    private static Field posJumpingTimeStamp;
    private static Field posLastOnGround;
    // SecondOrderDynamics internal state fields (for drift correction)
    private static Field dynPy;
    private static Field dynPyd;
    private static Field dynPx;
    private static Field dynTarget;
    private static boolean dynamicsFieldsResolved = false;
    // Static dynamics instances in FirstPersonRenderGunEvent
    private static Field posAimingDynamics;
    private static Field posRefitOpeningDynamics;
    private static Field posJumpingDynamics;

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
            taczStateMachineIsInitialized = stateMachine.getMethod("isInitialized");
            taczStateMachineSetContext = stateMachine.getMethod("setContext", Class.forName("com.tacz.guns.api.client.animation.statemachine.AnimationStateContext"));
            taczStateMachineInitialize = stateMachine.getMethod("initialize");

            taczPresent = true;
        } catch (Exception e) {
            LOGGER.warn("TACZ not present (animation reflection failed): {}", e.toString());
            taczPresent = false;
        }

        try {
            Class<?> taczTimelessApi = Class.forName("com.tacz.guns.api.TimelessAPI");
            taczGetGunIndex = taczTimelessApi.getMethod("getClientGunIndex", ResourceLocation.class);
            taczGetCommonGunIndex = taczTimelessApi.getMethod("getCommonGunIndex", ResourceLocation.class);
            Class<?> commonGunIndexClass = Class.forName("com.tacz.guns.resource.index.CommonGunIndex");
            taczGetGunData = commonGunIndexClass.getMethod("getGunData");
            taczGunDataClass = Class.forName("com.tacz.guns.resource.pojo.data.gun.GunData");
            taczGetAimTime = taczGunDataClass.getMethod("getAimTime");
        } catch (Exception e) {
            LOGGER.warn("TACZ not present (sound reflection failed): {}", e.toString());
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
        LOGGER.debug("TaczEventInjector: received packet '{}'", path);

        // Handle aim state packet
        if (path.equals("s2c_gun_aim")) {
            handleAimEvent(buf);
            return;
        }

        // For all other TACZ packets, ensure the aim tick callback is registered.
        // This handles the case where aim state arrives via s2c_update_entity_data
        // instead of a dedicated s2c_gun_aim packet.
        registerAimTickCallbackIfNeeded();

        // Skip s2c_gundraw animation here — forward() sends this packet to the client
        // where TACZ's own ServerMessageGunDraw handler fires GunDrawEvent, which
        // triggers the draw animation via PlayerAnimator. Triggering it here too causes
        // a double-draw. Sound is NOT handled by TACZ's GunDrawEvent, so we still
        // play it below.

        String animInput = mapToAnimationInput(path);
        boolean skipAnimation = path.equals("s2c_gundraw");
        if (animInput == null && !skipAnimation) return;

        int entityId;
        ItemStack gunItem;
        try {
            buf.markReaderIndex();
            entityId = buf.readVarInt();
            gunItem = readGunItemForEvent(buf, path);
            buf.resetReaderIndex();
        } catch (Exception e) {
            LOGGER.warn("TaczEventInjector: failed to read packet '{}': {}", path, e.toString());
            return;
        }

        if (gunItem == null || gunItem.isEmpty()) {
            LOGGER.warn("TaczEventInjector: gunItem is empty for '{}' (entityId={})", path, entityId);
            return;
        }

        LOGGER.debug("TaczEventInjector: processing '{}' entityId={} gun={}", path, entityId, gunItem);
        if (!skipAnimation) {
            triggerAnimation(gunItem, animInput);
        }

        // Reset positioning state on weapon switch to prevent stale scope/sway interpolation
        if (path.equals("s2c_gundraw")) {
            resetPositioningState();
        }

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
            lastAimChangeTimestamp = System.currentTimeMillis();
            lastAimState = isAiming;
            registerAimTickCallbackIfNeeded();
        } catch (Exception e) {
            LOGGER.warn("TaczEventInjector: failed to handle aim event: {}", e.toString());
        }
    }

    /**
     * Register the aim tick callback if not already registered.
     * During replay, there is no s2c_gun_aim packet (aim is C2S only).
     * Instead, the aim state is synced via s2c_update_entity_data packets
     * which update TACZ's SyncedEntityData. We read from there each tick
     * and bridge to LocalPlayerDataHolder for first-person rendering.
     */
    private static void registerAimTickCallbackIfNeeded() {
        if (tickCallbackRegistered) return;
        tickCallbackRegistered = true;

        try {
            resolveSyncedAimKeys();
        } catch (Exception e) {
            LOGGER.warn("TaczEventInjector: failed to resolve synced aim keys: {}", e.toString());
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            try {
                if (!aimFieldsResolved) {
                    resolveAimFields(client);
                }
                if (aimIsAimingField == null) return;

                // Read aim state from the SPECTATING player entity (not the local player),
                // because during replay the local player holds nothing — the spectating player
                // is the one whose aim state is synced via s2c_update_entity_data packets.
                net.minecraft.world.entity.player.Player viewPlayer = Flashback.getSpectatingPlayer();
                if (viewPlayer == null) return;

                // Reset positioning dynamics when spectating player changes (prevents drift)
                int viewPlayerId = viewPlayer.getId();
                if (viewPlayerId != lastSpectatingPlayerId) {
                    lastSpectatingPlayerId = viewPlayerId;
                    resetPositioningState();
                }

                if (!aimFieldsResolved) return;
                Boolean syncedAiming = readSyncedAimState(viewPlayer);

                Boolean state = pendingAimState;
                if (syncedAiming != null) {
                    state = syncedAiming;
                }

                if (state == null) return;

                Object gunOperator = aimFromLocalPlayer.invoke(null, client.player);
                if (gunOperator == null) return;

                Object dataHolder = aimGetDataHolder.invoke(gunOperator);
                if (dataHolder == null) return;

                aimIsAimingField.setBoolean(dataHolder, state);

                // During replay, TACZ's own tickAimingProgress() computes clientAimingProgress
                // using System.currentTimeMillis() deltas which are unreliable in replay.
                // When not aiming, force progress to 0 to prevent drift.
                if (!state) {
                    aimProgressField.setFloat(dataHolder, 0f);
                    aimOldProgressField.set(null, 0f);

                    // Also force the dynamics target to 0 so the SecondOrderDynamics
                    // converges to 0 instead of lingering at the old aim value.
                    resolvePositioningFields();
                    setDynamicsTarget(posAimingDynamics, 0f);
                }
            } catch (Exception e) {
                LOGGER.warn("TaczEventInjector: failed to apply aim state in tick: {}", e.toString());
            }
        });
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
            aimTimestampField = dataHolder.getClass().getField("clientAimingTimestamp");
            aimFieldsResolved = true;
        } catch (Exception e) {
            LOGGER.warn("TaczEventInjector: failed to resolve aim fields: {}", e.toString());
        }
    }

    /** Resolve TACZ synced entity data keys for IS_AIMING and AIMING_PROGRESS. */
    private static void resolveSyncedAimKeys() {
        try {
            Class<?> syncedEntityDataClass = Class.forName("com.tacz.guns.entity.sync.core.SyncedEntityData");
            syncedEntityDataInstance = syncedEntityDataClass.getMethod("instance").invoke(null);
            // Find get(Entity, SyncedDataKey) by name+parameter count to avoid
            // Class.forName("net.minecraft.world.entity.Entity") which fails
            // in production Fabric due to intermediary class names.
            for (java.lang.reflect.Method m : syncedEntityDataClass.getMethods()) {
                if (m.getName().equals("get") && m.getParameterCount() == 2) {
                    syncedEntityDataGet = m;
                    break;
                }
            }
            if (syncedEntityDataGet == null) {
                throw new NoSuchMethodException("SyncedEntityData.get(Entity, SyncedDataKey)");
            }

            Class<?> modSyncedEntityDataClass = Class.forName("com.tacz.guns.entity.sync.ModSyncedEntityData");
            isAimingKeyInstance = modSyncedEntityDataClass.getField("IS_AIMING_KEY").get(null);
            aimingProgressKeyInstance = modSyncedEntityDataClass.getField("AIMING_PROGRESS_KEY").get(null);
            syncedAimKeysResolved = true;
        } catch (Exception e) {
            LOGGER.warn("TaczEventInjector: failed to resolve synced aim keys: {}", e.toString());
        }
    }

    /**
     * Read the current aim state from TACZ's synced entity data.
     * Returns true if the entity is aiming, false if not, null if unavailable.
     */
    private static Boolean readSyncedAimState(net.minecraft.world.entity.LivingEntity entity) {
        if (!syncedAimKeysResolved || syncedEntityDataInstance == null || isAimingKeyInstance == null) return null;
        try {
            Object result = syncedEntityDataGet.invoke(syncedEntityDataInstance, entity, isAimingKeyInstance);
            if (result instanceof Boolean b) return b;
        } catch (Exception e) {
            LOGGER.warn("TaczEventInjector: failed to read synced aim state: {}", e.toString());
        }
        return null;
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
            // s2c_gundraw has TWO ItemStacks after entityId: previousGunItem + currentGunItem
            // We skip the previousGunItem and return the currentGunItem
            case "s2c_gundraw" -> {
                buf.readItem(); // skip previousGunItem
                yield buf.readItem(); // return currentGunItem
            }
            // All other gun events have: entityId (already consumed) + single ItemStack
            case "s2c_gun_shoot", "s2c_gun_reload", "s2c_gun_melee",
                 "s2c_gunfire_select", "s2c_gunfire" -> buf.readItem();
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

            // Auto-initialize the state machine if it hasn't been initialized yet
            // (packets may arrive before the first render initializes it)
            if (taczStateMachineIsInitialized != null && !(boolean) taczStateMachineIsInitialized.invoke(stateMachine)) {
                ensureStateMachineInitialized(stateMachine, gunItem);
            }

            taczStateMachineTrigger.invoke(stateMachine, animInput);
        } catch (Exception e) {
            LOGGER.warn("TaczEventInjector: failed to trigger animation '{}': {}", animInput, e.toString());
        }
    }

    private static void ensureStateMachineInitialized(Object stateMachine, ItemStack gunItem) {
        try {
            if (taczStateMachineSetContext == null || taczStateMachineInitialize == null) return;

            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.world.entity.player.Player player = mc.player;
            if (player == null) return;

            // Create GunAnimationStateContext via reflection
            Class<?> contextClass = Class.forName("com.tacz.guns.client.animation.statemachine.GunAnimationStateContext");
            Object context = contextClass.getDeclaredConstructor().newInstance();
            if (context == null) return;

            // Set fields: currentGunItem, partialTicks
            try {
                java.lang.reflect.Field gunItemField = contextClass.getField("currentGunItem");
                gunItemField.set(context, gunItem);
            } catch (NoSuchFieldException e) {
                // Try alternate names
                for (java.lang.reflect.Field f : contextClass.getDeclaredFields()) {
                    if (f.getType() == ItemStack.class) {
                        f.setAccessible(true);
                        f.set(context, gunItem);
                        break;
                    }
                }
            }

            taczStateMachineSetContext.invoke(stateMachine, context);
            taczStateMachineInitialize.invoke(stateMachine);
        } catch (Exception e) {
            LOGGER.warn("TaczEventInjector: failed to auto-init state machine: {}", e.toString());
        }
    }

    private static void playSoundForEvent(String path, ItemStack gunItem, LivingEntity player) {
        if (taczGetGunIndex == null || taczGetGunDisplay == null) return;
        if (gunItem == null || gunItem.isEmpty() || player == null) return;

        try {
            // getGunId is an instance method on IGun — get the IGun from the item
            Class<?> taczIGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            Method iGunGetGunId = taczIGunClass.getMethod("getGunId", ItemStack.class);
            Object iGunInstance = taczIGunClass.getMethod("getIGunOrNull", ItemStack.class).invoke(null, gunItem);
            if (iGunInstance == null) return;
            ResourceLocation gunId = (ResourceLocation) iGunGetGunId.invoke(iGunInstance, gunItem);
            if (gunId == null) return;

            // getClientGunIndex returns Optional<ClientGunIndex> — unwrap it
            Object gunIndexOpt = taczGetGunIndex.invoke(null, gunId);
            if (gunIndexOpt == null) return;
            Object gunIndex = gunIndexOpt.getClass().getMethod("orElse", Object.class).invoke(gunIndexOpt, (Object) null);
            if (gunIndex == null) return;

            Class<?> soundPlayManager = Class.forName("com.tacz.guns.client.sound.SoundPlayManager");
            Class<?> livingEntityClass = LivingEntity.class;
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
            LOGGER.warn("TaczEventInjector: failed to play sound for event '{}': {}", path, e.toString());
        }
    }

    private static void resetPositioningState() {
        resolvePositioningFields();
        try {
            if (posCurrentViewIndex != null) posCurrentViewIndex.set(null, -1);
            if (posOldViewIndex != null) posOldViewIndex.set(null, 0f);
            if (posOldAimingViewMatrix != null) posOldAimingViewMatrix.set(null, null);
            if (posSwitchViewDynamics != null) posSwitchViewDynamics.set(null, null);
            if (posShootTimeStamp != null) posShootTimeStamp.set(null, -1L);
            if (posJumpingSwayProgress != null) posJumpingSwayProgress.set(null, 0f);
            if (posJumpingTimeStamp != null) posJumpingTimeStamp.set(null, -1L);
            if (posLastOnGround != null) posLastOnGround.set(null, false);
            resetDynamicsField(posAimingDynamics);
            resetDynamicsField(posRefitOpeningDynamics);
            resetDynamicsField(posJumpingDynamics);
        } catch (Exception e) {
            LOGGER.warn("TaczEventInjector: failed to reset positioning state: {}", e.toString());
        }
    }

    private static void resetDynamicsField(Field dynamicsField) {
        if (!dynamicsFieldsResolved || dynamicsField == null) return;
        try {
            Object dynamics = dynamicsField.get(null);
            if (dynamics == null) return;
            dynPy.set(dynamics, 0f);
            dynPyd.set(dynamics, 0f);
            dynPx.set(dynamics, 0f);
            dynTarget.set(dynamics, 0f);
        } catch (Exception e) {
            // Silently ignore - field may not exist in this TACZ version
        }
    }

    private static void setDynamicsTarget(Field dynamicsField, float target) {
        if (!dynamicsFieldsResolved || dynamicsField == null) return;
        try {
            Object dynamics = dynamicsField.get(null);
            if (dynamics == null) return;
            dynTarget.set(dynamics, target);
        } catch (Exception e) {
            // Silently ignore
        }
    }

    /**
     * Lightly settle the aiming dynamics when aim has been inactive for a while.
     * Unlike resetPositioningState() which resets everything (causing a snap),
     * this just dampens the velocity to prevent slow drift.
     */
    private static void settleAimingDynamics() {
        resolvePositioningFields();
        if (!dynamicsFieldsResolved || posAimingDynamics == null) return;
        try {
            Object dynamics = posAimingDynamics.get(null);
            if (dynamics == null) return;
            float py = dynPy.getFloat(dynamics);
            float pyd = dynPyd.getFloat(dynamics);
            // If the dynamics are nearly settled (close to 0 with low velocity), snap to 0
            if (Math.abs(py) < 0.05f && Math.abs(pyd) < 0.1f) {
                dynPy.set(dynamics, 0f);
                dynPyd.set(dynamics, 0f);
                dynPx.set(dynamics, 0f);
                dynTarget.set(dynamics, 0f);
            }
        } catch (Exception e) {
            // Silently ignore
        }
    }

    private static void resolvePositioningFields() {
        if (positioningFieldsResolved) return;
        positioningFieldsResolved = true;
        try {
            Class<?> fprgClass = Class.forName("com.tacz.guns.client.event.FirstPersonRenderGunEvent");
            posCurrentViewIndex = fprgClass.getDeclaredField("currentViewIndex");
            posCurrentViewIndex.setAccessible(true);
            posOldViewIndex = fprgClass.getDeclaredField("oldViewIndex");
            posOldViewIndex.setAccessible(true);
            posOldAimingViewMatrix = fprgClass.getDeclaredField("oldAimingViewMatrix");
            posOldAimingViewMatrix.setAccessible(true);
            posSwitchViewDynamics = fprgClass.getDeclaredField("SWITCH_VIEW_DYNAMICS");
            posSwitchViewDynamics.setAccessible(true);
            posShootTimeStamp = fprgClass.getDeclaredField("shootTimeStamp");
            posShootTimeStamp.setAccessible(true);
            posJumpingSwayProgress = fprgClass.getDeclaredField("jumpingSwayProgress");
            posJumpingSwayProgress.setAccessible(true);
            posJumpingTimeStamp = fprgClass.getDeclaredField("jumpingTimeStamp");
            posJumpingTimeStamp.setAccessible(true);
            posLastOnGround = fprgClass.getDeclaredField("lastOnGround");
            posLastOnGround.setAccessible(true);
            posAimingDynamics = fprgClass.getDeclaredField("AIMING_DYNAMICS");
            posAimingDynamics.setAccessible(true);
            posRefitOpeningDynamics = fprgClass.getDeclaredField("REFIT_OPENING_DYNAMICS");
            posRefitOpeningDynamics.setAccessible(true);
            posJumpingDynamics = fprgClass.getDeclaredField("JUMPING_DYNAMICS");
            posJumpingDynamics.setAccessible(true);
        } catch (Exception e) {
            LOGGER.warn("TaczEventInjector: failed to resolve positioning fields: {}", e.toString());
        }
        try {
            Class<?> dynClass = Class.forName("com.tacz.guns.util.math.SecondOrderDynamics");
            dynPy = dynClass.getDeclaredField("py");
            dynPy.setAccessible(true);
            dynPyd = dynClass.getDeclaredField("pyd");
            dynPyd.setAccessible(true);
            dynPx = dynClass.getDeclaredField("px");
            dynPx.setAccessible(true);
            dynTarget = dynClass.getDeclaredField("target");
            dynTarget.setAccessible(true);
            dynamicsFieldsResolved = true;
        } catch (Exception e) {
            LOGGER.warn("TaczEventInjector: failed to resolve SecondOrderDynamics fields: {}", e.toString());
        }
    }
}