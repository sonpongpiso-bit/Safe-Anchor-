package dev.safeanchor.client;

import dev.safeanchor.config.SafeAnchorConfig;
import dev.safeanchor.sequence.AnchorSequence;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class SafeAnchorClient implements ClientModInitializer {
    public static final String MOD_ID = "safeanchor";

    private static final AnchorSequence SEQUENCE = new AnchorSequence();
    private static final InputHandler INPUT = new InputHandler(SEQUENCE);

    @Override
    public void onInitializeClient() {
        SafeAnchorConfig.load();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            INPUT.tick(client);
            SEQUENCE.tick(client);
        });
    }
}
