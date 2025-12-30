package com.raditha.dedup.clustering;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.Expression;
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
        List<ParameterSpec> parameters = new java.util.ArrayList<>(parameterExtractor.extractParameters(
                similarity.variations(),
                typeCompat.parameterTypes()));

        // Add captured variables (variables used but not defined in sequence, and
        // constant across duplicates)
        List<ParameterSpec> capturedParams = identifyCapturedParameters(cluster.primary(), parameters);
        parameters.addAll(capturedParams);

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
        // 1. Check if any variable DEFINED in the sequence is used in a return
        // statement
        Set<String> returnedVars = findVariablesInReturnStatements(sequence);
        Set<String> definedVars = dataFlowAnalyzer.findDefinedVariables(sequence);

        // Filter returned variables to only those defined in the sequence
        // We want to return the OBJECT (User), not the expression result (String)
        for (String varName : returnedVars) {
            if (definedVars.contains(varName)) {
                return getVariableType(sequence, varName);
            }
        }

        // 2. If no internal variable returned, check the return statement expression
        // type
        // (e.g. constant return, or external variable return)
        String typeFromReturn = analyzeReturnStatementType(sequence);
        if (typeFromReturn != null) {
            return typeFromReturn;
        }

        // 3. No return statement in duplicate - find variables that are used after
        Set<String> liveOutVars = dataFlowAnalyzer.findLiveOutVariables(sequence);

        if (liveOutVars.isEmpty()) {
            return "void";
        }

        // Has live-out variables → need to return something
        String varToReturn = liveOutVars.iterator().next();

        // Get the type of this variable from the sequence
        String type = getVariableType(sequence, varToReturn);
        return type;
    }

    /**
     * Analyze return statement within the sequence to determine actual return type.
     * Handles cases where return statement is part of the duplicate.
     * 
     * Returns null if no return statement found or type cannot be determined.
     */
    private String analyzeReturnStatementType(StatementSequence sequence) {
        for (Statement stmt : sequence.statements()) {
            if (stmt.isReturnStmt()) {
                var returnStmt = stmt.asReturnStmt();
                if (returnStmt.getExpression().isPresent()) {
                    Expression returnExpr = returnStmt.getExpression().get();

                    // If it's a method call, try to infer type from method name
                    if (returnExpr.isMethodCallExpr()) {
                        return inferTypeFromMethodCall(returnExpr.asMethodCallExpr());
                    }

                    // If it's a field access (user.field), infer from field name
                    if (returnExpr.isFieldAccessExpr()) {
                        return inferTypeFromFieldAccess(returnExpr.asFieldAccessExpr());
                    }

                    // If it's a simple variable name, get its type
                    if (returnExpr.isNameExpr()) {
                        String varName = returnExpr.asNameExpr().getNameAsString();
                        return getVariableType(sequence, varName);
                    }

                    // Fallback for other expression types
                    return inferTypeFromExpression(returnExpr);
                }
            }
        }
        return null; // No return statement found
    }

    /**
     * Infer return type from method call expression.
     * Uses common method naming conventions.
     */
    private String inferTypeFromMethodCall(MethodCallExpr methodCall) {
        String methodName = methodCall.getNameAsString();

        // Common getter patterns
        if (methodName.startsWith("get")) {
            if (methodName.equals("getName") || methodName.equals("getEmail") ||
                    methodName.equals("getRole") || methodName.equals("getMessage")) {
                return "String";
            }
            if (methodName.equals("getId") || methodName.equals("getAge") ||
                    methodName.equals("getCount") || methodName.equals("getSize")) {
                return "int";
            }
            if (methodName.equals("isActive") || methodName.equals("isValid") ||
                    methodName.equals("hasPermission")) {
                return "boolean";
            }
        }

        // is* methods typically return boolean
        if (methodName.startsWith("is") || methodName.startsWith("has")) {
            return "boolean";
        }

        // toString, format, etc.
        if (methodName.equals("toString") || methodName.equals("format")) {
            return "String";
        }

        // size, length, count methods
        if (methodName.equals("size") || methodName.equals("length") ||
                methodName.equals("count")) {
            return "int";
        }

        // Fallback: use "Object" for unknown method calls
        return "Object";
    }

    /**
     * Infer type from field access (user.field).
     */
    private String inferTypeFromFieldAccess(FieldAccessExpr fieldAccess) {
        String fieldName = fieldAccess.getNameAsString();

        // Common field naming patterns
        if (fieldName.equals("name") || fieldName.equals("email") ||
                fieldName.equals("role") || fieldName.equals("message")) {
            return "String";
        }
        if (fieldName.equals("id") || fieldName.equals("age") ||
                fieldName.equals("count")) {
            return "int";
        }
        if (fieldName.equals("active") || fieldName.equals("valid")) {
            return "boolean";
        }

        return "Object"; // Fallback
    }

    /**
     * Infer type from other expression types.
     */
    private String inferTypeFromExpression(Expression expr) {
        // String literals
        if (expr.isStringLiteralExpr()) {
            return "String";
        }

        // Integer literals
        if (expr.isIntegerLiteralExpr() || expr.isLongLiteralExpr()) {
            return "int";
        }

        // Boolean literals
        if (expr.isBooleanLiteralExpr()) {
            return "boolean";
        }

        // Object creation
        if (expr.isObjectCreationExpr()) {
            return expr.asObjectCreationExpr().getType().asString();
        }

        // Binary expressions (concatenation)
        if (expr.isBinaryExpr()) {
            return "String";
        }

        return "Object"; // Safe fallback
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
        return findTypeInContext(sequence, variableName);
    }

    /**
     * Identify variables that are used in the sequence but defined externally
     * (captured).
     * These must be passed as parameters if they are not already handled by
     * variation analysis.
     */
    private List<ParameterSpec> identifyCapturedParameters(StatementSequence sequence,
            List<ParameterSpec> existingParams) {
        Set<String> usedVars = dataFlowAnalyzer.findVariablesUsedInSequence(sequence);
        Set<String> definedVars = dataFlowAnalyzer.findDefinedVariables(sequence);

        // Captured = Used - Defined
        Set<String> capturedVars = new HashSet<>(usedVars);
        capturedVars.removeAll(definedVars);

        // Remove variables that are already parameters (from variation analysis)
        Set<String> existingParamNames = new HashSet<>();
        existingParams.forEach(p -> existingParamNames.add(p.name()));

        List<ParameterSpec> capturedParams = new java.util.ArrayList<>();

        for (String varName : capturedVars) {
            // Skip fields (this.field) - usually accessible in helper unless static context
            // mismatch
            // For now, assume fields are accessible (TODO: check static context)
            if (varName.equals("this") || varName.equals("super"))
                continue;

            // Skip if already a parameter
            if (existingParamNames.contains(varName))
                continue;

            // Determine type
            String type = findTypeInContext(sequence, varName);
            if ("void".equals(type) || "Object".equals(type)) {
                // Try to resolve if it's a field
                // If it's a field, we might not need to pass it
                // But if it's a local variable/parameter, we MUST pass it
                // Simple heuristic: if type found in context (params/locals), pass it.
                // If not found, maybe field?
            }

            // If we found a specific type, add it as parameter
            if (!"void".equals(type)) {
                capturedParams.add(new ParameterSpec(varName, type, List.of(varName)));
            }
        }

        return capturedParams;
    }

    private String findTypeInContext(StatementSequence sequence, String varName) {
        // 1. Check inside sequence
        for (Statement stmt : sequence.statements()) {
            var varDecls = stmt.findAll(VariableDeclarationExpr.class);
            for (VariableDeclarationExpr varDecl : varDecls) {
                for (var variable : varDecl.getVariables()) {
                    if (variable.getNameAsString().equals(varName)) {
                        return variable.getType().asString();
                    }
                }
            }
        }

        // 2. Check method parameters
        if (sequence.containingMethod() != null) {
            for (var param : sequence.containingMethod().getParameters()) {
                if (param.getNameAsString().equals(varName)) {
                    return param.getType().asString();
                }
            }

            // 3. Check local variables BEFORE the sequence
            // (Simplified: check usage in method body)
            // Ideally we iterate statements before sequence.startOffset()
        }

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
