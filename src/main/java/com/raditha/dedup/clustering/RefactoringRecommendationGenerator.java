package com.raditha.dedup.clustering;

import com.raditha.dedup.analysis.*;
import com.raditha.dedup.model.*;
import com.raditha.dedup.refactoring.MethodNameGenerator;

import java.util.List;

/**
 * Generates refactoring recommendations for duplicate clusters.
 */
public class RefactoringRecommendationGenerator {

    private final TypeAnalyzer typeAnalyzer;
    private final ParameterExtractor parameterExtractor;
    private final MethodNameGenerator nameGenerator;

    public RefactoringRecommendationGenerator() {
        this.typeAnalyzer = new TypeAnalyzer();
        this.parameterExtractor = new ParameterExtractor();
        this.nameGenerator = new MethodNameGenerator(true); // Enable AI
    }

    /**
     * Generate refactoring recommendation for a cluster.
     */
    public RefactoringRecommendation generateRecommendation(
            DuplicateCluster cluster,
            SimilarityResult similarity) {

        // Analyze type compatibility
        TypeCompatibility typeCompat = typeAnalyzer.analyzeTypeCompatibility(
                similarity.variations());

        // Extract parameters
        List<ParameterSpec> parameters = parameterExtractor.extractParameters(
                similarity.variations(),
                typeCompat.parameterTypes());

        // Determine strategy
        RefactoringStrategy strategy = determineStrategy(cluster, typeCompat);

        // Generate method name
        String methodName = suggestMethodName(cluster, strategy);

        // Calculate confidence
        double confidence = calculateConfidence(cluster, typeCompat, parameters);

        return new RefactoringRecommendation(
                strategy,
                methodName,
                parameters,
                "void", // suggestedReturnType
                "", // targetLocation
                confidence,
                cluster.estimatedLOCReduction());
    }

    private RefactoringStrategy determineStrategy(
            DuplicateCluster cluster,
            TypeCompatibility typeCompat) {

        StatementSequence primary = cluster.primary();

        // Check if in test class
        boolean isTestClass = primary.sourceFilePath() != null &&
                primary.sourceFilePath().toString().contains("Test.java");

        if (isTestClass) {
            if (isSetupCode(primary)) {
                return RefactoringStrategy.EXTRACT_TO_BEFORE_EACH;
            } else if (canParameterize(cluster)) {
                return RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST;
            }
        }

        // Cross-class?
        if (isCrossClass(cluster)) {
            return RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS;
        }

        return RefactoringStrategy.EXTRACT_HELPER_METHOD;
    }

    private String suggestMethodName(DuplicateCluster cluster, RefactoringStrategy strategy) {
        // Extract the containing class from the cluster's primary sequence
        var containingClass = cluster.primary().containingMethod()
                .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .orElse(null);

        // Use semantic naming with AI fallback
        return nameGenerator.generateName(
                cluster,
                strategy,
                containingClass,
                MethodNameGenerator.NamingStrategy.SEMANTIC);
    }

    private double calculateConfidence(
            DuplicateCluster cluster,
            TypeCompatibility typeCompat,
            List<ParameterSpec> parameters) {

        double score = 1.0;

        if (!typeCompat.isTypeSafe())
            score *= 0.7;
        if (parameters.size() > 3)
            score *= 0.8;
        if (cluster.getAverageSimilarity() < 0.8)
            score *= 0.9;
        if (cluster.getAverageSimilarity() > 0.95)
            score *= 1.1;

        return Math.min(1.0, score);
    }

    private boolean isSetupCode(StatementSequence sequence) {
        String code = sequence.statements().toString().toLowerCase();
        return code.contains("new ") || code.contains("set");
    }

    private boolean canParameterize(DuplicateCluster cluster) {
        return cluster.duplicates().size() >= 2;
    }

    private boolean isCrossClass(DuplicateCluster cluster) {
        return false; // Simplified
    }
}
