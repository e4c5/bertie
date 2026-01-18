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
        DuplicationDetectorSettings.loadConfig(0, 0, null);

        assertEquals(5, DuplicationDetectorSettings.getMinLines()); // Default min lines
        assertEquals(0.75, DuplicationDetectorSettings.getThreshold(), 0.001); // Default threshold
        assertTrue(DuplicationDetectorSettings.getEnableLSH()); // Default enableLSH
        assertEquals(5, DuplicationDetectorSettings.getMaxWindowGrowth()); // Default window growth
    }

    @Test
    void testLoadConfig_CliOverrides() {
        // CLI args should override everything
        int cliMinLines = 10;
        int cliThreshold = 85;

        DuplicationDetectorSettings.loadConfig(cliMinLines, cliThreshold, null);

        assertEquals(10, DuplicationDetectorSettings.getMinLines());
        assertEquals(0.85, DuplicationDetectorSettings.getThreshold(), 0.001);
    }

    @Test
    void testLoadConfig_Presets() {
        // Test Strict preset
        DuplicationDetectorSettings.loadConfig(0, 0, "strict");
        // Note: Preset values are not directly testable with current implementation
        // This test verifies loadConfig doesn't throw exceptions
        
        // Test Lenient preset
        DuplicationDetectorSettings.loadConfig(0, 0, "lenient");
        
        // Test unknown preset (should default to moderate/default)
        DuplicationDetectorSettings.loadConfig(0, 0, "unknown");
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

        DuplicationDetectorSettings.loadConfig(0, 0, null);

        assertEquals(8, DuplicationDetectorSettings.getMinLines());
        assertEquals(0.80, DuplicationDetectorSettings.getThreshold(), 0.001);
        assertFalse(DuplicationDetectorSettings.getEnableLSH());

        // Note: excludePatterns and weights are not directly testable via static methods
        // These would need to be tested via shouldExclude() method
    }

    @Test
    void testLoadConfig_GlobalFlatConfig() {
        // Setup flat global properties (legacy/fallback support)
        Settings.setProperty("min_lines", 12);
        Settings.setProperty("threshold", "0.65"); // Test String parsing
        Settings.setProperty("include_tests", "true"); // Test String parsing

        DuplicationDetectorSettings.loadConfig(0, 0, null);

        assertEquals(12, DuplicationDetectorSettings.getMinLines());
        assertEquals(0.65, DuplicationDetectorSettings.getThreshold(), 0.001);
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
        DuplicationDetectorSettings.loadConfig(cliMinLines, 0, null);
        assertEquals(20, DuplicationDetectorSettings.getMinLines());

        // Verify Nested wins over Global if CLI is missing
        DuplicationDetectorSettings.loadConfig(0, 0, null);
        assertEquals(5, DuplicationDetectorSettings.getMinLines());

        // Remove nested, verify Global wins
        Settings.setProperty(CONFIG_KEY, null);
        DuplicationDetectorSettings.loadConfig(0, 0, null);
        assertEquals(3, DuplicationDetectorSettings.getMinLines());
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
