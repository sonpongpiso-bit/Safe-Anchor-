package dev.safeanchor.sequence;

import dev.safeanchor.config.SafeAnchorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Tick driven state machine:
 *   place anchor -> charge (glowstone) -> glowstone behind (optional) -> detonate.
 *
 * Every interaction goes through MultiPlayerGameMode#useItemOn, i.e. the normal
 * client interaction path. The player is never made to sneak.
 */
public final class AnchorSequence {
    private enum Step { PLACE_ANCHOR, CHARGE, PLACE_GLOWSTONE, DETONATE }

    /** Max ticks to wait for the server to confirm a block change before giving up. */
    private static final int CONFIRM_TIMEOUT = 20;

    private boolean active;
    private Step step;
    private int cooldown;
    private int waited;

    private BlockHitResult initialHit;
    private BlockPos anchorPos;
    private Direction anchorFace;   // face of the anchor pointing at the player
    private Direction behind;       // horizontal direction pointing away from the player
    private boolean anchorExisted;
    private int originalSlot;

    public boolean isActive() {
        return active;
    }

    // ------------------------------------------------------------------ start

    public void start(Minecraft mc) {
        if (active) return;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null || player.isSpectator()) return;

        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            msg(player, "Look at a block first.");
            return;
        }

        SafeAnchorConfig cfg = SafeAnchorConfig.get();
        int detSlot = cfg.detonationSlot - 1;
        if (player.getInventory().getItem(detSlot).is(Items.GLOWSTONE)) {
            msg(player, "Detonation slot " + cfg.detonationSlot + " holds Glowstone - pick another slot.");
            return;
        }
        if (findSlot(player, Items.GLOWSTONE) < 0) {
            msg(player, "No Glowstone in hotbar.");
            return;
        }

        BlockPos target = hit.getBlockPos();
        BlockState targetState = level.getBlockState(target);
        anchorExisted = targetState.is(Blocks.RESPAWN_ANCHOR);

        if (anchorExisted) {
            anchorPos = target;
        } else {
            if (findSlot(player, Items.RESPAWN_ANCHOR) < 0) {
                msg(player, "No Respawn Anchor in hotbar.");
                return;
            }
            if (targetState.canBeReplaced()) {
                anchorPos = target;
            } else {
                if (targetState.hasBlockEntity()) {
                    // Without sneaking, right-clicking it would open a GUI instead of placing.
                    msg(player, "Target block is interactive - aim at a plain block.");
                    return;
                }
                anchorPos = target.relative(hit.getDirection());
            }
        }

        Direction facing = player.getDirection();            // horizontal look direction
        behind = facing;                                      // away from the player
        anchorFace = facing.getOpposite();                    // toward the player
        initialHit = hit;
        originalSlot = player.getInventory().getSelectedSlot();
        step = Step.PLACE_ANCHOR;
        cooldown = 0;
        waited = 0;
        active = true;
    }

    // ------------------------------------------------------------------- tick

    public void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null || !player.isAlive()) {
            active = false;
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        boolean advanced;
        try {
            advanced = switch (step) {
                case PLACE_ANCHOR -> placeAnchor(mc, player, level);
                case CHARGE -> charge(mc, player, level);
                case PLACE_GLOWSTONE -> placeGlowstone(mc, player, level);
                case DETONATE -> detonate(mc, player, level);
            };
        } catch (RuntimeException e) {
            msg(player, "Sequence error: " + e.getClass().getSimpleName());
            reset(mc);
            return;
        }
        if (!active) return;
        if (advanced) {
            waited = 0;
            cooldown = SafeAnchorConfig.get().tickSpeed - 1;
        }
    }

    // ------------------------------------------------------------------ steps

    private boolean placeAnchor(Minecraft mc, LocalPlayer player, ClientLevel level) {
        if (anchorExisted) {
            step = Step.CHARGE;
            return false; // nothing was sent, no delay needed
        }
        int slot = findSlot(player, Items.RESPAWN_ANCHOR);
        if (slot < 0) return abort(mc, player, "Ran out of Respawn Anchors.");
        select(player, slot);
        if (!use(mc, player, initialHit)) return abort(mc, player, "Could not place the anchor.");
        step = Step.CHARGE;
        return true;
    }

    private boolean charge(Minecraft mc, LocalPlayer player, ClientLevel level) {
        BlockState state = level.getBlockState(anchorPos);
        if (!state.is(Blocks.RESPAWN_ANCHOR)) {
            return waitOrAbort(mc, player, "Anchor was not placed.");
        }
        if (state.getValue(RespawnAnchorBlock.CHARGE) >= 1) { // already charged, skip
            step = Step.PLACE_GLOWSTONE;
            return false;
        }
        int slot = findSlot(player, Items.GLOWSTONE);
        if (slot < 0) return abort(mc, player, "No Glowstone to charge the anchor.");
        select(player, slot);
        if (!use(mc, player, faceHit(anchorPos, anchorFace))) {
            return abort(mc, player, "Could not charge the anchor.");
        }
        step = Step.PLACE_GLOWSTONE;
        return true;
    }

    private boolean placeGlowstone(Minecraft mc, LocalPlayer player, ClientLevel level) {
        BlockState state = level.getBlockState(anchorPos);
        if (!state.is(Blocks.RESPAWN_ANCHOR)) return abort(mc, player, "Anchor disappeared.");
        if (state.getValue(RespawnAnchorBlock.CHARGE) < 1) {
            return waitOrAbort(mc, player, "Anchor did not charge.");
        }

        // Optional step: any problem here just skips it - detonation has priority.
        BlockPos spot = anchorPos.relative(behind);
        BlockPos ground = spot.below();
        int slot = findSlot(player, Items.GLOWSTONE);
        step = Step.DETONATE;
        if (slot >= 0 && isValidGlowstoneSpot(player, level, spot, ground)) {
            select(player, slot);
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(ground).add(0.0, 0.5, 0.0), Direction.UP, ground, false);
            use(mc, player, hit); // result intentionally ignored
            return true;
        }
        return false; // no space / no glowstone: go straight to detonation without delay
    }

    private boolean detonate(Minecraft mc, LocalPlayer player, ClientLevel level) {
        BlockState state = level.getBlockState(anchorPos);
        if (!state.is(Blocks.RESPAWN_ANCHOR)) return abort(mc, player, "Anchor disappeared.");
        if (state.getValue(RespawnAnchorBlock.CHARGE) < 1) {
            return waitOrAbort(mc, player, "Anchor is not charged.");
        }
        select(player, SafeAnchorConfig.get().detonationSlot - 1);
        use(mc, player, faceHit(anchorPos, anchorFace));
        reset(mc);
        return true;
    }

    // ---------------------------------------------------------------- helpers

    private boolean isValidGlowstoneSpot(LocalPlayer player, ClientLevel level, BlockPos spot, BlockPos ground) {
        BlockState spotState = level.getBlockState(spot);
        BlockState groundState = level.getBlockState(ground);
        if (!spotState.canBeReplaced()) return false;
        if (!groundState.isFaceSturdy(level, ground, Direction.UP)) return false;
        if (groundState.hasBlockEntity() || groundState.is(Blocks.RESPAWN_ANCHOR)) return false; // would open GUI / recharge
        if (!level.isUnobstructed(Blocks.GLOWSTONE.defaultBlockState(), spot, CollisionContext.empty())) return false;
        return player.canInteractWithBlock(ground, 0.0);
    }

    private static BlockHitResult faceHit(BlockPos pos, Direction face) {
        Vec3 loc = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new BlockHitResult(loc, face, pos, false);
    }

    /** Normal client right-click on a block; swings the arm like vanilla does. */
    private static boolean use(Minecraft mc, LocalPlayer player, BlockHitResult hit) {
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private static void select(LocalPlayer player, int slot) {
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
    }

    private static int findSlot(LocalPlayer player, Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    /** Waits for server confirmation; aborts after the timeout. Returns false (not advanced). */
    private boolean waitOrAbort(Minecraft mc, LocalPlayer player, String reason) {
        if (++waited > CONFIRM_TIMEOUT) abort(mc, player, reason);
        return false;
    }

    private boolean abort(Minecraft mc, LocalPlayer player, String reason) {
        msg(player, reason);
        reset(mc);
        return false;
    }

    /** Ends the sequence and restores the player's original hotbar slot. */
    private void reset(Minecraft mc) {
        if (mc.player != null && active) {
            select(mc.player, originalSlot);
        }
        active = false;
        step = null;
        initialHit = null;
    }

    private static void msg(LocalPlayer player, String text) {
        player.displayClientMessage(Component.literal("[Safe Anchor] " + text), true);
    }
}
