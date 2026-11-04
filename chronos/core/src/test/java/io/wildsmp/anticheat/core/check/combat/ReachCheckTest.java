package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        ReachCheck check = new ReachCheck(id -> null);

        assertEquals("ReachA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.COMBAT, check.category());
    }

    @Test
    void withinVanillaReachOfTheBacktrackedTargetPositionDoesNotFlag() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        attacker.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 10, 10, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                "ReachCheck.PositionHistory", TickIndexedPositionHistory::new);
        targetHistory.record(new PositionSnapshot(2.5, 64, 0, 0.3, 1.8, 10L)); // ~2.2 blocks from attacker eye

        Map<UUID, PlayerData> byId = Map.of(target.id(), target);

        CheckRegistry registry = new CheckRegistry(violationManager);
        ReachCheck check = new ReachCheck(byId::get);
        registry.register(check);

        check.testCheckReach(attacker, target.id(), 10L);

        assertEquals(0.0, violationManager.vl(attacker.id(), "ReachA"), 0.001);
    }

    @Test
    void beyondVanillaReachOfTheBacktrackedTargetPositionFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        attacker.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 10, 10, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                "ReachCheck.PositionHistory", TickIndexedPositionHistory::new);
        targetHistory.record(new PositionSnapshot(6.0, 64, 0, 0.3, 1.8, 10L)); // well beyond 3-block reach

        Map<UUID, PlayerData> byId = Map.of(target.id(), target);

        CheckRegistry registry = new CheckRegistry(violationManager);
        ReachCheck check = new ReachCheck(byId::get);
        registry.register(check);

        check.testCheckReach(attacker, target.id(), 10L);

        assertTrue(violationManager.vl(attacker.id(), "ReachA") > 0.0);
    }

    @Test
    void backtrackedTickOffByAFewFromTheNearestRecordedSnapshotStillEvaluates() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        attacker.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 10, 10, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                "ReachCheck.PositionHistory", TickIndexedPositionHistory::new);
        // The target's client didn't send a packet on tick 10 itself - only tick 8, two ticks off
        // from the attacker's perceived tick. An exact-only lookup would find nothing at all and
        // silently never evaluate this attack, regardless of how far away the target really was.
        targetHistory.record(new PositionSnapshot(6.0, 64, 0, 0.3, 1.8, 8L));

        Map<UUID, PlayerData> byId = Map.of(target.id(), target);

        CheckRegistry registry = new CheckRegistry(violationManager);
        ReachCheck check = new ReachCheck(byId::get);
        registry.register(check);

        check.testCheckReach(attacker, target.id(), 10L);

        assertTrue(violationManager.vl(attacker.id(), "ReachA") > 0.0);
    }

    @Test
    void unknownTargetIdIsIgnoredWithoutError() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        ReachCheck check = new ReachCheck(id -> null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        attacker.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 0, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        check.testCheckReach(attacker, UUID.randomUUID(), 0L);

        assertEquals(0.0, violationManager.vl(attacker.id(), "ReachA"), 0.001);
    }
}
