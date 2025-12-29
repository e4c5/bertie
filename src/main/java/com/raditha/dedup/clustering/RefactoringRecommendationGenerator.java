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

        // DEFAULT: EXTRACT_HELPER_METHOD is the primary, safest strategy
        // It works for both source and test files

        // Only use test-specific strategies if:
        // 1. ALL duplicates are in test files
        // 2. There's a specific, strong signal for that strategy

        boolean hasSourceFiles = cluster.allSequences().stream()
                .anyMatch(seq -> !isTestFile(seq.sourceFilePath()));

        // If ANY source files involved, always use EXTRACT_HELPER_METHOD
        if (hasSourceFiles) {
            return RefactoringStrategy.EXTRACT_HELPER_METHOD;
        }

        // ALL duplicates in test files - but still default to EXTRACT_HELPER_METHOD
        // Only use specialized strategies for clear, specific cases

        // Note: Parameterized tests and @BeforeEach are DISABLED by default
        // They require very specific patterns and have higher risk of false positives

        // Example: When to enable:
        // if (hasExplicitTestSetupPattern(cluster)) {
        // return RefactoringStrategy.EXTRACT_TO_BEFORE_EACH;
        // }

        // Cross-class refactoring (when implemented)
        if (isCrossClass(cluster)) {
            return RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS;
        }

        // Default to the most robust, general-purpose strategy
        return RefactoringStrategy.EXTRACT_HELPER_METHOD;
    }

    /**
     * Check if a file path is a test file.
     */
    private boolean isTestFile(java.nio.file.Path filePath) {
        if (filePath == null) {
            return false;
        }
        String path = filePath.toString();
        return path.contains("Test.java") || path.contains("/test/") || path.contains("\\test\\");
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
