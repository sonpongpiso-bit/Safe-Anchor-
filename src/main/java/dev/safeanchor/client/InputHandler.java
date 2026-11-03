package dev.safeanchor.client;

import dev.safeanchor.config.SafeAnchorConfig;
import dev.safeanchor.gui.SafeAnchorScreen;
import dev.safeanchor.sequence.AnchorSequence;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Polls the configured keys (edge triggered). Both keys are user-configurable
 * through the Safe Anchor menu, so they are read from the config rather than
 * from vanilla KeyMappings.
 */
final class InputHandler {
    private final AnchorSequence sequence;
    private boolean activationWasDown;
    private boolean menuWasDown;

    InputHandler(AnchorSequence sequence) {
        this.sequence = sequence;
    }

    void tick(Minecraft mc) {
        long window = mc.getWindow().handle();
        SafeAnchorConfig cfg = SafeAnchorConfig.get();

        boolean menuDown = isDown(window, cfg.menuKey);
        boolean activationDown = isDown(window, cfg.activationKey);

        // Ignore keys while any screen is open (chat, inventory, our menu...).
        if (mc.screen != null) {
            menuWasDown = menuDown;
            activationWasDown = activationDown;
            return;
        }

        if (menuDown && !menuWasDown) {
            mc.setScreen(new SafeAnchorScreen(null));
        } else if (activationDown && !activationWasDown) {
            sequence.start(mc);
        }
        menuWasDown = menuDown;
        activationWasDown = activationDown;
    }

    private static boolean isDown(long window, int key) {
        return key >= 0 && GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }
}
