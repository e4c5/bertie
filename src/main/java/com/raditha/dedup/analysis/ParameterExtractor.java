package com.raditha.dedup.analysis;

import com.raditha.dedup.model.ParameterSpec;
import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.Variation;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.VariationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Extracts parameter specifications from variations for method extraction.
 */
public class ParameterExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ParameterExtractor.class);
    private static final int MAX_PARAMETERS = 5;

    private final AIParameterNamer aiNamer;

    public ParameterExtractor() {
        this.aiNamer = new AIParameterNamer();
        if (aiNamer.isAvailable()) {
            logger.info("AI-powered parameter naming enabled");
        } else {
            logger.info("Using pattern-based parameter naming (AI not configured)");
        }
    }

    /**
     * Extract parameter specifications from variations.
     * 
     * @param variations        Variations to analyze
     * @param typeCompatibility Type compatibility analysis
     * @return List of suggested parameters
     */
    public List<ParameterSpec> extractParameters(
            VariationAnalysis variations,
            Map<String, String> typeCompatibility) {

        List<ParameterSpec> parameters = new ArrayList<>();

        // Group variations by position and iterate in ascending source order
        Map<Integer, List<Variation>> byPosition = variations.variations().stream()
                .collect(Collectors.groupingBy(Variation::alignedIndex1));

        var orderedPositions = new java.util.TreeSet<>(byPosition.keySet());
        for (Integer position : orderedPositions) {
            List<Variation> positionVars = byPosition.get(position);

            if (positionVars == null || positionVars.isEmpty()) {
                continue;
            }

            // Get variation type
            VariationType varType = positionVars.get(0).type();

            // Skip control flow variations (can't parameterize)
            if (varType == VariationType.CONTROL_FLOW) {
                continue;
            }

            // Get type from compatibility analysis
            String type = typeCompatibility.getOrDefault("param" + position, "Object");

            // Infer parameter name
            String name = inferParameterName(positionVars, position);

            // Collect example values
            // Include both value1 (primary) AND value2 (duplicate)
            List<String> exampleValues = positionVars.stream()
                    .flatMap(v -> java.util.stream.Stream.of(v.value1(), v.value2()))
                    .distinct()
                    .limit(5)
                    .toList();

            // Use aligned position as the binding key (matches VariationTracker's valueBindings key)
            Integer variationIndex = position;

            Token t = null;
            // Use position (alignedIndex1) to fetch token from primary tokens attached to analysis
            if (variations.primaryTokens() != null && position >= 0 && position < variations.primaryTokens().size()) {
                t = variations.primaryTokens().get(position);
            }
            Integer line = t != null ? t.lineNumber() : null;
            Integer col = t != null ? t.columnNumber() : null;

            ParameterSpec spec = new ParameterSpec(
                    name,
                    type,
                    exampleValues,
                    variationIndex,
                    line,
                    col);

            logger.debug("[ParamExtractor] position={} type={} name={} examples={} varIdx={} loc=({}, {})",
                    position, type, name, exampleValues, variationIndex, line, col);

            parameters.add(spec);

            // Limit total parameters
            if (parameters.size() >= MAX_PARAMETERS) {
                break;
            }
        }

        // Preserve original source order of parameters; do NOT reorder by type
        return parameters;
    }

    /**
     * Infer parameter name using three-tier strategy: AI → Pattern → Generic.
     */
    private String inferParameterName(List<Variation> variations, int position) {
        if (variations.isEmpty()) {
            return "param" + position;
        }

        Variation first = variations.get(0);
        String type = first.inferredType();
        String value = first.value1();

        // Collect example values for AI
        List<String> exampleValues = variations.stream()
                .flatMap(v -> java.util.stream.Stream.of(v.value1(), v.value2()))
                .distinct()
                .limit(5)
                .toList();

        // Tier 1: Try AI naming (PRIMARY)
        if (aiNamer != null && aiNamer.isAvailable()) {
            String aiName = aiNamer.suggestName(value, exampleValues, null);
            if (aiName != null) {
                logger.debug("AI suggested '{}' for value '{}'", aiName, value);
                return aiName;
            }
        }

        // Tier 2: Try pattern-based naming (BACKUP)
        String patternName = inferFromPattern(value, first.type());
        if (patternName != null) {
            logger.debug("Pattern matched '{}' for value '{}'", patternName, value);
            return patternName;
        }

        // Tier 3: Generic fallback (LAST RESORT)
        String genericName = generateGenericName(type, first.type(), position);
        logger.debug("Using generic name '{}' for value '{}'", genericName, value);
        return genericName;
    }

    /**
     * Try to infer name from obvious patterns (Tier 2).
     * Returns null if no pattern matches.
     */
    private String inferFromPattern(String value, VariationType varType) {
        // Only for literals and variables
        if (varType != VariationType.LITERAL && varType != VariationType.VARIABLE) {
            return null;
        }

        String content = value.replaceAll("^\"|\"$", "");
        String upper = content.toUpperCase();

        // Essential, unambiguous patterns only
        if (content.contains("@") && content.contains(".")) {
            return "email";
        }
        if (content.startsWith("http://") || content.startsWith("https://")) {
            return "url";
        }
        if (content.matches("\\d+")) {
            return "id";
        }
        if (content.contains("/") || content.contains("\\\\")) {
            return "path";
        }
        if (upper.startsWith("SELECT") || upper.startsWith("INSERT") ||
                upper.startsWith("UPDATE") || upper.startsWith("DELETE")) {
            return "query";
        }

        return null;
    }

    /**
     * Generate generic parameter name (Tier 3).
     */
    private String generateGenericName(String type, VariationType varType, int position) {
        return switch (varType) {
            case LITERAL -> type.equalsIgnoreCase("String") ? "text" + position : "value" + position;
            case VARIABLE -> "value" + position;
            case METHOD_CALL -> "result" + position;
            default -> "param" + position;
        };
    }
}
