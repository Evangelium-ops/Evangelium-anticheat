package io.wildsmp.anticheat.core.config;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnticheatConfigTest {

    private static final String SAMPLE_YAML = """
            checks:
              KillauraA:
                enabled: true
                threshold: 15.0
                decay-rate: 2.0
              SpeedA:
                enabled: false
                threshold: 8.0
                decay-rate: 1.0
            """;

    @Test
    void loadsPerCheckOverridesFromYaml() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 10.0, 1.0));

        config.load(new StringReader(SAMPLE_YAML));

        assertTrue(config.enabled("KillauraA"));
        assertEquals(15.0, config.threshold("KillauraA"), 0.001);
        assertEquals(2.0, config.decayRate("KillauraA"), 0.001);
        assertFalse(config.enabled("SpeedA"));
    }

    @Test
    void fallsBackToDefaultsForAnUnknownCheckName() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 10.0, 1.0));

        config.load(new StringReader(SAMPLE_YAML));

        assertTrue(config.enabled("SomeCheckNotInTheYaml"));
        assertEquals(10.0, config.threshold("SomeCheckNotInTheYaml"), 0.001);
        assertEquals(1.0, config.decayRate("SomeCheckNotInTheYaml"), 0.001);
    }

    @Test
    void reloadingReplacesThePreviousOverridesEntirely() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 10.0, 1.0));
        config.load(new StringReader(SAMPLE_YAML));

        config.load(new StringReader("checks:\n  KillauraA:\n    threshold: 99.0\n"));

        assertEquals(99.0, config.threshold("KillauraA"), 0.001);
    }

    @Test
    void malformedYamlDoesNotCrashAndKeepsPreviousSettings() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 10.0, 1.0));
        config.load(new StringReader(SAMPLE_YAML));

        config.load(new StringReader("not: [valid, yaml: structure"));

        assertEquals(15.0, config.threshold("KillauraA"), 0.001);
    }
}
