package dev.safeanchor.sequence;

import dev.safeanchor.config.SafeAnchorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AnchorSequence {
    private enum Step { PLACE_ANCHOR, CHARGE, PLACE_GLOWSTONE, DETONATE }

    private static final int CONFIRM_TIMEOUT = 20;
    private static final Direction[] HORIZONTAL =
            { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };

    private boolean active;
    private Step step;
    private int cooldown;
    private int waited;

    private BlockHitResult initialHit;
    private BlockPos anchorPos;
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

        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        SafeAnchorConfig cfg = SafeAnchorConfig.get();
        int detSlot = cfg.detonationSlot - 1;
        if (player.getInventory().getItem(detSlot).is(Items.GLOWSTONE)) return;
        if (findSlot(player, Items.GLOWSTONE) < 0) return;

        BlockPos target = hit.getBlockPos();
        BlockState targetState = level.getBlockState(target);
        anchorExisted = targetState.is(Blocks.RESPAWN_ANCHOR);

        if (anchorExisted) {
            anchorPos = target;
        } else {
            if (findSlot(player, Items.RESPAWN_ANCHOR) < 0) return;
            if (targetState.canBeReplaced()) {
                anchorPos = target;
            } else {
                if (targetState.hasBlockEntity()) return;
                anchorPos = target.relative(hit.getDirection());
            }
            if (!level.getBlockState(anchorPos).canBeReplaced()) return;
            if (!level.isUnobstructed(Blocks.RESPAWN_ANCHOR.defaultBlockState(), anchorPos, CollisionContext.empty())) return;
        }

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
            return false;
        }
        int slot = findSlot(player, Items.RESPAWN_ANCHOR);
        if (slot < 0) return abort(mc);
        if (!ensureSlot(player, slot)) return false;
        if (!use(mc, player, initialHit)) return abort(mc);
        step = Step.CHARGE;
        return true;
    }

    private boolean charge(Minecraft mc, LocalPlayer player, ClientLevel level) {
        BlockState state = level.getBlockState(anchorPos);
        if (!state.is(Blocks.RESPAWN_ANCHOR)) return waitOrAbort(mc);
        if (state.getValue(RespawnAnchorBlock.CHARGE) >= 1) {
            step = Step.PLACE_GLOWSTONE;
            return false;
        }
        int slot = findSlot(player, Items.GLOWSTONE);
        if (slot < 0) return abort(mc);
        if (!ensureSlot(player, slot)) return false;
        if (!use(mc, player, faceHit(anchorPos, pickAnchorFace(player, level)))) return abort(mc);
        step = Step.PLACE_GLOWSTONE;
        return true;
    }

    private boolean placeGlowstone(Minecraft mc, LocalPlayer player, ClientLevel level) {
        BlockState state = level.getBlockState(anchorPos);
        if (!state.is(Blocks.RESPAWN_ANCHOR)) return abort(mc);
        if (state.getValue(RespawnAnchorBlock.CHARGE) < 1) return waitOrAbort(mc);

        int slot = findSlot(player, Items.GLOWSTONE);

        // Recalculated from the player's current world position every time this step runs.
        // Only the single side of the anchor closest to the player is ever used.
        Direction side = closestSide(player, anchorPos);
        BlockPos spot = anchorPos.relative(side);   // same Y level as the anchor
        BlockPos ground = spot.below();             // glowstone rests on this block

        if (slot < 0 || !isValidGlowstoneSpot(player, level, spot, ground)) {
            step = Step.DETONATE; // blocked / invalid: skip and detonate immediately
            return false;
        }
        if (!ensureSlot(player, slot)) return false; // direction is re-evaluated after the switch

        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(ground).add(0.0, 0.5, 0.0), Direction.UP, ground, false);
        use(mc, player, hit);
        step = Step.DETONATE;
        return true;
    }

    private boolean detonate(Minecraft mc, LocalPlayer player, ClientLevel level) {
        BlockState state = level.getBlockState(anchorPos);
        if (!state.is(Blocks.RESPAWN_ANCHOR)) return abort(mc);
        if (state.getValue(RespawnAnchorBlock.CHARGE) < 1) return waitOrAbort(mc);

        int slot = SafeAnchorConfig.get().detonationSlot - 1;
        if (!ensureSlot(player, slot)) return false;
        use(mc, player, faceHit(anchorPos, pickAnchorFace(player, level)));
        reset(mc);
        return true;
    }

    // ------------------------------------------------------- player-relative

    /**
     * The horizontal side (N/E/S/W) of the anchor closest to the player, from the vector
     * anchor-centre -> player position. Uses world position only. Camera direction is used
     * solely in the degenerate case where the player is exactly above/below the anchor centre.
     */
    private static Direction closestSide(LocalPlayer player, BlockPos anchor) {
        double dx = player.getX() - (anchor.getX() + 0.5);
        double dz = player.getZ() - (anchor.getZ() + 0.5);
        if (Math.abs(dx) < 1.0E-4 && Math.abs(dz) < 1.0E-4) {
            return player.getDirection().getOpposite();
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** Horizontal sides ordered from closest to the player to farthest (used only for click faces). */
    private static List<Direction> sidesByPlayerProximity(LocalPlayer player, BlockPos anchor) {
        double dx = player.getX() - (anchor.getX() + 0.5);
        double dz = player.getZ() - (anchor.getZ() + 0.5);
        List<Direction> sides = new ArrayList<>(List.of(HORIZONTAL));
        sides.sort(Comparator.comparingDouble((Direction d) -> -(d.getStepX() * dx + d.getStepZ() * dz)));
        return sides;
    }

    /**
     * Picks an anchor face a real player could click: not covered by a solid block and
     * facing the player's eyes. Prefers the side facing the player, then top, then others.
     */
    private Direction pickAnchorFace(LocalPlayer player, ClientLevel level) {
        Vec3 eye = player.getEyePosition();
        List<Direction> order = new ArrayList<>(sidesByPlayerProximity(player, anchorPos));
        order.add(1, Direction.UP);
        order.add(Direction.DOWN);

        for (Direction d : order) {
            BlockPos neighbour = anchorPos.relative(d);
            if (level.getBlockState(neighbour).isFaceSturdy(level, neighbour, d.getOpposite())) continue;
            Vec3 center = Vec3.atCenterOf(anchorPos)
                    .add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
            Vec3 normal = new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
            if (eye.subtract(center).dot(normal) <= 0.0) continue;
            return d;
        }
        return Direction.UP;
    }

    private boolean isValidGlowstoneSpot(LocalPlayer player, ClientLevel level, BlockPos spot, BlockPos ground) {
        BlockState spotState = level.getBlockState(spot);
        BlockState groundState = level.getBlockState(ground);
        if (!spotState.canBeReplaced()) return false;
        if (!groundState.isFaceSturdy(level, ground, Direction.UP)) return false;
        if (groundState.hasBlockEntity() || groundState.is(Blocks.RESPAWN_ANCHOR)) return false;
        if (!level.isUnobstructed(Blocks.GLOWSTONE.defaultBlockState(), spot, CollisionContext.empty())) return false;
        Vec3 eye = player.getEyePosition();
        if (eye.y <= ground.getY() + 1.0) return false;
        return eye.distanceToSqr(Vec3.atCenterOf(ground)) <= 4.5 * 4.5;
    }

    // ---------------------------------------------------------------- helpers

    private static BlockHitResult faceHit(BlockPos pos, Direction face) {
        Vec3 loc = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new BlockHitResult(loc, face, pos, false);
    }

    /** Standard client right-click: MultiPlayerGameMode#useItemOn sends the normal use-item-on packet. */
    private static boolean use(Minecraft mc, LocalPlayer player, BlockHitResult hit) {
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private boolean ensureSlot(LocalPlayer player, int slot) {
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
            int delay = SafeAnchorConfig.get().hotbarScrollDelay;
            if (delay > 0) {
                cooldown = delay - 1;
                return false;
            }
        }
        return player.getInventory().getSelectedSlot() == slot;
    }

    private static int findSlot(LocalPlayer player, Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    private boolean waitOrAbort(Minecraft mc) {
        if (++waited > CONFIRM_TIMEOUT) abort(mc);
        return false;
    }

    private boolean abort(Minecraft mc) {
        reset(mc);
        return false;
    }

    private void reset(Minecraft mc) {
        if (mc.player != null && active) {
            mc.player.getInventory().setSelectedSlot(originalSlot);
        }
        active = false;
        step = null;
        initialHit = null;
    }
}
