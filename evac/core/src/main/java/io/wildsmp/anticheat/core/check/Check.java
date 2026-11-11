package io.wildsmp.anticheat.core.check;

import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.ViolationManager;

/**
 * The contract every future check (combat, movement, ...) implements. Core defines and
 * dispatches to this contract; it implements no concrete detection logic itself.
 */
public abstract class Check {

    private final String name;
    private final CheckCategory category;
    private volatile ViolationManager violationManager;

    protected Check(String name, CheckCategory category) {
        this.name = name;
        this.category = category;
    }

    public final String name() {
        return name;
    }

    public final CheckCategory category() {
        return category;
    }

    /** Override to react to an incoming packet. Default no-op so subclasses only implement what they need. */
    public void onPacketReceive(PlayerData data, InboundPacket packet) {
    }

    /** Override to run once per server tick. Default no-op so subclasses only implement what they need. */
    public void onTick(PlayerData data) {
    }

    protected final void flag(PlayerData data, double vlIncrement, String details) {
        ViolationManager manager = this.violationManager;
        if (manager == null) {
            throw new IllegalStateException("Check '" + name + "' is not registered with a ViolationManager");
        }
        manager.addViolation(data, this, vlIncrement, details);
    }

    /** Test-only hook - real checks call the protected {@link #flag} from their own overrides. */
    void testFlag(PlayerData data, double vlIncrement, String details) {
        flag(data, vlIncrement, details);
    }

    public final void bindViolationManager(ViolationManager manager) {
        this.violationManager = manager;
    }
}
