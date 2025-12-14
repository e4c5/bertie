package com.raditha.dedup.analysis;

import com.raditha.dedup.model.ParameterSpec;
import com.raditha.dedup.model.Variation;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.VariationType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Extracts parameter specifications from variations for method extraction.
 */
public class ParameterExtractor {

    private static final int MAX_PARAMETERS = 5;

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
     * Infer a meaningful parameter name from variations.
     */
    private String inferParameterName(List<Variation> variations, int position) {
        VariationType varType = variations.get(0).type();

        // Try to infer name based on variation type
        return switch (varType) {
            case LITERAL -> inferLiteralParameterName(variations, position);
            case VARIABLE -> inferVariableParameterName(variations);
            case METHOD_CALL -> "method" + position;
            case TYPE -> "type" + position;
            default -> "param" + position;
        };
    }

    /**
     * Infer parameter name from literal variations.
     */
    private String inferLiteralParameterName(List<Variation> variations, int position) {
        String firstValue = variations.get(0).value1();

        // Infer from literal type
        if (isNumeric(firstValue)) {
            return "value" + position;
        } else if (isBoolean(firstValue)) {
            return "flag" + position;
        } else if (isString(firstValue)) {
            // Try to infer from string content
            return inferFromStringContent(firstValue, position);
        }

        return "literal" + position;
    }

    /**
     * Infer parameter name from variable name variations.
     */
    private String inferVariableParameterName(List<Variation> variations) {
        // Look for common patterns in variable names
        List<String> names = variations.stream()
                .map(Variation::value1)
                .toList();

        // Find common suffix/prefix
        String commonPart = findCommonPart(names);
        if (commonPart != null && !commonPart.isEmpty()) {
            return uncapitalize(commonPart);
        }

        // Use a generic name based on content
        return "value";
    }

    /**
     * Infer parameter name from string content.
     */
    private String inferFromStringContent(String stringLiteral, int position) {
        // Remove quotes
        String content = stringLiteral.replaceAll("^\"|\"$", "");

        // Look for patterns
        if (content.contains("@") && content.contains(".")) {
            return "email" + position;
        } else if (content.matches("\\d+")) {
            return "id" + position;
        } else if (content.length() < 20) {
            return "name" + position;
        }

        return "text" + position;
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
                .sorted((a, b) -> {
                    boolean aPrimitive = isPrimitive(a.type());
                    boolean bPrimitive = isPrimitive(b.type());

                    if (aPrimitive && !bPrimitive)
                        return -1;
                    if (!aPrimitive && bPrimitive)
                        return 1;
                    return 0; // Keep original order
                })
                .toList();
    }

    /**
     * Check if a type is primitive.
     */
    private boolean isPrimitive(String type) {
        return switch (type) {
            case "int", "long", "double", "float", "boolean", "char", "byte", "short" -> true;
            default -> false;
        };
    }

    /**
     * Check if string is numeric.
     */
    private boolean isNumeric(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Check if string is boolean.
     */
    private boolean isBoolean(String value) {
        return "true".equals(value) || "false".equals(value);
    }

    /**
     * Check if string is a string literal.
     */
    private boolean isString(String value) {
        return value.startsWith("\"") && value.endsWith("\"");
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

    /**
     * Uncapitalize first letter.
     */
    private String uncapitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }
}
