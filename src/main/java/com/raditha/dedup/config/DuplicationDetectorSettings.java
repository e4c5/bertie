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
    private static final String CLI_CONFIG_KEY = "duplication_detector_cli";

    /**
     * Load configuration from Settings, applying CLI overrides where provided.
     * 
     * CLI parameters should already be applied to Settings via setProperty() before
     * calling this.
     * 
     * @param minLinesCLI  CLI min lines (0 = use YAML/default)
     * @param thresholdCLI CLI threshold percentage 0-100 (0 = use YAML/default)
     * @param presetCLI    CLI preset name (null = use YAML/default)
     */
    public static void loadConfig(int minLinesCLI, int thresholdCLI, String presetCLI) {
        // Store CLI parameters in a separate config map to preserve YAML config
        // Get or create the CLI config map
        Object cliConfigRaw = Settings.getProperty(CLI_CONFIG_KEY);
        @SuppressWarnings("unchecked")
        Map<String, Object> cliConfig = (cliConfigRaw instanceof Map) 
            ? new java.util.HashMap<>((Map<String, Object>) cliConfigRaw)
            : new java.util.HashMap<>();
        
        // Store or remove CLI parameters
        if (minLinesCLI != 0) {
            cliConfig.put("min_lines", minLinesCLI);
        } else {
            cliConfig.remove("min_lines");
        }
        
        if (thresholdCLI != 0) {
            cliConfig.put("threshold", thresholdCLI / 100.0);
        } else {
            cliConfig.remove("threshold");
        }
        
        if (presetCLI != null) {
            cliConfig.put("preset", presetCLI);
        } else {
            cliConfig.remove("preset");
        }
        
        // Update the Settings with the modified CLI config
        Settings.setProperty(CLI_CONFIG_KEY, cliConfig);
    }

    /**
     * Get target class for focused analysis from YAML configuration.
     * If specified, only this class will be analyzed.
     * 
     * @return fully qualified class name or null if not specified
     */
    public static String getTargetClass() {
        return getOverriddenString("target_class", null);
    }

    // --- Global Helpers ---
    
    // Helper to get value with priority: CLI > YAML Nested > Global > Default
    
    private static int getOverriddenInt(String key, int defaultValue) {
        // 1. Check CLI Config
        Map<String, Object> cliConfig = extractConfig(Settings.getProperty(CLI_CONFIG_KEY));
        if (cliConfig != null && cliConfig.containsKey(key)) {
            return getInt(cliConfig, key, defaultValue);
        }
        
        // 2. Check YAML Nested Config
        Map<String, Object> yamlConfig = extractConfig(Settings.getProperty(CONFIG_KEY));
        if (yamlConfig != null && yamlConfig.containsKey(key)) {
            return getInt(yamlConfig, key, defaultValue);
        }
        
        // 3. Global / Fallback
        return getGlobalInt(key, defaultValue);
    }

    private static double getOverriddenDouble(String key, double defaultValue) {
        // 1. Check CLI Config
        Map<String, Object> cliConfig = extractConfig(Settings.getProperty(CLI_CONFIG_KEY));
        if (cliConfig != null && cliConfig.containsKey(key)) {
            return getDouble(cliConfig, key, defaultValue);
        }
        
        // 2. Check YAML Nested Config
        Map<String, Object> yamlConfig = extractConfig(Settings.getProperty(CONFIG_KEY));
        if (yamlConfig != null && yamlConfig.containsKey(key)) {
            return getDouble(yamlConfig, key, defaultValue);
        }
        
        // 3. Global / Fallback
        return getGlobalDouble(key, defaultValue);
    }

    private static boolean getOverriddenBoolean(String key, boolean defaultValue) {
         // 1. Check CLI Config
        Map<String, Object> cliConfig = extractConfig(Settings.getProperty(CLI_CONFIG_KEY));
        if (cliConfig != null && cliConfig.containsKey(key)) {
            return getBoolean(cliConfig, key, defaultValue);
        }
        
        // 2. Check YAML Nested Config
        Map<String, Object> yamlConfig = extractConfig(Settings.getProperty(CONFIG_KEY));
        if (yamlConfig != null && yamlConfig.containsKey(key)) {
            return getBoolean(yamlConfig, key, defaultValue);
        }
        
        // 3. Global / Fallback
        return getGlobalBoolean(key, defaultValue);
    }
    
    private static String getOverriddenString(String key, String defaultValue) {
         // 1. Check CLI Config
        Map<String, Object> cliConfig = extractConfig(Settings.getProperty(CLI_CONFIG_KEY));
        if (cliConfig != null && cliConfig.containsKey(key)) {
            return getString(cliConfig, key, defaultValue);
        }
        
        // 2. Check YAML Nested Config
        Map<String, Object> yamlConfig = extractConfig(Settings.getProperty(CONFIG_KEY));
        if (yamlConfig != null && yamlConfig.containsKey(key)) {
            return getString(yamlConfig, key, defaultValue);
        }
        
        // 3. Global / Fallback
        return getGlobalString(key, defaultValue);
    }

    private static String getGlobalString(String key, String defaultValue) {
        Object val = Settings.getProperty(key);
        return val != null ? val.toString() : defaultValue;
    }

    private static int getGlobalInt(String key, int defaultValue) {
        Object val = Settings.getProperty(key);
        if (val instanceof Number n)
            return n.intValue();
        if (val instanceof String s) {
            return Integer.parseInt(s);
        }
        return defaultValue;
    }

    private static double getGlobalDouble(String key, double defaultValue) {
        Object val = Settings.getProperty(key);
        if (val instanceof Number n)
            return n.doubleValue();
        if (val instanceof String s) {
            return Double.parseDouble(s);
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

    // ========== Public Static Getters ==========
    // These methods provide direct access to configuration values
    // without needing to pass DuplicationConfig around

    /**
     * Get minimum lines for duplicate detection.
     * @return minimum number of lines
     */
    public static int getMinLines() {
        return getOverriddenInt("min_lines", 5);
    }

    /**
     * Get similarity threshold for duplicate detection.
     * @return threshold value (0.0-1.0)
     */
    public static double getThreshold() {
        double threshold = getOverriddenDouble("threshold", 0.75);
        if (threshold > 1.0) {
            threshold /= 100.0;
        }
        return threshold;
    }

    /**
     * Get similarity weights for duplicate detection.
     * @return configured similarity weights
     */
    public static SimilarityWeights getWeights() {
        // Weights likely not overridden via simple CLI args, but could be in future
        Map<String, Object> yamlConfig = extractConfig(Settings.getProperty(CONFIG_KEY));
        return buildWeights(yamlConfig);
    }

    /**
     * Get maximum window growth for duplicate detection.
     * @return max window growth value
     */
    public static int getMaxWindowGrowth() {
        return getOverriddenInt("max_window_growth", 5);
    }

    /**
     * Get maximal only flag for duplicate detection.
     * @return true if only maximal sequences should be extracted
     */
    public static boolean getMaximalOnly() {
        return getOverriddenBoolean("maximal_only", true);
    }

    /**
     * Get boundary refinement flag.
     * @return true if boundary refinement is enabled
     */
    public static boolean getEnableBoundaryRefinement() {
        // Always enabled for now
        return true;
    }

    /**
     * Get LSH enabled flag.
     * @return true if LSH is enabled
     */
    public static boolean getEnableLSH() {
        return getOverriddenBoolean("enable_lsh", true);
    }

    /**
     * Check if a file path should be excluded from analysis.
     * @param filePath the file path to check
     * @return true if the file should be excluded
     */
    public static boolean shouldExclude(String filePath) {
        Object yamlConfigRaw = Settings.getProperty(CONFIG_KEY);
        Map<String, Object> config = extractConfig(yamlConfigRaw);
        
        List<String> excludePatterns;
        if (config != null && config.containsKey("exclude_patterns")) {
            excludePatterns = getListString(config, "exclude_patterns");
        } else {
            excludePatterns = getGlobalListString("exclude_patterns");
        }

        if (excludePatterns == null || excludePatterns.isEmpty()) {
            excludePatterns = getDefaultExcludes();
        }

        for (String pattern : excludePatterns) {
            if (matchesGlobPattern(filePath, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Simple glob pattern matching.
     * Supports ** and * wildcards.
     */
    private static boolean matchesGlobPattern(String path, String pattern) {
        // Convert glob pattern to regex
        String regex = pattern
                .replace(".", "\\.")
                .replace("**", ".*")
                .replace("*", "[^/]*");
        return path.matches(regex);
    }

    /**
     * Extract config map from YAML config raw object.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractConfig(Object yamlConfigRaw) {
        if (yamlConfigRaw instanceof Map) {
            return (Map<String, Object>) yamlConfigRaw;
        }
        return null;
    }
}
