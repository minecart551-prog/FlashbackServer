package com.moulberry.flashback.mixin.compat.simplebedrockmodel;

import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Placeholder mixin for SimpleBedrockModel compatibility.
 *
 * <p>The actual gun rendering during replay works because:
 * <ol>
 *   <li>Flashback's {@code flashback$renderHandsWithItems} calls {@code renderArmWithItem}
 *       with the spectating player's item stack.</li>
 *   <li>SimpleBedrockModel's {@code sbm$renderHand} fires {@code RenderHandEvent} carrying
 *       that item stack.</li>
 *   <li>SimpleBedrockModel's own {@code onRenderHand} handler returns early (no cancel)
 *       when {@code activeInstance} is null (local player has no gun).</li>
 *   <li>TACZ's {@code FirstPersonRenderEvent.onRenderHand} handler then runs, detects the
 *       gun in the event item stack, and renders it.</li>
 * </ol>
 */
@IfModLoaded("simplebedrockmodel")
@Pseudo
@Mixin(targets = "com.github.mcmodderanchor.simplebedrockmodel.v1.client.handler.FirstPersonRenderHandler", remap = false)
public class MixinFirstPersonRenderHandler {
}
