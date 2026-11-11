package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.DamagePacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.PlayerData;

/**
 * Flags "mace damage" cheats (violation type "MaceA") - dealing Mace smash-attack bonus damage
 * without having genuinely fallen. See {@link MaceAnalyzer} for the actual comparison.
 *
 * <p>Unlike every packet-driven check in this package, {@link DamagePacket} doesn't come from
 * PacketEvents at all - it's forwarded from a real Bukkit {@code EntityDamageByEntityEvent}, since
 * the whole point is comparing the SERVER's own authoritative damage output (which a hacked client
 * can't lie about) against fall distance as this anticheat independently tracks it. {@code data} in
 * {@link #onPacketReceive} is the ATTACKER (the mace wielder, already resolved backend-side before
 * dispatch) - no separate target lookup is needed, since this only ever looks at the attacker's own
 * movement history.
 */
public final class MaceDamageCheck extends Check {

    // Higher than the fraction-based checks in this package - a single confirmed instance here is
    // already unambiguous evidence, not one data point among several needed to trust a pattern.
    private static final double VL_SCALE = 10.0;

    private final MaceAnalyzer analyzer;

    public MaceDamageCheck() {
        this(new MaceAnalyzer());
    }

    public MaceDamageCheck(MaceAnalyzer analyzer) {
        super("MaceA", CheckCategory.COMBAT);
        this.analyzer = analyzer;
    }

    @Override
    public void onPacketReceive(PlayerData data, InboundPacket packet) {
        if (!(packet instanceof DamagePacket dp)) {
            return;
        }
        evaluate(data, dp.tick(), dp.damage());
    }

    private void evaluate(PlayerData data, long hitTick, double damage) {
        double score = analyzer.analyze(data.movementHistory(), hitTick, damage);
        if (score > 0.0) {
            flag(data, score * VL_SCALE, String.format("mace damage %.1f with no real fall tracked", damage));
        }
    }

    /** Test-only hook - real dispatch goes through {@link #onPacketReceive}. */
    void testEvaluate(PlayerData data, long hitTick, double damage) {
        evaluate(data, hitTick, damage);
    }
}
