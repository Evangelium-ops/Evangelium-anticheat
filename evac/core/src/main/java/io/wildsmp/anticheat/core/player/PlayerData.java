package io.wildsmp.anticheat.core.player;

import io.wildsmp.anticheat.core.timing.TickTimingState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One instance per online player, created on join and destroyed on leave by the platform
 * adapter. Holds everything future checks need, plus a per-check state map so individual checks
 * can stash their own data without this class needing to know every check that exists.
 */
public final class PlayerData {

    public static final int MAX_MOVEMENT_HISTORY = 40;
    public static final int MAX_COMBAT_HISTORY = 20;

    private final UUID id;
    private volatile String name;
    private final Deque<MovementSample> movementHistory = new ArrayDeque<>(MAX_MOVEMENT_HISTORY);
    private final Deque<CombatSample> combatHistory = new ArrayDeque<>(MAX_COMBAT_HISTORY);
    private final TickTimingState timing = new TickTimingState();
    private final Map<String, Object> checkState = new ConcurrentHashMap<>();

    public PlayerData(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    /**
     * Corrects the name after the fact - a player's {@link PlayerData} can end up created (with a
     * placeholder name) by a movement/attack packet that arrives before their real join message,
     * e.g. a server restart while they're already connected. The real join handler calls this to
     * fix it up once the actual name is known, rather than being stuck with the placeholder.
     */
    public void updateName(String name) {
        this.name = name;
    }

    public synchronized void recordMovement(MovementSample sample) {
        movementHistory.addLast(sample);
        while (movementHistory.size() > MAX_MOVEMENT_HISTORY) {
            movementHistory.removeFirst();
        }
    }

    public synchronized Deque<MovementSample> movementHistory() {
        return new ArrayDeque<>(movementHistory);
    }

    public synchronized void recordCombat(CombatSample sample) {
        combatHistory.addLast(sample);
        while (combatHistory.size() > MAX_COMBAT_HISTORY) {
            combatHistory.removeFirst();
        }
    }

    public synchronized Deque<CombatSample> combatHistory() {
        return new ArrayDeque<>(combatHistory);
    }

    public TickTimingState timing() {
        return timing;
    }

    @SuppressWarnings("unchecked")
    public <T> T computeCheckState(String checkName, Supplier<T> defaultFactory) {
        return (T) checkState.computeIfAbsent(checkName, k -> defaultFactory.get());
    }

    public void clearCheckState(String checkName) {
        checkState.remove(checkName);
    }
}
