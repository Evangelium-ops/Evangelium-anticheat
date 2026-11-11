package io.wildsmp.anticheat.core.check.movement;

import java.util.UUID;

/**
 * The delivery mechanism for a high-confidence movement check's snap-back correction - separate
 * from {@link io.wildsmp.anticheat.core.violation.ViolationManager}'s listener pipeline, since that
 * pipeline is level-triggered off accumulated VL (not a single evaluation's confidence) and its
 * {@code Violation} record carries no position data.
 */
public interface MovementCorrectionSink {
    void correctPosition(UUID playerId, double x, double y, double z);
}
