package com.moulberry.flashback.mixin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.editor.ui.ReplayUI;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EntityRenderDispatcher.class, priority = 990)
public abstract class MixinEntityRenderDispatcher {

    @Shadow
    private static void renderHitbox(PoseStack poseStack, VertexConsumer vertexConsumer, Entity entity, float f) {
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    public void renderBefore(Entity entity, double d, double e, double f, float g, float h, PoseStack poseStack, MultiBufferSource multiBufferSource, int i, CallbackInfo ci) {
        if (Flashback.isExporting()) {
            EditorState editorState = EditorStateManager.getCurrent();
            if (editorState != null && editorState.hideDuringExport.contains(entity.getUUID())) {
                ci.cancel();
            }
        }
        if (Flashback.isInReplay()) {
            String name = entity.getClass().getName();
            if (name.startsWith("fabric.net.mca.") || name.startsWith("net.mca.")) {
                ci.cancel();
            }
        }
    }

    // Add a yellow outline to selected entity
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V", shift = At.Shift.BEFORE), require = 0)
    public void renderAfter(Entity entity, double d, double e, double f, float g, float h, PoseStack poseStack, MultiBufferSource multiBufferSource, int i, CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            if (ReplayUI.isEntitySelected(entity.getUUID())) {
                renderHitbox(poseStack, multiBufferSource.getBuffer(RenderType.lines()), entity, h);
            } else if (!Flashback.isExporting()) {
                EditorState editorState = EditorStateManager.getCurrent();
                if (editorState != null && entity.getUUID().equals(editorState.audioSourceEntity)) {
                    renderHitbox(poseStack, multiBufferSource.getBuffer(RenderType.lines()), entity, h);
                }
            }
        }
    }

}
