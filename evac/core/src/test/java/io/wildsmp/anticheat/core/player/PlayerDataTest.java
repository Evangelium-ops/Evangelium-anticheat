package io.wildsmp.anticheat.core.player;

import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PlayerDataTest {

    @Test
    void movementHistoryIsBoundedToMostRecentEntries() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");

        for (int i = 0; i < PlayerData.MAX_MOVEMENT_HISTORY + 10; i++) {
            data.recordMovement(new MovementSample(i, 64, 0, 0f, 0f, true, i, i, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        }

        assertEquals(PlayerData.MAX_MOVEMENT_HISTORY, data.movementHistory().size());
        assertEquals(PlayerData.MAX_MOVEMENT_HISTORY + 9,
                (int) data.movementHistory().getLast().x());
    }

    @Test
    void combatHistoryIsBoundedToMostRecentEntries() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "Alex");
        UUID target = UUID.randomUUID();

        for (int i = 0; i < PlayerData.MAX_COMBAT_HISTORY + 5; i++) {
            data.recordCombat(new CombatSample(target, 0f, 0f, 3.0, i, i));
        }

        assertEquals(PlayerData.MAX_COMBAT_HISTORY, data.combatHistory().size());
    }

    @Test
    void checkStateIsLazilyCreatedAndSharedAcrossCallsForTheSameCheckName() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");

        StringBuilder first = data.computeCheckState("KillauraA", StringBuilder::new);
        first.append("hello");
        StringBuilder second = data.computeCheckState("KillauraA", StringBuilder::new);

        assertSame(first, second);
        assertEquals("hello", second.toString());
    }

    @Test
    void checkStateForDifferentChecksIsIndependent() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");

        StringBuilder a = data.computeCheckState("KillauraA", StringBuilder::new);
        StringBuilder b = data.computeCheckState("SpeedA", StringBuilder::new);

        a.append("a-only");

        assertEquals("", b.toString());
    }
}
