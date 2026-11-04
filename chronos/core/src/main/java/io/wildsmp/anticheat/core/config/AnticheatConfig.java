package io.wildsmp.anticheat.core.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.Reader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AnticheatConfig {

    public record CheckSettings(boolean enabled, double threshold, double decayRate) {
    }

    private final CheckSettings defaultSettings;
    private volatile Map<String, CheckSettings> overrides = new ConcurrentHashMap<>();

    private AnticheatConfig(CheckSettings defaultSettings) {
        this.defaultSettings = defaultSettings;
    }

    public static AnticheatConfig withDefaults(CheckSettings defaultSettings) {
        return new AnticheatConfig(defaultSettings);
    }

    /**
     * Parses {@code checks: <name>: {enabled, threshold, decay-rate}} from YAML and replaces the
     * current overrides entirely. On malformed YAML, logs nothing itself (the caller/adapter is
     * responsible for surfacing that) and simply keeps the previous settings rather than wiping
     * a working config on a bad reload.
     */
    public void load(Reader yamlReader) {
        Map<String, CheckSettings> parsed;
        try {
            parsed = parse(yamlReader);
        } catch (YAMLException | ClassCastException e) {
            return;
        }
        this.overrides = parsed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, CheckSettings> parse(Reader yamlReader) {
        Yaml yaml = new Yaml();
        Object root = yaml.load(yamlReader);
        Map<String, CheckSettings> parsed = new ConcurrentHashMap<>();
        if (!(root instanceof Map<?, ?> rootMap)) {
            return parsed;
        }
        Object checksObj = rootMap.get("checks");
        if (!(checksObj instanceof Map<?, ?> checksMap)) {
            return parsed;
        }
        for (Map.Entry<?, ?> entry : checksMap.entrySet()) {
            String checkName = String.valueOf(entry.getKey());
            if (entry.getValue() instanceof Map<?, ?> cfg) {
                boolean enabled = boolAt(cfg, "enabled", defaultSettings.enabled());
                double threshold = doubleAt(cfg, "threshold", defaultSettings.threshold());
                double decay = doubleAt(cfg, "decay-rate", defaultSettings.decayRate());
                parsed.put(checkName, new CheckSettings(enabled, threshold, decay));
            }
        }
        return parsed;
    }

    private static boolean boolAt(Map<?, ?> m, String key, boolean def) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : def;
    }

    private static double doubleAt(Map<?, ?> m, String key, double def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private CheckSettings settingsFor(String checkName) {
        return overrides.getOrDefault(checkName, defaultSettings);
    }

    public boolean enabled(String checkName) {
        return settingsFor(checkName).enabled();
    }

    public double threshold(String checkName) {
        return settingsFor(checkName).threshold();
    }

    public double decayRate(String checkName) {
        return settingsFor(checkName).decayRate();
    }
}
