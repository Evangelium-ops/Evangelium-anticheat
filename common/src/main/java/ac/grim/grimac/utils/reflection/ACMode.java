package ac.grim.grimac.utils.reflection;

/** Engine selection is fixed for a backend process; packet ordering stays on its connection thread. */
public final class ACMode {
    private ACMode() {}
    public static boolean unified() { return Boolean.getBoolean("launcher.anticheat"); }
    public static boolean movementEnabled() {
        return !unified() || Boolean.parseBoolean(System.getProperty("launcher.anticheat.movement", "true"));
    }
    public static boolean grimCheckEnabled(boolean unified, String className) {
        return !unified || !(className.startsWith("ac.grim.grimac.checks.impl.combat.")
                || className.startsWith("ac.grim.grimac.checks.impl.aim."));
    }
}
