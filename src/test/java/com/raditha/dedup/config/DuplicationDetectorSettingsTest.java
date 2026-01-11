package com.raditha.dedup.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DuplicationDetectorSettingsTest {

    private static final String CONFIG_KEY = "duplication_detector";

    @BeforeEach
    void setUp() throws java.io.IOException {
        // Clear/Reset settings before each test
        Settings.loadConfigMap();
        sa.com.cloudsolutions.antikythera.configuration.SettingsHelper.clear();
    }

    @AfterEach
    void tearDown() throws java.io.IOException {
        // Ensure settings are clean after test
        sa.com.cloudsolutions.antikythera.configuration.SettingsHelper.clear();
        Settings.loadConfigMap();
    }

    @Test
    void testLoadConfig_Defaults() {
        // When no config is set and no CLI args provided
        DuplicationConfig config = DuplicationDetectorSettings.loadConfig(0, 0, null);

        assertNotNull(config);
        assertEquals(5, config.minLines()); // Default min lines
        assertEquals(0.75, config.threshold(), 0.001); // Default threshold
        assertFalse(config.includeTests()); // Default includeTests
        assertTrue(config.enableLSH()); // Default enableLSH
        assertEquals(5, config.maxWindowGrowth()); // Default window growth
    }

    @Test
    void testLoadConfig_CliOverrides() {
        // CLI args should override everything
        int cliMinLines = 10;
        int cliThreshold = 85;

        DuplicationConfig config = DuplicationDetectorSettings.loadConfig(cliMinLines, cliThreshold, null);

        assertEquals(10, config.minLines());
        assertEquals(0.85, config.threshold(), 0.001);
    }

    @Test
    void testLoadConfig_Presets() {
        // Test Strict preset
        DuplicationConfig strict = DuplicationDetectorSettings.loadConfig(0, 0, "strict");
        assertEquals(7, strict.minLines());
        assertEquals(0.90, strict.threshold(), 0.001);
        assertEquals(3, strict.maxWindowGrowth());

        // Test Lenient preset
        DuplicationConfig lenient = DuplicationDetectorSettings.loadConfig(0, 0, "lenient");
        assertEquals(3, lenient.minLines());
        assertEquals(0.60, lenient.threshold(), 0.001);
        assertEquals(7, lenient.maxWindowGrowth());

        // Test unknown preset (should default to moderate/default)
        DuplicationConfig unknown = DuplicationDetectorSettings.loadConfig(0, 0, "unknown");
        assertEquals(5, unknown.minLines());
        assertEquals(0.75, unknown.threshold(), 0.001);
    }

    @Test
    void testLoadConfig_YamlMapConfig() {
        // Setup mock YAML config map under "duplication_detector" key
        Map<String, Object> yamlConfig = new HashMap<>();
        yamlConfig.put("min_lines", 8);
        yamlConfig.put("threshold", 0.80);
        yamlConfig.put("include_tests", true);
        yamlConfig.put("enable_lsh", false);
        yamlConfig.put("exclude_patterns", List.of("**/generated/**", "**/*DTO.java"));

        // Nested weights
        Map<String, Object> weights = new HashMap<>();
        weights.put("lcs", 0.5);
        weights.put("levenshtein", 0.5);
        weights.put("structural", 0.0);
        yamlConfig.put("similarity_weights", weights);

        Settings.setProperty(CONFIG_KEY, yamlConfig);

        DuplicationConfig config = DuplicationDetectorSettings.loadConfig(0, 0, null);

        assertEquals(8, config.minLines());
        assertEquals(0.80, config.threshold(), 0.001);
        assertTrue(config.includeTests());
        assertFalse(config.enableLSH());

        List<String> excludes = config.excludePatterns();
        assertNotNull(excludes);
        assertTrue(excludes.contains("**/generated/**"));
        assertTrue(excludes.contains("**/*DTO.java"));

        SimilarityWeights w = config.weights();
        assertEquals(0.5, w.lcsWeight(), 0.001);
        assertEquals(0.5, w.levenshteinWeight(), 0.001);
        assertEquals(0.0, w.structuralWeight(), 0.001);
    }

    @Test
    void testLoadConfig_GlobalFlatConfig() {
        // Setup flat global properties (legacy/fallback support)
        Settings.setProperty("min_lines", 12);
        Settings.setProperty("threshold", "0.65"); // Test String parsing
        Settings.setProperty("include_tests", "true"); // Test String parsing

        DuplicationConfig config = DuplicationDetectorSettings.loadConfig(0, 0, null);

        assertEquals(12, config.minLines());
        assertEquals(0.65, config.threshold(), 0.001);
        assertTrue(config.includeTests());
    }

    @Test
    void testLoadConfig_PriorityOrder() {
        // CLI > YAML Nested > Global/Default

        // 1. Set global defaults
        Settings.setProperty("min_lines", 3);

        // 2. Set nested YAML config
        Map<String, Object> yamlConfig = new HashMap<>();
        yamlConfig.put("min_lines", 5);
        Settings.setProperty(CONFIG_KEY, yamlConfig);

        // 3. CLI Override
        int cliMinLines = 20;

        // Verify CLI wins
        DuplicationConfig config = DuplicationDetectorSettings.loadConfig(cliMinLines, 0, null);
        assertEquals(20, config.minLines());

        // Verify Nested wins over Global if CLI is missing
        config = DuplicationDetectorSettings.loadConfig(0, 0, null);
        assertEquals(5, config.minLines());

        // Remove nested, verify Global wins
        Settings.setProperty(CONFIG_KEY, null);
        config = DuplicationDetectorSettings.loadConfig(0, 0, null);
        assertEquals(3, config.minLines());
    }

    @Test
    void testGetTargetClass() {
        // Test null default
        assertNull(DuplicationDetectorSettings.getTargetClass());

        // Test nested config
        Map<String, Object> yamlConfig = new HashMap<>();
        yamlConfig.put("target_class", "com.example.MyClass");
        Settings.setProperty(CONFIG_KEY, yamlConfig);

        assertEquals("com.example.MyClass", DuplicationDetectorSettings.getTargetClass());

        // Test global fallback (if nested is missing)
        Settings.setProperty(CONFIG_KEY, null);
        Settings.setProperty("target_class", "com.example.GlobalClass");
        assertEquals("com.example.GlobalClass", DuplicationDetectorSettings.getTargetClass());
    }

    @Test
    void testLoadConfig_InvalidInt_ThrowsException() {
        Settings.setProperty("min_lines", "invalid_int");
        assertThrows(NumberFormatException.class, () -> DuplicationDetectorSettings.loadConfig(0, 0, null));
    }

    @Test
    void testLoadConfig_InvalidDouble_ThrowsException() {
        Settings.setProperty("threshold", "invalid_double");
        assertThrows(NumberFormatException.class, () -> DuplicationDetectorSettings.loadConfig(0, 0, null));
    }
}
