package io.wildsmp.anticheat.core.violation;

import io.wildsmp.anticheat.core.check.CheckCategory;

import java.util.UUID;

/**
 * The punishment handoff point. Carries everything an external listener (the separately-built
 * ban system) needs to decide what to do - this framework's job ends once this is fired.
 */
public record Violation(
        UUID playerId,
        String playerName,
        String checkName,
        CheckCategory category,
        double vl,
        double threshold,
        String details,
        long timestampMillis) {
}
