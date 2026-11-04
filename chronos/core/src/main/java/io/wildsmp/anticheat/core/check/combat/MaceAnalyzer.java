package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects a "mace damage" cheat - dealing a Mace's smash-attack bonus damage (the fall-distance-
 * scaled bonus vanilla only grants while genuinely falling from height) without having actually
 * fallen.
 *
 * <p>Rather than modeling the exact vanilla smash-attack damage curve (which scales with fall
 * distance and the Density enchant in ways this codebase has no confident source of truth for,
 * and would risk the same kind of wrong-formula false positives earlier checks hit), this compares
 * the SERVER's own real damage output for a confirmed mace hit (see {@link
 * io.wildsmp.anticheat.core.packet.DamagePacket} - a genuine Bukkit event, not anything
 * client-reported) against the attacker's fall distance as THIS anticheat independently tracks it
 * from their own movement packets. Confirmed live: gating on fall distance as a simple yes/no
 * ("did they fall at all") let a 210-damage hit through because our own tracking showed a 12-block
 * fall - real, but nowhere near enough to legitimately justify 210 damage. The ceiling has to scale
 * WITH the tracked fall distance, not just be waived by it, or a cheat that also spoofs a modest
 * fake fall trivially bypasses the whole check regardless of how absurd the actual damage is.
 */
public final class MaceAnalyzer {

    private static final Logger LOG = Logger.getLogger(MaceAnalyzer.class.getName());

    // Flat allowance for what a GROUNDED (zero tracked fall) mace hit could ever legitimately deal
    // - base mace damage, any enchant (Sharpness etc.), and even a Strength potion stacked on top,
    // with real headroom above all of that. Unverified against real playtest data yet - may need
    // retuning the way ReachA's tolerance did.
    private static final double MAX_GROUNDED_MACE_DAMAGE = 20.0;
    // Generous per-block scaling on top of the flat allowance for however far they genuinely fell -
    // padded well above vanilla's real per-block smash bonus (even accounting for Density) so a
    // legitimate huge Wind-Charge-launch-then-smash play never trips this, while a damage number
    // wildly out of proportion to the tracked fall still does.
    private static final double MAX_DAMAGE_PER_FALL_BLOCK = 6.0;

    /**
     * @param attackerHistory the ATTACKER's (mace wielder's) own recent movement history, oldest first
     * @param hitTick         the tick the damage was applied
     * @param damage          the real damage the server actually applied for this hit
     * @return a suspicion score: 0.0 (unremarkable) or 1.0 (confirmed impossible smash damage) -
     *         binary, not fraction-based, since a single confirmed instance is already unambiguous
     */
    public double analyze(Deque<MovementSample> attackerHistory, long hitTick, double damage) {
        List<MovementSample> ordered = List.copyOf(attackerHistory);
        int hitIndex = AimGeometry.indexOfClosest(ordered, hitTick);
        if (hitIndex < 0) {
            return 0.0;
        }

        double fallDistance = fallDistanceAt(ordered, hitIndex);
        double maxPlausibleDamage = maxPlausibleDamage(fallDistance);
        boolean impossible = damage > maxPlausibleDamage;
        LOG.log(Level.INFO, "Mace: hit tick {0}, damage {1}, tracked fall distance {2}, max plausible {3}, impossible {4}",
                new Object[] {hitTick, damage, fallDistance, maxPlausibleDamage, impossible});
        return impossible ? 1.0 : 0.0;
    }

    /**
     * The most damage a mace hit could ever legitimately deal given a real fall of {@code
     * fallDistance} blocks - the same formula {@link #analyze} judges a hit against, exposed so
     * bukkit-side code can clamp an exploited hit's damage in real time (before it ever applies to
     * the victim) using a LOCALLY tracked fall distance, without waiting on a round trip to this
     * (backend-side) analyzer. Both sides computing the ceiling from this one method keeps them
     * from silently drifting apart.
     */
    public static double maxPlausibleDamage(double fallDistance) {
        return MAX_GROUNDED_MACE_DAMAGE + Math.max(0.0, fallDistance) * MAX_DAMAGE_PER_FALL_BLOCK;
    }

    /** How far above their current position the attacker's own tracked history shows them peaking during the current (possibly still-ongoing) airborne stretch. */
    private static double fallDistanceAt(List<MovementSample> ordered, int hitIndex) {
        double currentY = ordered.get(hitIndex).y();
        double peakY = currentY;
        int i = hitIndex;
        while (i > 0 && !ordered.get(i).onGround()) {
            peakY = Math.max(peakY, ordered.get(i).y());
            i--;
        }
        peakY = Math.max(peakY, ordered.get(i).y());
        return peakY - currentY;
    }
}
