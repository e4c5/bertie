package com.raditha.dedup.clustering;

import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.analysis.*;
import com.raditha.dedup.model.*;
import com.raditha.dedup.refactoring.MethodNameGenerator;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates refactoring recommendations for duplicate clusters.
 */
public class RefactoringRecommendationGenerator {

    private final TypeAnalyzer typeAnalyzer;
    private final ParameterExtractor parameterExtractor;
    private final MethodNameGenerator nameGenerator;
    private final DataFlowAnalyzer dataFlowAnalyzer;

    public RefactoringRecommendationGenerator() {
        this.typeAnalyzer = new TypeAnalyzer();
        this.parameterExtractor = new ParameterExtractor();
        this.nameGenerator = new MethodNameGenerator(true); // Enable AI
        this.dataFlowAnalyzer = new DataFlowAnalyzer();
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

        // Determine return type using data flow analysis
        String returnType = determineReturnType(cluster.primary());

        // Calculate confidence
        double confidence = calculateConfidence(cluster, typeCompat, parameters);

        return new RefactoringRecommendation(
                strategy,
                methodName,
                parameters,
                returnType,
                "", // targetLocation
                confidence,
                cluster.estimatedLOCReduction());
    }

    /**
     * Determine what type the extracted method should return.
     * Uses data flow analysis to check if any variables are live-out OR returned.
     */
    private String determineReturnType(StatementSequence sequence) {
        // Find variables that are used after the duplicate code
        Set<String> liveOutVars = dataFlowAnalyzer.findLiveOutVariables(sequence);

        if (liveOutVars.isEmpty()) {
            // No variables used after - check if any are RETURNED within the duplicate
            Set<String> returnedVars = findVariablesInReturnStatements(sequence);

            if (!returnedVars.isEmpty()) {
                // Variable is returned - use that!
                String varToReturn = returnedVars.iterator().next();
                String type = getVariableType(sequence, varToReturn);
                return type;
            }

            return "void";
        }

        // Has live-out variables → need to return something
        String varToReturn = liveOutVars.iterator().next();

        // Get the type of this variable from the sequence
        String type = getVariableType(sequence, varToReturn);
        return type;
    }

    /**
     * Find variables used in return statements within the sequence.
     * This handles cases where the return statement is part of the duplicate.
     */
    private Set<String> findVariablesInReturnStatements(StatementSequence sequence) {
        Set<String> returned = new HashSet<>();

        for (Statement stmt : sequence.statements()) {
            if (stmt.isReturnStmt()) {
                var returnStmt = stmt.asReturnStmt();
                if (returnStmt.getExpression().isPresent()) {
                    // Find all variable references in the return expression
                    returnStmt.getExpression().get().findAll(NameExpr.class)
                            .forEach(nameExpr -> returned.add(nameExpr.getNameAsString()));
                }
            }
        }

        return returned;
    }

    /**
     * Get the type of a variable from the statement sequence.
     */
    private String getVariableType(StatementSequence sequence, String variableName) {
        // Search through all statements to find variable declaration
        for (Statement stmt : sequence.statements()) {
            // Find all variable declarations in this statement (any type)
            var varDecls = stmt.findAll(VariableDeclarationExpr.class);
            for (VariableDeclarationExpr varDecl : varDecls) {
                for (var variable : varDecl.getVariables()) {
                    if (variable.getNameAsString().equals(variableName)) {
                        return variable.getType().asString();
                    }
                }
            }
        }

        // Fallback to void if type not found
        return "void";
    }

    // [Rest of the class remains unchanged - keeping existing methods]
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
            score *= 0.5;

        if (parameters.size() > 4)
            score *= 0.7;

        if (cluster.getAverageSimilarity() < 0.85)
            score *= 0.8;

        return score;
    }

    private boolean isSetupCode(StatementSequence sequence) {
        String code = sequence.statements().toString().toLowerCase();
        return code.contains("new ") || code.contains("set");
    }

    private boolean canParameterize(DuplicateCluster cluster) {
        return cluster.duplicates().size() >= 3;
    }

    private boolean isCrossClass(DuplicateCluster cluster) {
        // Check if duplicates span multiple classes
        return false; // Simplified for now
    }
}
