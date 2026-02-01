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
    private final VariationAnalysis variationAnalysis; // NEW: Analysis used to generate this recommendation

    /**
     * Creates a full recommendation with all details.
     *
     * @param strategy              The refactoring strategy
     * @param suggestedMethodName   Suggested name for the extracted method
     * @param suggestedParameters   List of parameters
     * @param suggestedReturnType   Return type
     * @param targetLocation        Target class/file location
     * @param confidenceScore       Confidence score (0.0-1.0)
     * @param estimatedLOCReduction Estimated lines of code reduction
     * @param primaryReturnVariable Variable to return (if any)
     * @param validStatementCount   Number of valid statements to extract (for truncation)
     * @param variationAnalysis     The variation analysis used
     */
    public RefactoringRecommendation(
            RefactoringStrategy strategy,
            String suggestedMethodName,
            List<ParameterSpec> suggestedParameters,
            Type suggestedReturnType,
            String targetLocation,
            double confidenceScore,
            long estimatedLOCReduction,
            String primaryReturnVariable,
            int validStatementCount,
            VariationAnalysis variationAnalysis) {
        this.strategy = strategy;
        this.suggestedMethodName = suggestedMethodName;
        this.suggestedParameters = suggestedParameters;
        this.suggestedReturnType = suggestedReturnType;
        this.targetLocation = targetLocation;
        this.confidenceScore = confidenceScore;
        this.estimatedLOCReduction = estimatedLOCReduction;
        this.primaryReturnVariable = primaryReturnVariable;
        this.validStatementCount = validStatementCount;
        this.variationAnalysis = variationAnalysis;
    }

    /**
     * Creates a recommendation without truncation or variation details.
     *
     * @param strategy              The refactoring strategy
     * @param suggestedMethodName   Suggested name for the extracted method
     * @param suggestedParameters   List of parameters
     * @param suggestedReturnType   Return type
     * @param targetLocation        Target class/file location
     * @param confidenceScore       Confidence score (0.0-1.0)
     * @param estimatedLOCReduction Estimated lines of code reduction
     * @param primaryReturnVariable Variable to return (if any)
     */
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
                estimatedLOCReduction, primaryReturnVariable, -1, null);
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
                targetLocation, confidenceScore, estimatedLOCReduction, null, -1, null);
    }

    /**
     * Gets the variation analysis used to generate this recommendation.
     *
     * @return the variation analysis, or null if not available
     */
    public VariationAnalysis getVariationAnalysis() {
        return variationAnalysis;
    }

    /**
     * Gets the recommended refactoring strategy.
     *
     * @return the strategy
     */
    public RefactoringStrategy getStrategy() {
        return strategy;
    }

    /**
     * Gets the suggested method name.
     *
     * @return the method name
     */
    public String getSuggestedMethodName() {
        return suggestedMethodName;
    }

    /**
     * Gets the suggested parameters.
     *
     * @return the list of parameters
     */
    public List<ParameterSpec> getSuggestedParameters() {
        return suggestedParameters;
    }

    /**
     * Gets the suggested return type.
     *
     * @return the return type
     */
    public Type getSuggestedReturnType() {
        return suggestedReturnType;
    }

    /**
     * Gets the target location description.
     *
     * @return the target location
     */
    public String getTargetLocation() {
        return targetLocation;
    }

    /**
     * Gets the confidence score.
     *
     * @return the confidence score (0.0 to 1.0)
     */
    public double getConfidenceScore() {
        return confidenceScore;
    }

    /**
     * Gets the estimated LOC reduction.
     *
     * @return the estimated reduction in lines of code
     */
    public long getEstimatedLOCReduction() {
        return estimatedLOCReduction;
    }

    /**
     * Gets the primary variable to return from the extracted method.
     *
     * @return the return variable name
     */
    public String getPrimaryReturnVariable() {
        return primaryReturnVariable;
    }

    /**
     * Gets the valid statement count for truncation.
     *
     * @return the number of valid statements, or -1 if no truncation
     */
    public int getValidStatementCount() {
        return validStatementCount;
    }

    /**
     * Check if this recommendation is high confidence (>= 0.90).
     */
    public boolean isHighConfidence() {
        return confidenceScore >= 0.50;
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
