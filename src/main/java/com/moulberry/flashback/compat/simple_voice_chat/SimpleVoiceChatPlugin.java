package com.moulberry.flashback.compat.simple_voice_chat;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.action.ActionLevelChunkCached;
import com.moulberry.flashback.action.ActionRegistry;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

import javax.annotation.Nullable;

public class SimpleVoiceChatPlugin implements VoicechatPlugin {

    @Nullable
    public static VoicechatClientApi CLIENT_API;

    @Override
    public String getPluginId() {
        return "flashback";
    }

    @Override
    public void initialize(VoicechatApi api) {
        // Initialize CLIENT_API lazily when first event is received
        ActionRegistry.register(ActionSimpleVoiceChatSound.INSTANCE);
    }

    @Nullable
    public static VoicechatClientApi getClientApi() {
        return CLIENT_API;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, event -> {
            if (CLIENT_API == null) {
                CLIENT_API = event.getVoicechat();
            }
            SimpleVoiceChatRecorder.onReceiveEntitySound(event);
        });
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, event -> {
            if (CLIENT_API == null) {
                CLIENT_API = event.getVoicechat();
            }
            SimpleVoiceChatRecorder.onReceiveLocationalSound(event);
        });
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, event -> {
            if (CLIENT_API == null) {
                CLIENT_API = event.getVoicechat();
            }
            SimpleVoiceChatRecorder.onReceiveStaticSound(event);
        });
        registration.registerEvent(ClientSoundEvent.class, SimpleVoiceChatRecorder::onSendSound);
    }
}
