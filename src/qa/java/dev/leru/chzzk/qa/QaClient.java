package dev.leru.chzzk.qa;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class QaClient implements ClientModInitializer {
    private int ticks;
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // External smoke runner stops the dedicated-server client after reading server QA_PASS.
            if (client.getServer() != null && QaMod.passed && ++ticks > 20) client.scheduleStop();
        });
    }
}
