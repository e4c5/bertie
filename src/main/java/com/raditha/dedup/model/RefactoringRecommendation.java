package com.raditha.dedup.model;

import java.util.List;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.StaticJavaParser;

/**
 * Refactoring recommendation for duplicate code.
 * Generated after similarity analysis and feasibility checks.
 */
public class RefactoringRecommendation {

    private final RefactoringStrategy strategy;
    private final String suggestedMethodName;
    private final List<ParameterSpec> suggestedParameters;
    private final transient Type suggestedReturnType;
    private final String targetLocation;
    private final double confidenceScore;
    private final long estimatedLOCReduction;
    private final String primaryReturnVariable;
    private final int validStatementCount; // NEW: Truncate sequence after N statements if < size

    public RefactoringRecommendation(
            RefactoringStrategy strategy,
            String suggestedMethodName,
            List<ParameterSpec> suggestedParameters,
            Type suggestedReturnType,
            String targetLocation,
            double confidenceScore,
            long estimatedLOCReduction,
            String primaryReturnVariable,
            int validStatementCount) {
        this.strategy = strategy;
        this.suggestedMethodName = suggestedMethodName;
        this.suggestedParameters = suggestedParameters;
        this.suggestedReturnType = suggestedReturnType;
        this.targetLocation = targetLocation;
        this.confidenceScore = confidenceScore;
        this.estimatedLOCReduction = estimatedLOCReduction;
        this.primaryReturnVariable = primaryReturnVariable;
        this.validStatementCount = validStatementCount;
    }

    public RefactoringRecommendation(
            RefactoringStrategy strategy,
            String suggestedMethodName,
            List<ParameterSpec> suggestedParameters,
            Type suggestedReturnType,
            String targetLocation,
            double confidenceScore,
            long estimatedLOCReduction,
            String primaryReturnVariable) {
        this(strategy, suggestedMethodName, suggestedParameters, suggestedReturnType, targetLocation, confidenceScore,
                estimatedLOCReduction, primaryReturnVariable, -1);
    }

    /**
     * Compatibility constructor for existing codes.
     * Takes String return type and attempts to parse it.
     */
    public RefactoringRecommendation(
            RefactoringStrategy strategy,
            String suggestedMethodName,
            List<ParameterSpec> suggestedParameters,
            String suggestedReturnType,
            String targetLocation,
            double confidenceScore,
            int estimatedLOCReduction) {
        this(strategy, suggestedMethodName, suggestedParameters,
                StaticJavaParser.parseType(suggestedReturnType != null ? suggestedReturnType : "void"),
                targetLocation, confidenceScore, estimatedLOCReduction, null, -1);
    }

    public RefactoringStrategy getStrategy() {
        return strategy;
    }

    public String getSuggestedMethodName() {
        return suggestedMethodName;
    }

    public List<ParameterSpec> getSuggestedParameters() {
        return suggestedParameters;
    }

    public Type getSuggestedReturnType() {
        return suggestedReturnType;
    }

    public String getTargetLocation() {
        return targetLocation;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public long getEstimatedLOCReduction() {
        return estimatedLOCReduction;
    }

    public String getPrimaryReturnVariable() {
        return primaryReturnVariable;
    }

    public int getValidStatementCount() {
        return validStatementCount;
    }

    /**
     * Check if this recommendation is high confidence (>= 0.90).
     */
    public boolean isHighConfidence() {
        return confidenceScore >= 0.80;
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
                suggestedReturnType != null ? suggestedReturnType.asString() : "void",
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
