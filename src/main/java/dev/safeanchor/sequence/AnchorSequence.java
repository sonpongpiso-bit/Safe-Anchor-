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

        // Recalculated from the player's CURRENT position every time this step runs.
        // Sides are tried from the one closest to the player outward; the side
        // opposite the player is never used.
        BlockPos spot = null;
        if (slot >= 0) {
            for (Direction side : sidesByPlayerProximity(player, anchorPos)) {
                BlockPos candidate = anchorPos.relative(side);
                if (isValidGlowstoneSpot(player, level, candidate, candidate.below())) {
                    spot = candidate;
                    break;
                }
            }
        }
        if (spot == null) {
            step = Step.DETONATE; // no protective position: skip, detonate immediately
            return false;
        }
        if (!ensureSlot(player, slot)) return false; // position is re-evaluated after the switch

        BlockPos ground = spot.below();
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
     * Horizontal sides of the anchor ordered by how close they are to the player
     * (best protection first). Based on the player's exact position relative to the
     * anchor centre; if the player is directly above/below it, the facing is used.
     * Sides pointing away from the player are excluded.
     */
    private static List<Direction> sidesByPlayerProximity(LocalPlayer player, BlockPos anchor) {
        double dx = player.getX() - (anchor.getX() + 0.5);
        double dz = player.getZ() - (anchor.getZ() + 0.5);
        if (Math.abs(dx) < 0.01 && Math.abs(dz) < 0.01) {
            Direction facing = player.getDirection();
            dx = -facing.getStepX();
            dz = -facing.getStepZ();
        }
        final double fdx = dx;
        final double fdz = dz;
        List<Direction> sides = new ArrayList<>();
        for (Direction d : HORIZONTAL) {
            if (d.getStepX() * fdx + d.getStepZ() * fdz >= 0.0) sides.add(d);
        }
        sides.sort(Comparator.comparingDouble((Direction d) -> -(d.getStepX() * fdx + d.getStepZ() * fdz)));
        return sides;
    }

    /**
     * Picks an anchor face a real player could click: not covered by a solid block and
     * facing the player's eyes. Prefers the side facing the player, then top, then others.
     */
    private Direction pickAnchorFace(LocalPlayer player, ClientLevel level) {
        Vec3 eye = player.getEyePosition();
        List<Direction> order = new ArrayList<>(sidesByPlayerProximity(player, anchorPos));
        order.add(Direction.UP);
        for (Direction d : HORIZONTAL) {
            if (!order.contains(d)) order.add(d);
        }
        order.add(Direction.DOWN);

        for (Direction d : order) {
            BlockPos neighbour = anchorPos.relative(d);
            if (level.getBlockState(neighbour).isFaceSturdy(level, neighbour, d.getOpposite())) continue;
            Vec3 center = Vec3.atCenterOf(anchorPos)
                    .add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
            Vec3 normal = new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
            if (eye.subtract(center).dot(normal) <= 0.0) continue; // face points away from the player
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
        if (eye.y <= ground.getY() + 1.0) return false; // must be able to click the top face
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
