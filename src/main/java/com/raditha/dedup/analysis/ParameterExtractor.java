package com.raditha.dedup.analysis;

import com.raditha.dedup.model.ParameterSpec;
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

        // Group variations by position
        Map<Integer, List<Variation>> byPosition = variations.variations().stream()
                .collect(Collectors.groupingBy(Variation::alignedIndex1));

        // Create a parameter for each position
        for (Map.Entry<Integer, List<Variation>> entry : byPosition.entrySet()) {
            int position = entry.getKey();
            List<Variation> positionVars = entry.getValue();

            if (positionVars.isEmpty()) {
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
            List<String> exampleValues = positionVars.stream()
                    .map(v -> v.value1())
                    .distinct()
                    .limit(3) // Limit to 3 examples
                    .toList();

            parameters.add(new ParameterSpec(
                    name,
                    type,
                    exampleValues));

            // Limit total parameters
            if (parameters.size() >= MAX_PARAMETERS) {
                break;
            }
        }

        // Sort parameters (primitives first, then objects)
        return sortParameters(parameters);
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
                .map(Variation::value1)
                .distinct()
                .limit(3)
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

    /**
     * Find common part among variable names.
     */
    private String findCommonPart(List<String> names) {
        if (names.isEmpty()) {
            return null;
        }

        // Simple approach: find common suffix
        // e.g., userId, customerId → "Id"
        String first = names.get(0);
        for (int len = 2; len <= first.length(); len++) {
            String suffix = first.substring(first.length() - len);
            boolean allMatch = names.stream()
                    .allMatch(name -> name.endsWith(suffix));
            if (allMatch && len > 2) {
                return capitalize(suffix);
            }
        }

        return null;
    }

    /**
     * Sort parameters: primitives first, then objects.
     */
    private List<ParameterSpec> sortParameters(List<ParameterSpec> parameters) {
        return parameters.stream()
                .sorted((p1, p2) -> {
                    boolean p1IsPrimitive = isPrimitiveType(p1.type());
                    boolean p2IsPrimitive = isPrimitiveType(p2.type());

                    if (p1IsPrimitive && !p2IsPrimitive) {
                        return -1;
                    } else if (!p1IsPrimitive && p2IsPrimitive) {
                        return 1;
                    }
                    return 0;
                })
                .toList();
    }

    /**
     * Check if a type is a primitive or primitive wrapper.
     */
    private boolean isPrimitiveType(String type) {
        return type.equals("int") || type.equals("long") || type.equals("double") ||
                type.equals("boolean") || type.equals("Integer") || type.equals("Long") ||
                type.equals("Double") || type.equals("Boolean");
    }

    /**
     * Capitalize first letter.
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
