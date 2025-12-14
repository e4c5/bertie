package com.raditha.dedup.config;

import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.util.List;
import java.util.Map;

/**
 * Loads duplication detector configuration from Settings (generator.yml) with
 * CLI overrides.
 * 
 * Configuration priority: CLI arguments > generator.yml > defaults
 */
public class DuplicationDetectorSettings {

    private static final String CONFIG_KEY = "duplication_detector";

    /**
     * Load configuration from Settings, applying CLI overrides where provided.
     * 
     * CLI parameters should already be applied to Settings via setProperty() before
     * calling this.
     * 
     * @param minLinesCLI  CLI min lines (0 = use YAML/default)
     * @param thresholdCLI CLI threshold percentage 0-100 (0 = use YAML/default)
     * @param presetCLI    CLI preset name (null = use YAML/default)
     * @return Complete duplication configuration
     */
    public static DuplicationConfig loadConfig(int minLinesCLI, int thresholdCLI, String presetCLI) {
        // Get config from generator.yml
        Object yamlConfigRaw = Settings.getProperty(CONFIG_KEY);

        if (!(yamlConfigRaw instanceof Map)) {
            // No YAML config, use CLI or defaults
            return createFromCLI(minLinesCLI, thresholdCLI, presetCLI);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) yamlConfigRaw;

        // Determine preset (CLI > YAML)
        String preset = presetCLI != null ? presetCLI : getString(config, "preset", null);

        // Use preset if specified
        if (preset != null) {
            return switch (preset) {
                case "strict" -> DuplicationConfig.strict();
                case "lenient" -> DuplicationConfig.lenient();
                default -> DuplicationConfig.moderate();
            };
        }

        // Build custom config (CLI overrides YAML overrides defaults)
        int minLines = minLinesCLI != 0 ? minLinesCLI : getInt(config, "min_lines", 5);
        double threshold = thresholdCLI != 0 ? thresholdCLI / 100.0 : getDouble(config, "threshold", 0.75);

        boolean includeTests = getBoolean(config, "include_tests", false);

        List<String> excludePatterns = getListString(config, "exclude_patterns");
        if (excludePatterns.isEmpty()) {
            excludePatterns = getDefaultExcludes();
        }

        // Build similarity weights (from YAML or defaults)
        SimilarityWeights weights = buildWeights(config);

        return new DuplicationConfig(
                minLines,
                threshold,
                weights,
                includeTests,
                excludePatterns);
    }

    /**
     * Get target class for focused analysis from YAML configuration.
     * If specified, only this class will be analyzed.
     * 
     * @return fully qualified class name or null if not specified
     */
    public static String getTargetClass() {
        Object yamlConfigRaw = Settings.getProperty(CONFIG_KEY);
        if (yamlConfigRaw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) yamlConfigRaw;
            return getString(config, "target_class", null);
        }
        return null;
    }

    private static DuplicationConfig createFromCLI(int minLinesCLI, int thresholdCLI, String presetCLI) {
        // Use preset if specified
        if (presetCLI != null) {
            return switch (presetCLI) {
                case "strict" -> DuplicationConfig.strict();
                case "lenient" -> DuplicationConfig.lenient();
                default -> DuplicationConfig.moderate();
            };
        }

        // Use CLI values or defaults
        int minLines = minLinesCLI != 0 ? minLinesCLI : 5;
        double threshold = thresholdCLI != 0 ? thresholdCLI / 100.0 : 0.75;
        boolean includeTests = false;
        List<String> excludePatterns = getDefaultExcludes();

        SimilarityWeights weights = SimilarityWeights.balanced();

        return new DuplicationConfig(
                minLines,
                threshold,
                weights,
                includeTests,
                excludePatterns);
    }

    private static SimilarityWeights buildWeights(Map<String, Object> config) {
        Object weightsObj = config.get("similarity_weights");
        if (weightsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> weightsMap = (Map<String, Object>) weightsObj;
            double lcs = getDouble(weightsMap, "lcs", 0.4);
            double levenshtein = getDouble(weightsMap, "levenshtein", 0.3);
            double structural = getDouble(weightsMap, "structural", 0.3);
            return new SimilarityWeights(lcs, levenshtein, structural);
        }
        return SimilarityWeights.balanced();
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private static double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value != null) {
            return value.toString();
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getListString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            return (List<String>) value;
        }
        return List.of();
    }

    private static List<String> getDefaultExcludes() {
        return List.of(
                "**/test/**",
                "**/*Test.java",
                "**/target/**",
                "**/build/**",
                "**/.git/**");
    }
}
