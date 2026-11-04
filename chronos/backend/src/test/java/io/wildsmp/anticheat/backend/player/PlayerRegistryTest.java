package io.wildsmp.anticheat.backend.player;

import io.wildsmp.anticheat.core.player.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerRegistryTest {

    @Test
    void getOrCreateReturnsTheSameInstanceOnRepeatedCalls() {
        PlayerRegistry registry = new PlayerRegistry();
        UUID id = UUID.randomUUID();

        PlayerData first = registry.getOrCreate(id, "Steve");
        PlayerData second = registry.getOrCreate(id, "Steve");

        assertSame(first, second);
    }

    @Test
    void aPlayerIdleLongerThanTheTimeoutIsEvicted() {
        PlayerRegistry registry = new PlayerRegistry();
        UUID id = UUID.randomUUID();
        registry.getOrCreate(id, "Steve");
        registry.touch(id, 1_000L);

        List<UUID> evicted = registry.evictIdleOlderThan(500L, 2_000L); // idle 1000ms > 500ms timeout

        assertEquals(List.of(id), evicted);
        assertNull(registry.get(id));
    }

    @Test
    void aRecentlyActivePlayerIsNotEvicted() {
        PlayerRegistry registry = new PlayerRegistry();
        UUID id = UUID.randomUUID();
        registry.getOrCreate(id, "Steve");
        registry.touch(id, 1_900L);

        List<UUID> evicted = registry.evictIdleOlderThan(500L, 2_000L); // idle 100ms < 500ms timeout

        assertTrue(evicted.isEmpty());
    }

    @Test
    void markQuitStillAllowsEvictionButDoesNotImmediatelyRemove() {
        PlayerRegistry registry = new PlayerRegistry();
        UUID id = UUID.randomUUID();
        registry.getOrCreate(id, "Steve");
        registry.touch(id, 1_000L);

        registry.markQuit(id);

        assertTrue(registry.get(id) != null, "quitting must not immediately drop VL/history - see spec: "
                + "cross-server switch should not reset a player's violation state");
    }
}
