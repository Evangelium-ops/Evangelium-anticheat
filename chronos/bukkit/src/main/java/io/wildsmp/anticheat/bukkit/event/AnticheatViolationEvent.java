package io.wildsmp.anticheat.bukkit.event;

import io.wildsmp.anticheat.core.violation.Violation;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a check's VL crosses its configured threshold. Any plugin can listen for this like
 * a normal Bukkit event - the actual punishment path for our own network is the bsint-anticheat
 * dispatch in {@link io.wildsmp.anticheat.bukkit.bansystem.BanSystemIntegration}, which runs
 * independently of whether anything listens for this event.
 */
public final class AnticheatViolationEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Violation violation;

    public AnticheatViolationEvent(Violation violation) {
        // Always fired via FoliaSchedulerAdapter#runOnce, which lands on the main thread on
        // Paper/Spigot (runTaskLater) or the global region thread on Folia (runDelayed) - never
        // truly off-thread - so this must be a synchronous event or Bukkit's callEvent() rejects it.
        super(false);
        this.violation = violation;
    }

    public Violation violation() {
        return violation;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
