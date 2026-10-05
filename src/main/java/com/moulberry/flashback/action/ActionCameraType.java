package com.moulberry.flashback.action;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public class ActionCameraType implements Action {

    private static final ResourceLocation NAME = Flashback.createResourceLocation("action/camera_type_optional");
    public static final ActionCameraType INSTANCE = new ActionCameraType();
    private ActionCameraType() {
    }

    @Override
    public ResourceLocation name() {
        return NAME;
    }

    @Override
    public void handle(ReplayServer replayServer, FriendlyByteBuf friendlyByteBuf) {
        replayServer.handleCameraType(friendlyByteBuf);
    }

}
