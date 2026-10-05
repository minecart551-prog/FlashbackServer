package com.moulberry.flashback.playback;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.UUID;

/**
 * Replays the perspective (F5) of the player who was recording while that player is being
 * spectated.
 *
 * <p>The recording player's {@link CameraType} is written into the replay by
 * {@link com.moulberry.flashback.io.AsyncReplaySaver#writeCameraType(int)} on every change and
 * into each snapshot, so seeking restores it. Nothing is applied unless the viewer is actually
 * spectating the recorded player and the "Sync Perspective" toggle in the Visuals panel is on.
 */
public class ReplayPerspective {

    private static volatile int recordedCameraType = -1;
    private static volatile UUID localPlayerUuid = null;

    private static CameraType originalCameraType = null;

    private ReplayPerspective() {
    }

    public static void track(int cameraType) {
        if (cameraType < 0 || cameraType >= CameraType.values().length) {
            return;
        }
        recordedCameraType = cameraType;
    }

    public static void setLocalPlayer(UUID uuid) {
        localPlayerUuid = uuid;
    }

    /**
     * Drops the recorded perspective before a snapshot is played, so a chunk that predates the
     * perspective being recorded doesn't keep the state of the previous chunk.
     */
    public static void clearRecorded() {
        recordedCameraType = -1;
    }

    /**
     * Full reset plus putting the viewer's own camera perspective back, called when leaving a replay.
     */
    public static void clear() {
        clearRecorded();
        localPlayerUuid = null;
        restoreOriginal();
    }

    private static void restoreOriginal() {
        if (originalCameraType != null) {
            Minecraft.getInstance().options.setCameraType(originalCameraType);
            originalCameraType = null;
        }
    }

    public static void tick() {
        if (!Flashback.isInReplay()) {
            restoreOriginal();
            return;
        }

        int recorded = recordedCameraType;
        if (recorded < 0) {
            return;
        }

        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null || !editorState.replayVisuals.syncPerspective) {
            return;
        }

        UUID localUuid = localPlayerUuid;
        AbstractClientPlayer spectating = Flashback.getSpectatingPlayer();
        if (localUuid == null || spectating == null || !localUuid.equals(spectating.getUUID())) {
            return;
        }

        // Enforced every tick rather than only when the recorded value changes: while the replay
        // is running the perspective can be reset elsewhere, and only applying on change would
        // leave it stuck on the reset value until the next recorded switch.
        Minecraft minecraft = Minecraft.getInstance();
        CameraType wanted = CameraType.values()[recorded];
        if (minecraft.options.getCameraType() == wanted) {
            return;
        }

        if (originalCameraType == null) {
            originalCameraType = minecraft.options.getCameraType();
        }
        minecraft.options.setCameraType(wanted);
    }

}
