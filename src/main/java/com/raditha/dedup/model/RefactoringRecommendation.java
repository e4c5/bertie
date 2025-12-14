package com.raditha.dedup.model;

import java.util.List;

/**
 * Refactoring recommendation for duplicate code.
 * Generated after similarity analysis and feasibility checks.
 * 
 * @param strategy              Recommended refactoring approach
 * @param suggestedMethodName   Suggested name for extracted method
 * @param suggestedParameters   Parameters to extract
 * @param suggestedReturnType   Return type for extracted method
 * @param targetLocation        Where to insert the extracted method
 * @param confidenceScore       Confidence in this recommendation (0.0-1.0)
 * @param estimatedLOCReduction Estimated lines of code reduction
 */
public record RefactoringRecommendation(
        RefactoringStrategy strategy,
        String suggestedMethodName,
        List<ParameterSpec> suggestedParameters,
        String suggestedReturnType,
        String targetLocation,
        double confidenceScore,
        int estimatedLOCReduction) {
    /**
     * Check if this recommendation is high confidence (>= 0.90).
     */
    public boolean isHighConfidence() {
        return confidenceScore >= 0.90;
    }

    /**
     * Check if this recommendation is safe for automated refactoring.
     */
    public boolean isSafeForAutomation() {
        return isHighConfidence() && strategy != RefactoringStrategy.MANUAL_REVIEW_REQUIRED;
    }

    /**
     * Generate method signature from recommendation.
     * Example: "private Admission createTestAdmission(String patientId, String
     * hospitalId)"
     */
    public String generateMethodSignature() {
        String params = suggestedParameters.stream()
                .map(ParameterSpec::toParameterDeclaration)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        return String.format("private %s %s(%s)",
                suggestedReturnType != null ? suggestedReturnType : "void",
                suggestedMethodName,
                params);
    }

    /**
     * Format confidence as percentage.
     */
    public String formatConfidence() {
        return String.format("%.0f%%", confidenceScore * 100);
    }
}
