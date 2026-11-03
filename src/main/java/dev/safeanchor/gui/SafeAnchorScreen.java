package dev.safeanchor.gui;

import dev.safeanchor.config.KeyNames;
import dev.safeanchor.config.SafeAnchorConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class SafeAnchorScreen extends Screen {
    private enum Listening { NONE, ACTIVATION, MENU }

    private final Screen parent;
    private Listening listening = Listening.NONE;

    private Button activationButton;
    private Button menuButton;
    private Button slotButton;
    private Button speedButton;
    private Button scrollButton;

    public SafeAnchorScreen(Screen parent) {
        super(Component.literal("Safe Anchor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        SafeAnchorConfig cfg = SafeAnchorConfig.get();
        int w = 220;
        int h = 20;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 75;
        int gap = 26;

        StringWidget titleWidget = new StringWidget(x, y - 24, w, 12, this.title, this.font);
        this.addRenderableWidget(titleWidget);

        activationButton = Button.builder(Component.empty(), b -> {
            listening = Listening.ACTIVATION;
            refresh();
        }).bounds(x, y, w, h).build();

        menuButton = Button.builder(Component.empty(), b -> {
            listening = Listening.MENU;
            refresh();
        }).bounds(x, y + gap, w, h).build();

        slotButton = Button.builder(Component.empty(), b -> {
            cfg.detonationSlot = cfg.detonationSlot % 9 + 1;
            SafeAnchorConfig.save();
            refresh();
        }).bounds(x, y + gap * 2, w, h).build();

        speedButton = Button.builder(Component.empty(), b -> {
            cfg.tickSpeed = cfg.tickSpeed >= SafeAnchorConfig.MAX_TICKS
                    ? SafeAnchorConfig.MIN_TICKS : cfg.tickSpeed + 1;
            SafeAnchorConfig.save();
            refresh();
        }).bounds(x, y + gap * 3, w, h).build();

        scrollButton = Button.builder(Component.empty(), b -> {
            cfg.hotbarScrollDelay = cfg.hotbarScrollDelay >= SafeAnchorConfig.MAX_SCROLL
                    ? SafeAnchorConfig.MIN_SCROLL : cfg.hotbarScrollDelay + 1;
            SafeAnchorConfig.save();
            refresh();
        }).bounds(x, y + gap * 4, w, h).build();

        Button done = Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(x, y + gap * 5 + 6, w, h).build();

        this.addRenderableWidget(activationButton);
        this.addRenderableWidget(menuButton);
        this.addRenderableWidget(slotButton);
        this.addRenderableWidget(speedButton);
        this.addRenderableWidget(scrollButton);
        this.addRenderableWidget(done);
        refresh();
    }

    private void refresh() {
        SafeAnchorConfig cfg = SafeAnchorConfig.get();
        activationButton.setMessage(Component.literal("Activation Key: "
                + (listening == Listening.ACTIVATION ? "> press a key <" : KeyNames.of(cfg.activationKey))));
        menuButton.setMessage(Component.literal("Menu Key: "
                + (listening == Listening.MENU ? "> press a key <" : KeyNames.of(cfg.menuKey))));
        slotButton.setMessage(Component.literal("Detonation Hotbar Slot: " + cfg.detonationSlot));
        speedButton.setMessage(Component.literal("Tick Speed: " + cfg.tickSpeed
                + " (" + SafeAnchorConfig.MIN_TICKS + "-" + SafeAnchorConfig.MAX_TICKS + ")"));
        scrollButton.setMessage(Component.literal("Hotbar Scroll Delay: " + cfg.hotbarScrollDelay
                + (cfg.hotbarScrollDelay == 0 ? " (instant)" : " tick(s)")));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening != Listening.NONE) {
            int key = event.key();
            if (key != GLFW.GLFW_KEY_ESCAPE) {
                SafeAnchorConfig cfg = SafeAnchorConfig.get();
                int value = key == GLFW.GLFW_KEY_DELETE ? -1 : key;
                if (listening == Listening.ACTIVATION) {
                    cfg.activationKey = value;
                } else if (value >= 0) {
                    cfg.menuKey = value;
                }
                SafeAnchorConfig.save();
            }
            listening = Listening.NONE;
            refresh();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        SafeAnchorConfig.save();
        this.minecraft.setScreen(parent);
    }
}
