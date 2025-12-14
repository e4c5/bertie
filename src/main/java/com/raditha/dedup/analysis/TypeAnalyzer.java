package com.raditha.dedup.analysis;

import com.raditha.dedup.model.TypeCompatibility;
import com.raditha.dedup.model.Variation;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.VariationType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyzes type compatibility for variations to determine if
 * refactoring is type-safe.
 */
public class TypeAnalyzer {

    /**
     * Analyze type compatibility for a set of variations.
     * 
     * @param variations Variations to analyze
     * @return Type compatibility analysis
     */
    public TypeCompatibility analyzeTypeCompatibility(VariationAnalysis variations) {
        Map<String, String> parameterTypes = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        boolean allTypeSafe = true;
        String unsafeReason = null;

        // Group variations by position to find type patterns
        Map<Integer, List<Variation>> byPosition = groupByPosition(variations);

        // Analyze each position
        for (Map.Entry<Integer, List<Variation>> entry : byPosition.entrySet()) {
            int position = entry.getKey();
            List<Variation> positionVars = entry.getValue();

            // Infer type for this position
            TypeInference inference = inferType(positionVars);

            if (!inference.isSafe) {
                allTypeSafe = false;
                unsafeReason = inference.reason;
                warnings.add(String.format("Position %d: %s", position, inference.reason));
            }

            if (inference.type != null) {
                parameterTypes.put("param" + position, inference.type);
            }
        }

        return new TypeCompatibility(
                allTypeSafe,
                parameterTypes,
                unsafeReason,
                warnings);
    }

    /**
     * Group variations by their aligned position.
     */
    private Map<Integer, List<Variation>> groupByPosition(VariationAnalysis variations) {
        Map<Integer, List<Variation>> byPosition = new HashMap<>();

        for (Variation var : variations.variations()) {
            // Use the first sequence's index as the position
            int position = var.alignedIndex1();
            byPosition.computeIfAbsent(position, k -> new ArrayList<>()).add(var);
        }

        return byPosition;
    }

    /**
     * Infer the type for a list of variations at the same position.
     */
    private TypeInference inferType(List<Variation> variations) {
        if (variations.isEmpty()) {
            return new TypeInference(null, true, null);
        }

        // Get variation type (LITERAL, VARIABLE, METHOD_CALL, etc.)
        VariationType varType = variations.get(0).type();

        // Infer based on variation type
        return switch (varType) {
            case LITERAL -> inferLiteralType(variations);
            case VARIABLE -> inferVariableType(variations);
            case METHOD_CALL -> new TypeInference("Object", true, null); // Conservative
            case TYPE -> new TypeInference("Class<?>", true, null);
            case CONTROL_FLOW -> new TypeInference(null, false, "Control flow differences cannot be parameterized");
        };
    }

    /**
     * Infer type from literal variations.
     */
    private TypeInference inferLiteralType(List<Variation> variations) {
        // Try to infer from the literal values
        String firstValue = variations.get(0).value1();

        // Simple heuristics
        if (isNumeric(firstValue)) {
            return new TypeInference("int", true, null);
        } else if (isBoolean(firstValue)) {
            return new TypeInference("boolean", true, null);
        } else if (isString(firstValue)) {
            return new TypeInference("String", true, null);
        }

        // Check if all variations have the same inferred type
        String inferredType = variations.get(0).inferredType();
        for (Variation var : variations) {
            if (!inferredType.equals(var.inferredType())) {
                return new TypeInference(
                        "Object",
                        false,
                        "Inconsistent literal types: " + inferredType + " vs " + var.inferredType());
            }
        }

        return new TypeInference(inferredType, true, null);
    }

    /**
     * Infer type from variable variations.
     */
    private TypeInference inferVariableType(List<Variation> variations) {
        // For variables, we'd need to look up their actual types
        // This is a simplified implementation

        String inferredType = variations.get(0).inferredType();
        if (inferredType == null || inferredType.isEmpty()) {
            return new TypeInference("Object", true, null); // Conservative fallback
        }

        // Check consistency
        for (Variation var : variations) {
            String varType = var.inferredType();
            if (varType != null && !varType.equals(inferredType)) {
                return new TypeInference(
                        "Object",
                        false,
                        "Inconsistent variable types: " + inferredType + " vs " + varType);
            }
        }

        return new TypeInference(inferredType, true, null);
    }

    /**
     * Check if a string represents a numeric literal.
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
     * Check if a string represents a boolean literal.
     */
    private boolean isBoolean(String value) {
        return "true".equals(value) || "false".equals(value);
    }

    /**
     * Check if a string represents a string literal.
     */
    private boolean isString(String value) {
        return value.startsWith("\"") && value.endsWith("\"");
    }

    /**
     * Result of type inference.
     */
    private record TypeInference(
            String type,
            boolean isSafe,
            String reason) {
    }
}
