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
        // Get config from generator.yml (nested)
        Object yamlConfigRaw = Settings.getProperty(CONFIG_KEY);
        Map<String, Object> config = null;

        if (yamlConfigRaw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) yamlConfigRaw;
            config = map;
        }

        // Determine preset (CLI > YAML nested > YAML flat)
        String preset = presetCLI;
        if (preset == null) {
            if (config != null) {
                preset = getString(config, "preset", null);
            }
            if (preset == null) {
                preset = getGlobalString("preset", null); // Flat fallback
            }
        }

        // Use preset if specified
        if (preset != null) {
            return switch (preset) {
                case "strict" -> DuplicationConfig.strict();
                case "lenient" -> DuplicationConfig.lenient();
                default -> DuplicationConfig.moderate();
            };
        }

        // Build custom config (CLI > YAML nested > YAML flat > defaults)
        int minLines = minLinesCLI != 0 ? minLinesCLI
                : (config != null ? getInt(config, "min_lines", 5) : getGlobalInt("min_lines", 5));

        double thresholdDefault = (config != null ? getDouble(config, "threshold", 0.75)
                : getGlobalDouble("threshold", 0.75));
        if (thresholdDefault > 1.0) {
            thresholdDefault /= 100.0;
        }
        double threshold = thresholdCLI != 0 ? thresholdCLI / 100.0 : thresholdDefault;

        boolean includeTests = (config != null ? getBoolean(config, "include_tests", false)
                : getGlobalBoolean("include_tests", false));

        List<String> excludePatterns;
        if (config != null && config.containsKey("exclude_patterns")) {
            excludePatterns = getListString(config, "exclude_patterns");
        } else {
            excludePatterns = getGlobalListString("exclude_patterns");
        }

        if (excludePatterns == null || excludePatterns.isEmpty()) {
            excludePatterns = getDefaultExcludes();
        }

        // Build similarity weights (from YAML or defaults)
        SimilarityWeights weights = buildWeights(config); // Simplified, ignore flat weights for now or impl later if
                                                          // needed

        // Other settings
        int maxWindowGrowth = (config != null ? getInt(config, "max_window_growth", 5)
                : getGlobalInt("max_window_growth", 5));
        boolean maximalOnly = (config != null ? getBoolean(config, "maximal_only", true)
                : getGlobalBoolean("maximal_only", true));

        return new DuplicationConfig(
                minLines,
                threshold,
                weights,
                includeTests,
                excludePatterns,
                maxWindowGrowth,
                maximalOnly,
                true); // enableBoundaryRefinement
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
        // Fallback to top-level property
        return getGlobalString("target_class", null);
    }

    // --- Global Helpers ---

    private static String getGlobalString(String key, String defaultValue) {
        Object val = Settings.getProperty(key);
        return val != null ? val.toString() : defaultValue;
    }

    private static int getGlobalInt(String key, int defaultValue) {
        Object val = Settings.getProperty(key);
        if (val instanceof Number n)
            return n.intValue();
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
            }
        }
        return defaultValue;
    }

    private static double getGlobalDouble(String key, double defaultValue) {
        Object val = Settings.getProperty(key);
        if (val instanceof Number n)
            return n.doubleValue();
        if (val instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
            }
        }
        return defaultValue;
    }

    private static boolean getGlobalBoolean(String key, boolean defaultValue) {
        Object val = Settings.getProperty(key);
        if (val instanceof Boolean b)
            return b;
        if (val instanceof String s)
            return Boolean.parseBoolean(s);
        return defaultValue;
    }

    private static List<String> getGlobalListString(String key) {
        Object val = Settings.getProperty(key);
        if (val instanceof List) {
            // Settings.getListString returns an empty list if not found, but we want null
            // for consistency with other getGlobalX
            // and to allow getDefaultExcludes to be called.
            if (((List<?>) val).isEmpty()) {
                return null;
            }
            return (List<String>) val;
        }
        return null;
    }

    private static SimilarityWeights buildWeights(Map<String, Object> config) {
        if (config == null) {
            return SimilarityWeights.balanced();
        }
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
        if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    private static double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return defaultValue;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
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
