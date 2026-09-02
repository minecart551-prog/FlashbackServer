package com.moulberry.flashback.compat.simple_voice_chat;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.packet.FlashbackVoiceChatSound;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.voice.client.ClientManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public class SimpleVoiceChatRecorder {

    private static boolean shouldWritePacket() {
        return Flashback.RECORDER != null && Flashback.RECORDER.readyToWrite() && Flashback.getConfig().recordVoiceChat;
    }

    public static void onReceiveEntitySound(ClientReceiveSoundEvent.EntitySound event) {
        if (!shouldWritePacket()) {
            return;
        }

        FlashbackVoiceChatSound soundPacket = new FlashbackVoiceChatSound.SoundEntity(event.getId(), event.getRawAudio(), event.isWhispering(), event.getDistance());
        submitSoundPacket(soundPacket);
    }

    public static void onReceiveLocationalSound(ClientReceiveSoundEvent.LocationalSound event) {
        if (!shouldWritePacket()) {
            return;
        }

        Vec3 position = new Vec3(event.getPosition().getX(), event.getPosition().getY(), event.getPosition().getZ());
        FlashbackVoiceChatSound soundPacket = new FlashbackVoiceChatSound.SoundLocational(event.getId(), event.getRawAudio(), position, event.getDistance());
        submitSoundPacket(soundPacket);
    }

    public static void onReceiveStaticSound(ClientReceiveSoundEvent.StaticSound event) {
        if (!shouldWritePacket()) {
            return;
        }

        FlashbackVoiceChatSound soundPacket = new FlashbackVoiceChatSound.SoundStatic(event.getId(), event.getRawAudio());
        submitSoundPacket(soundPacket);
    }

    public static void onSendSound(ClientSoundEvent event) {
        if (!shouldWritePacket()) {
            return;
        }

        UUID id = ClientManager.getPlayerStateManager().getOwnID();

        de.maxhenkel.voicechat.api.VoicechatClientApi clientApi = SimpleVoiceChatPlugin.getClientApi();
        FlashbackVoiceChatSound soundPacket;
        if (clientApi != null && clientApi.getGroup() != null) {
            soundPacket = new FlashbackVoiceChatSound.SoundStatic(id, event.getRawAudio());
        } else {
            float distance = clientApi != null ? (float) clientApi.getVoiceChatDistance() : 16.0f;
            soundPacket = new FlashbackVoiceChatSound.SoundEntity(id, event.getRawAudio(), event.isWhispering(), distance);
        }

        submitSoundPacket(soundPacket);
    }

    private static void submitSoundPacket(FlashbackVoiceChatSound soundPacket) {
        Minecraft.getInstance().submit(() -> {
            if (shouldWritePacket()) {
                Flashback.RECORDER.submitCustomTask(writer -> {
                    writer.startAction(ActionSimpleVoiceChatSound.INSTANCE);
                    soundPacket.write(writer.friendlyByteBuf());
                    writer.finishAction(ActionSimpleVoiceChatSound.INSTANCE);
                });
            }
        });
    }

}