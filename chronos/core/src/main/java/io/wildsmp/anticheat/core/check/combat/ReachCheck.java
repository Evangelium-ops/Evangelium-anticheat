package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.AttackPacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Flags an attack whose distance - measured against the target's *backtracked* position at the
 * tick the attacker's client actually saw, not the target's live current position - exceeds
 * vanilla max reach. Backtrack is what lets this stay accurate under real, jittery ping instead
 * of relying on a flat ping-scaled tolerance. Tick-sync (turning real time into "the attacker's
 * perceived tick") happens on the originating Minecraft server, before the packet ever reaches
 * this check - see {@link AttackPacket#perceivedTick()}.
 */
public final class ReachCheck extends Check {

    public static final double VANILLA_MAX_REACH = 3.0;
    // The target's backtracked position only exists at whole-tick resolution (50ms apart), so a
    // fast-moving target's true position at the exact instant of the click can legitimately sit up
    // to half a tick of travel away from the nearest recorded snapshot, on top of ordinary network
    // jitter and eye-height rounding. 0.1 was too tight for real play - confirmed live, a genuine
    // hit measured at 3.17 blocks (VANILLA_MAX_REACH + 0.17) got flagged.
    public static final double EPSILON = 0.3;
    private static final double EYE_HEIGHT = 1.62;
    private static final Logger LOG = Logger.getLogger(ReachCheck.class.getName());

    private final Function<UUID, PlayerData> targetLookup;

    public ReachCheck(Function<UUID, PlayerData> targetLookup) {
        super("ReachA", CheckCategory.COMBAT);
        this.targetLookup = targetLookup;
    }

    @Override
    public void onPacketReceive(PlayerData attackerData, InboundPacket packet) {
        if (!(packet instanceof AttackPacket ap)) {
            return;
        }
        checkReach(attackerData, ap.targetId(), ap.perceivedTick());
    }

    private void checkReach(PlayerData attackerData, UUID targetId, long perceivedTick) {
        PlayerData targetData = targetLookup.apply(targetId);
        if (targetData == null) {
            LOG.log(Level.FINE, "ReachA: no PlayerData for target {0} - can''t evaluate this attack", targetId);
            return;
        }

        TickIndexedPositionHistory targetHistory = targetData.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);

        Optional<PositionSnapshot> targetSnapshot = targetHistory.at(perceivedTick);
        if (targetSnapshot.isEmpty()) {
            LOG.log(Level.FINE, "ReachA: no position history for target {0} at perceived tick {1} - can''t evaluate this attack",
                    new Object[] {targetId, perceivedTick});
            return; // no data for that tick yet - nothing to compare against
        }

        MovementSample attackerLast = attackerData.movementHistory().peekLast();
        if (attackerLast == null) {
            LOG.log(Level.FINE, "ReachA: attacker {0} has no movement history yet - can''t evaluate this attack", attackerData.id());
            return;
        }

        double distance = targetSnapshot.get().distanceTo(attackerLast.x(), attackerLast.y() + EYE_HEIGHT, attackerLast.z());
        LOG.log(Level.FINE, "ReachA: evaluated attack from {0} - distance {1} blocks", new Object[] {attackerData.id(), distance});
        if (distance > VANILLA_MAX_REACH + EPSILON) {
            // Temporary elevated diagnostic - a prior distance blow-up traced to a stale
            // teleport-adjacent snapshot; logging the raw coordinates on both sides (rather than
            // just the scalar distance) so a repeat can be diagnosed without guessing again.
            LOG.log(Level.INFO, "ReachA flag detail: attacker {0} at ({1},{2},{3}) tick {4}, "
                            + "target {5} snapshot at ({6},{7},{8}) tick {9}, perceivedTick {10}, distance {11}",
                    new Object[] {attackerData.id(), attackerLast.x(), attackerLast.y(), attackerLast.z(),
                            attackerLast.tickTimestamp(), targetId, targetSnapshot.get().x(), targetSnapshot.get().y(),
                            targetSnapshot.get().z(), targetSnapshot.get().tick(), perceivedTick, distance});
            flag(attackerData, distance - VANILLA_MAX_REACH, String.format("reach %.2f blocks", distance));
        }
    }

    /** Test-only hook - real dispatch goes through {@link #onPacketReceive}. */
    void testCheckReach(PlayerData attackerData, UUID targetId, long perceivedTick) {
        checkReach(attackerData, targetId, perceivedTick);
    }
}
