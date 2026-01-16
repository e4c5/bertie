package com.raditha.dedup.clustering;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.ParameterSpec;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.refactoring.MethodNameGenerator;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates refactoring recommendations for duplicate clusters.
 * 
 * This class coordinates the refactoring recommendation generation by delegating
 * to focused component classes:
 * - VariationAggregator: Aggregates variations across duplicates
 * - SequenceTruncator: Handles truncation logic for safe extraction
 * - ParameterResolver: Extracts and filters parameters
 * - ReturnTypeResolver: Determines return types
 * - RefactoringConfidenceCalculator: Calculates confidence scores
 */
public class RefactoringRecommendationGenerator {

    private final VariationAggregator variationAggregator;
    private final SequenceTruncator truncator;
    private final ParameterResolver parameterResolver;
    private final ReturnTypeResolver returnTypeResolver;
    private final RefactoringConfidenceCalculator confidenceCalculator;
    private final MethodNameGenerator nameGenerator;

    public RefactoringRecommendationGenerator() {
        this(Collections.emptyMap());
    }

    public RefactoringRecommendationGenerator(Map<String, CompilationUnit> allCUs) {
        this.variationAggregator = new VariationAggregator();
        this.truncator = new SequenceTruncator();
        this.parameterResolver = new ParameterResolver(allCUs);
        this.returnTypeResolver = new ReturnTypeResolver(allCUs);
        this.confidenceCalculator = new RefactoringConfidenceCalculator();
        this.nameGenerator = new MethodNameGenerator(true);
    }

    /**
     * Generate refactoring recommendation for a cluster.
     */
    public RefactoringRecommendation generateRecommendation(DuplicateCluster cluster) {
        // Step 1: Aggregate variations
        AggregatedVariations aggregated = variationAggregator.aggregate(cluster);
        VariationAnalysis analysis = variationAggregator.buildAnalysis(aggregated);

        // Step 2: Calculate truncation
        TruncationResult truncation = truncator.calculateValidStatementCount(cluster, analysis);
        int validStatementCount = truncation.validCount();

        // Abort if no valid statements
        if (validStatementCount == 0) {
            return createAbortedRecommendation(analysis);
        }

        // Step 3: Resolve parameters
        List<ParameterSpec> parameters = parameterResolver.resolveParameters(analysis, cluster, validStatementCount);

        // Step 4: Create effective sequence and determine strategy
        StatementSequence effectivePrimary = cluster.primary();
        if (validStatementCount != -1) {
            effectivePrimary = truncator.createPrefixSequence(cluster.primary(), validStatementCount);
        }
        RefactoringStrategy strategy = determineStrategy(cluster, effectivePrimary);
        
        // CRITICAL FIX: Exclude field variables from parameters for EXTRACT_HELPER_METHOD
        // For EXTRACT_PARENT_CLASS, fields need to be promoted to parent, so keep them as parameters
        if (strategy == RefactoringStrategy.EXTRACT_HELPER_METHOD) {
            parameters = filterOutFieldParameters(parameters, cluster.primary());
        }

        // Step 5: Resolve return type
        ReturnTypeResult returnTypeResult = returnTypeResolver.resolve(cluster, validStatementCount, 
                truncation.returnVariable());
        String returnType = returnTypeResult.returnType();
        String primaryReturnVariable = returnTypeResult.returnVariable();

        // Step 6: Calculate confidence
        double confidence = confidenceCalculator.calculate(cluster, parameters, validStatementCount);

        // Step 7: Generate method name
        String methodName = suggestMethodName(cluster, strategy);

        // Step 8: Build and return recommendation
        return new RefactoringRecommendation(
                strategy,
                methodName,
                parameters,
                StaticJavaParser.parseType(returnType != null ? returnType : "void"),
                "",
                confidence,
                cluster.estimatedLOCReduction(),
                primaryReturnVariable,
                validStatementCount,
                analysis);
    }

    private RefactoringRecommendation createAbortedRecommendation(VariationAnalysis analysis) {
        return new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                "",
                Collections.emptyList(),
                StaticJavaParser.parseType("void"),
                "",
                0.0,
                0,
                null,
                0,
                analysis);
    }

    private RefactoringStrategy determineStrategy(DuplicateCluster cluster, StatementSequence primarySeq) {
        // Check for risky control flow (conditional returns)
        if (hasNestedReturn(primarySeq)) {
            return RefactoringStrategy.MANUAL_REVIEW_REQUIRED;
        }

        // Check if this is a cross-file duplication scenario
        boolean isCrossFile = isCrossFileDuplication(cluster);

        if (isCrossFile) {
            if (usesInstanceState(primarySeq)) {
                return RefactoringStrategy.EXTRACT_PARENT_CLASS;
            } else {
                return RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS;
            }
        }

        // Only use test-specific strategies if ALL duplicates are in test files
        boolean hasSourceFiles = cluster.allSequences().stream()
                .anyMatch(seq -> !isTestFile(seq));

        if (hasSourceFiles) {
            return RefactoringStrategy.EXTRACT_HELPER_METHOD;
        }

        return RefactoringStrategy.EXTRACT_HELPER_METHOD;
    }

    private boolean isCrossFileDuplication(DuplicateCluster cluster) {
        Set<String> filePaths = new HashSet<>();
        for (StatementSequence seq : cluster.allSequences()) {
            if (seq.sourceFilePath() != null) {
                filePaths.add(seq.sourceFilePath().toString());
            }
        }
        return filePaths.size() > 1;
    }

    private boolean usesInstanceState(StatementSequence seq) {
        // If the method is not static, we assume it belongs to an object context
        // and should be extracted to a Parent Class (preserving inheritance)
        // rather than a Utility class.
        // This matches the original behavior and ensures Service classes
        // are refactored into BaseService hierarchies.
        return seq.containingMethod() != null && !seq.containingMethod().isStatic();
    }

    private boolean hasNestedReturn(StatementSequence seq) {
        for (Statement stmt : seq.statements()) {
            if (stmt.isReturnStmt()) continue;
            if (!stmt.findAll(com.github.javaparser.ast.stmt.ReturnStmt.class).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isTestFile(StatementSequence seq) {
        CompilationUnit cu = seq.compilationUnit();
        if (cu == null) return false;

        boolean hasTestAnnotations = cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                .anyMatch(m -> m.getAnnotations().stream()
                        .anyMatch(a -> {
                            String name = a.getNameAsString();
                            return name.equals("Test") || name.equals("ParameterizedTest") ||
                                    name.equals("RepeatedTest") || name.equals("TestFactory");
                        }));

        if (hasTestAnnotations) return true;

        return cu.getImports().stream()
                .anyMatch(i -> i.getNameAsString().startsWith("org.junit"));
    }

    private String suggestMethodName(DuplicateCluster cluster, RefactoringStrategy strategy) {
        var containingClass = cluster.primary().containingMethod()
                .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .orElse(null);

        return nameGenerator.generateName(
                cluster,
                strategy,
                containingClass,
                MethodNameGenerator.NamingStrategy.SEMANTIC);
    }

    /**
     * Filter out field variables from the parameter list.
     * Fields are accessible in the method context and shouldn't be passed as parameters.
     */
    private List<ParameterSpec> filterOutFieldParameters(List<ParameterSpec> parameters, StatementSequence sequence) {
        Set<String> fieldNames = getFieldNames(sequence);
        if (fieldNames.isEmpty()) {
            return parameters;
        }
        
        return parameters.stream()
                .filter(param -> !fieldNames.contains(param.getName()))
                .toList();
    }

    /**
     * Get all field names from the containing class.
     */
    private Set<String> getFieldNames(StatementSequence sequence) {
        var method = sequence.containingMethod();
        if (method == null) {
            return Collections.emptySet();
        }
        
        var clazz = method.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .orElse(null);
        if (clazz == null) {
            return Collections.emptySet();
        }
        
        Set<String> fieldNames = new HashSet<>();
        clazz.getFields().forEach(field -> 
            field.getVariables().forEach(var -> 
                fieldNames.add(var.getNameAsString())
            )
        );
        return fieldNames;
    }
}
