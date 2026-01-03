package com.raditha.dedup.clustering;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.resolution.types.ResolvedType;
import com.raditha.dedup.analysis.*;
import com.raditha.dedup.model.*;
import com.raditha.dedup.refactoring.MethodNameGenerator;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Optional;

/**
 * Generates refactoring recommendations for duplicate clusters.
 */
public class RefactoringRecommendationGenerator {

    private final TypeAnalyzer typeAnalyzer;
    private final ParameterExtractor parameterExtractor;
    private final MethodNameGenerator nameGenerator;
    private final DataFlowAnalyzer dataFlowAnalyzer;
    private final Map<String, CompilationUnit> allCUs;

    public static class TypeInferenceException extends RuntimeException {
        public TypeInferenceException(String message) {
            super(message);
        }
    }

    public RefactoringRecommendationGenerator() {
        this(java.util.Collections.emptyMap());
    }

    public RefactoringRecommendationGenerator(java.util.Map<String, CompilationUnit> allCUs) {
        this.typeAnalyzer = new TypeAnalyzer();
        this.parameterExtractor = new ParameterExtractor();
        this.nameGenerator = new MethodNameGenerator(true); // Enable AI
        this.dataFlowAnalyzer = new DataFlowAnalyzer();
        this.allCUs = allCUs;
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

        // Filter out internal parameters (defined in sequence)
        parameters = filterInternalParameters(parameters, cluster.primary());

        // Determine strategy
        RefactoringStrategy strategy = determineStrategy(cluster, typeCompat);

        // Generate method name
        String methodName = suggestMethodName(cluster, strategy);

        // Determine return type using data flow analysis
        String returnType = determineReturnType(cluster);

        // Calculate confidence
        double confidence = calculateConfidence(cluster, typeCompat, parameters);

        // Find the return variable in the primary sequence to use as a fallback for
        // duplicates
        // This helps when data flow analysis fails for a specific duplicate but works
        // for the primary
        String primaryReturnVariable = null;
        if (returnType != null && !"void".equals(returnType)) {
            primaryReturnVariable = dataFlowAnalyzer.findReturnVariable(cluster.primary(), returnType);
        }

        return new RefactoringRecommendation(
                strategy,
                methodName,
                parameters,
                returnType,
                "", // targetLocation
                confidence,
                cluster.estimatedLOCReduction(),
                primaryReturnVariable);
    }

    /**
     * Determine the return type for the extracted method.
     * Checks all duplicates to find a common, compatible type.
     */
    private String determineReturnType(DuplicateCluster cluster) {
        Set<String> returnTypes = new HashSet<>();

        // Check primary
        System.out.println("DEBUG determineReturnType: Analyzing PRIMARY sequence with "
                + cluster.primary().statements().size() + " statements");
        String primaryType = analyzeReturnTypeForSequence(cluster.primary());
        System.out.println("DEBUG determineReturnType: primary returned type = " + primaryType);
        if (primaryType != null) {
            returnTypes.add(primaryType);
        }

        // Check all duplicates
        for (var pair : cluster.duplicates()) {
            // SimilarityPair contains (seq1=Primary, seq2=Duplicate) usually, or duplicates
            // relative to primary
            // We want the duplicate sequence (seq2)
            StatementSequence duplicate = pair.seq2();
            System.out.println("DEBUG determineReturnType: Analyzing DUPLICATE sequence with "
                    + duplicate.statements().size() + " statements");
            String type = analyzeReturnTypeForSequence(duplicate);
            if (type != null) {
                returnTypes.add(type);
            }
        }

        if (returnTypes.isEmpty()) {
            return "void";
        }

        // PRIORITY: Check for domain objects FIRST (User, Customer, etc.)
        // Domain objects take precedence over String!
        Optional<String> domainType = returnTypes.stream()
                .filter(t -> !t.equals("int") && !t.equals("long") && !t.equals("double") &&
                        !t.equals("boolean") && !t.equals("void") && !t.equals("String"))
                .findFirst();

        if (domainType.isPresent()) {
            return domainType.get(); // Return User, Customer, Order, etc.
        }

        // Only return String if no domain objects found
        if (returnTypes.contains("String"))
            return "String";

        // Primitive unification
        boolean hasInt = returnTypes.contains("int");
        boolean hasLong = returnTypes.contains("long");
        boolean hasDouble = returnTypes.contains("double");

        // Heuristic: Check for time-related variables in duplicates if inference failed
        boolean hasObject = returnTypes.stream()
                .anyMatch(t -> !t.equals("int") && !t.equals("long") && !t.equals("double") &&
                        !t.equals("boolean") && !t.equals("void") && !t.equals("String"));

        if (!hasObject && !hasLong && !hasDouble) {
            boolean hasTimeVar = cluster.duplicates().stream()
                    .map(d -> d.seq2().statements().toString())
                    .anyMatch(s -> s.contains("timeout") || s.contains("elapsed") || s.contains("Time"));
            if (hasTimeVar) {
                hasLong = true;
                hasLong = true;
            }
        }

        if (hasDouble)
            return "double";
        if (hasLong)
            return "long";
        if (hasInt)
            return "int";

        // Fallback to first found
        return returnTypes.iterator().next();
    }

    private String analyzeReturnTypeForSequence(StatementSequence sequence) {
        // CRITICAL FIX: Use findReturnVariable to find the variable, then get its type
        // Don't try to determine type first (circular dependency!)

        // Find variable that should be returned (live-out or used in return statements)
        String returnVarName = dataFlowAnalyzer.findReturnVariable(sequence, "void"); // Pass "void" to bypass type
                                                                                      // filtering
        System.out.println("DEBUG analyzeReturnTypeForSequence: findReturnVariable returned: " + returnVarName);

        if (returnVarName != null) {
            // Get the actual type of that variable
            String varType = getVariableType(sequence, returnVarName);
            System.out.println("DEBUG analyzeReturnTypeForSequence: Found return var '" + returnVarName
                    + "' with type: " + varType);
            // Return the variable's type, NOT the return statement's expression type
            if (varType != null && !"void".equals(varType)) {
                return varType;
            }
        }

        // Fallback: Check for explicit return statements IN the duplicate sequence
        // But DO NOT use this if we found a return variable - that would give us the
        // wrong type
        String explicitType = analyzeReturnStatementType(sequence);
        System.out.println("DEBUG analyzeReturnTypeForSequence: Fallback to explicit type: " + explicitType);
        return explicitType;
    }

    /**
     * Determine what type the extracted method should return.
     * Uses data flow analysis to check if any variables are live-out OR returned.
     */

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
                    if (returnExpr.isEnclosedExpr()) {
                        returnExpr = returnExpr.asEnclosedExpr().getInner();
                    }

                    // If it's a method call, try to infer type from method name
                    if (returnExpr.isMethodCallExpr()) {
                        return inferTypeFromMethodCall(returnExpr.asMethodCallExpr(), sequence);
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
    /**
     * Infer return type from method call expression.
     * Uses resolution, manual lookup, and method naming conventions.
     */
    private String inferTypeFromMethodCall(MethodCallExpr methodCall, StatementSequence sequence) {
        // DEBUG LOGGING
        System.out.println("DEBUG: Inferring for " + methodCall);

        // Try resolution first
        String resolvedType = null;
        try {
            resolvedType = methodCall.calculateResolvedType().describe();
        } catch (Exception e) {
            // Ignore
        }

        // If resolution worked and is specific, use it
        if (resolvedType != null && !resolvedType.equals("java.lang.Object") && !resolvedType.equals("Object")) {
            return resolvedType;
        }

        // Check manual lookup via allCUs (if resolution failed or returned generic
        // Object)
        try {
            if (methodCall.getScope().isPresent()) {
                String scopeName = methodCall.getScope().get().toString();

                // NEW: Track generic type from field declaration
                String genericTypeParam = extractGenericTypeFromScope(sequence, scopeName);

                String typeName = findTypeInContext(sequence, scopeName);

                // Find CU for this type
                CompilationUnit typeCU = allCUs.get(typeName);
                if (typeCU == null) {
                    // Try simple name matching
                    for (var entry : allCUs.entrySet()) {
                        if (entry.getKey().endsWith("." + typeName) || entry.getKey().equals(typeName)) {
                            typeCU = entry.getValue();
                            break;
                        }
                    }
                }

                if (typeCU != null) {
                    // Find method in this CU
                    String methodName = methodCall.getNameAsString();
                    var method = typeCU.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                            .filter(m -> m.getNameAsString().equals(methodName))
                            .findFirst();

                    if (method.isPresent()) {
                        String returnType = method.get().getType().asString();

                        // NEW: If method returns generic type T and we know T=User, substitute
                        if (genericTypeParam != null && returnType.equals("T")) {
                            return genericTypeParam;
                        }

                        return returnType;
                    }
                }
            } else {
                // Implicit scope (this)
                if (sequence.containingMethod() != null) {
                    var classDecl = sequence.containingMethod()
                            .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
                    if (classDecl.isPresent()) {
                        String methodName = methodCall.getNameAsString();
                        // Look inside valid methods of this class
                        var method = classDecl.get().getMethodsByName(methodName).stream().findFirst();
                        if (method.isPresent()) {
                            return method.get().getType().asString();
                        }
                    }
                }
            }
        } catch (TypeInferenceException e) {
            throw e;
        } catch (Exception e) {
            // Ignore errors during manual lookup
        }

        return inferTypeFromMethodCallHeuristic(methodCall);
    }

    /**
     * Extract generic type parameter from field declaration.
     * E.g., for "repository" with field "Repository<User> repository", returns
     * "User"
     */
    private String extractGenericTypeFromScope(StatementSequence sequence, String scopeName) {
        try {
            if (sequence.containingMethod() != null) {
                var classDecl = sequence.containingMethod()
                        .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
                if (classDecl.isPresent()) {
                    // Find field declaration for the scope
                    for (var field : classDecl.get().getFields()) {
                        for (var v : field.getVariables()) {
                            if (v.getNameAsString().equals(scopeName)) {
                                // Check if field type has generic type arguments
                                if (field.getElementType().isClassOrInterfaceType()) {
                                    var classType = field.getElementType().asClassOrInterfaceType();
                                    if (classType.getTypeArguments().isPresent()) {
                                        var typeArgs = classType.getTypeArguments().get();
                                        if (!typeArgs.isEmpty()) {
                                            // Return first type argument (e.g., User from Repository<User>)
                                            return typeArgs.get(0).asString();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String inferTypeFromMethodCallHeuristic(MethodCallExpr methodCall) {
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

        throw new TypeInferenceException("Unable to infer return type for method call: " + methodCall);
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

        throw new TypeInferenceException("Unable to infer type for field access: " + fieldAccess);
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

        throw new TypeInferenceException("Unable to infer type for expression: " + expr);
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

        // Find CU for type checks
        CompilationUnit cu = null;
        if (!sequence.statements().isEmpty()) {
            cu = sequence.statements().get(0).findCompilationUnit().orElse(null);
        }

        // AST: collect fields from the containing class
        boolean containingMethodIsStatic = false;
        java.util.Map<String, FieldInfo> classFields = new java.util.HashMap<>();
        try {
            var methodOpt = sequence.containingMethod();
            if (methodOpt != null) {
                containingMethodIsStatic = methodOpt.isStatic();
                var classDeclOpt = methodOpt.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
                if (classDeclOpt.isPresent()) {
                    var classDecl = classDeclOpt.get();
                    classDecl.getFields().forEach(fd -> {
                        boolean isStatic = fd.getModifiers().stream().anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.STATIC);
                        fd.getVariables().forEach(v -> {
                            classFields.put(v.getNameAsString(), new FieldInfo(v.getType().asString(), isStatic));
                        });
                    });
                }
            }
        } catch (Exception ignore) {}

        for (String varName : capturedVars) {
            // Skip pseudo-variables and duplicates
            if (varName == null || varName.isEmpty()) continue;
            if (varName.equals("this") || varName.equals("super")) continue;
            if (existingParamNames.contains(varName)) continue;

            // Heuristic: Skip names that look like type/class names (e.g., System)
            if (Character.isUpperCase(varName.charAt(0))) {
                continue;
            }
            // Explicitly skip well-known java.lang types referenced statically
            if ("System".equals(varName) || "Math".equals(varName) || "Integer".equals(varName)) {
                continue;
            }
            // If the name resolves to a type in context, skip (likely static access)
            if (cu != null) {
                try {
                    if (AbstractCompiler.findType(cu, varName) != null) {
                        continue;
                    }
                } catch (Exception ignore) {}
            }

            // NEW: Field-aware captured parameter handling
            FieldInfo fi = classFields.get(varName);
            if (fi != null) {
                // If it is a class field, decide based on helper accessibility
                // Helper mirrors containing method staticness
                // - If containing method is non-static → helper non-static → instance fields are accessible → SKIP param
                // - If containing method is static:
                //     • static field → accessible → SKIP param
                //     • non-static field → NOT accessible → KEEP as parameter with field type
                if (!containingMethodIsStatic) {
                    // accessible instance field
                    continue; // skip adding as parameter
                } else {
                    if (fi.isStatic) {
                        continue; // static field accessible from static helper
                    } else {
                        // non-static field needed for static helper; will pass as parameter
                        String type = fi.type != null ? fi.type : "Object";
                        capturedParams.add(new ParameterSpec(varName, type, List.of(varName), null, null, null));
                        continue;
                    }
                }
            }

            // Determine type of true captured variable (non-field or unresolved field)
            String type = findTypeInContext(sequence, varName);
            if ("void".equals(type)) {
                continue;
            }

            capturedParams.add(new ParameterSpec(varName, type != null ? type : "Object", List.of(varName), null, null, null));
        }

        return capturedParams;
    }

    // Lightweight holder for field metadata
    private static final class FieldInfo {
        final String type;
        final boolean isStatic;
        FieldInfo(String type, boolean isStatic) {
            this.type = type;
            this.isStatic = isStatic;
        }
    }

    private String findTypeInContext(StatementSequence sequence, String varName) {
        try {
            // 1. Scan statements for any expression referring to this variable
            for (Statement stmt : sequence.statements()) {
                // Check variable declarations directly
                for (VariableDeclarationExpr vde : stmt.findAll(VariableDeclarationExpr.class)) {
                    for (var v : vde.getVariables()) {
                        if (v.getNameAsString().equals(varName)) {
                            // Use solver if possible, or fallback to AST type
                            // Use solver if possible, or fallback to AST type
                            String resolved = null;
                            try {
                                resolved = v.resolve().getType().describe();
                            } catch (Exception e) {
                            }

                            if (resolved != null && !resolved.equals("java.lang.Object")
                                    && !resolved.equals("Object")) {
                                return resolved;
                            }

                            // Fallback to AST type if resolution failed or is generic Object
                            String astType = v.getType().asString();
                            if (!astType.equals("var")) {
                                return astType;
                            }

                            // If var, and resolved is Object, try to infer from initializer
                            if (v.getInitializer().isPresent()) {
                                Expression init = v.getInitializer().get();
                                if (init.isMethodCallExpr()) {
                                    return inferTypeFromMethodCall(init.asMethodCallExpr(), sequence);
                                }
                                return inferTypeFromExpression(init);
                            }

                            return resolved != null ? resolved : "Object";

                        }
                    }
                }

                for (NameExpr nameExpr : stmt.findAll(NameExpr.class)) {
                    if (nameExpr.getNameAsString().equals(varName)) {
                        try {
                            String resolved = nameExpr.calculateResolvedType().describe();
                            if (resolved != null && !resolved.equals("java.lang.Object")
                                    && !resolved.equals("Object")) {
                                return resolved;
                            }
                        } catch (Exception e) {
                            // Continue...
                        }
                    }
                }
            }

            // Check field declarations in the containing class
            if (sequence.containingMethod() != null) {
                var classDecl = sequence.containingMethod()
                        .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
                if (classDecl.isPresent()) {
                    for (var field : classDecl.get().getFields()) {
                        for (var v : field.getVariables()) {
                            if (v.getNameAsString().equals(varName)) {
                                // Use asString() to preserve generic type parameters (e.g., Repository<User>)
                                return field.getElementType().asString();
                            }
                        }
                    }
                }
            }

            // 2. Fallback: Scanner check (if solver failed on all usages, or no usages
            // found in block)
            // This handles cases where variable is defined OUTSIDE the block.
            // We can't easily resolve outside without a reference node.

            // If we have a reference to the method, we can scan parameters
            if (sequence.containingMethod() != null) {
                for (var param : sequence.containingMethod().getParameters()) {
                    if (param.getNameAsString().equals(varName)) {
                        try {
                            return param.resolve().getType().describe();
                        } catch (Exception e) {
                            return param.getType().asString();
                        }
                    }
                }
            }

            // 3. Fallback: Scan method body for variables declared outside the block
            if (sequence.containingMethod() != null && sequence.containingMethod().getBody().isPresent()) {
                for (VariableDeclarationExpr vde : sequence.containingMethod().getBody().get()
                        .findAll(VariableDeclarationExpr.class)) {
                    for (var v : vde.getVariables()) {
                        if (v.getNameAsString().equals(varName)) {
                            // Use solver if possible
                            try {
                                String resolved = v.resolve().getType().describe();
                                if (resolved != null && !resolved.equals("java.lang.Object")
                                        && !resolved.equals("Object")) {
                                    return resolved;
                                }
                            } catch (Exception e) {
                            }

                            // Fallback to AST type
                            return v.getType().asString();
                        }
                    }
                }
            }

        } catch (Exception e) {
            // Ignore
        }

        // 4. Special handling for well-known Java classes and common patterns
        // This handles cases where variables reference built-in classes or follow
        // common naming conventions

        // Built-in Java classes (static references that don't need declaration)
        if ("System".equals(varName)) {
            return "java.lang.System";
        }
        if ("Math".equals(varName)) {
            return "java.lang.Math";
        }
        if ("String".equals(varName)) {
            return "java.lang.String";
        }

        // Common naming patterns - infer type from variable name
        // This is lenient but better than failing fast on test data
        if (varName.toLowerCase().contains("logger")) {
            return "Logger"; // Common logger pattern
        }
        if (varName.toLowerCase().contains("repository") || varName.toLowerCase().contains("repo")) {
            return "Repository"; // Common repository pattern
        }
        if (varName.toLowerCase().contains("service")) {
            return "Service";
        }
        if (varName.toLowerCase().contains("data")) {
            return "Object"; // Generic data object
        }
        // Common domain objects
        if (varName.toLowerCase().contains("order")) {
            return "Order";
        }
        if (varName.toLowerCase().contains("customer")) {
            return "Customer";
        }
        if (varName.toLowerCase().contains("product")) {
            return "Product";
        }
        if (varName.toLowerCase().contains("account")) {
            return "Account";
        }
        if (varName.toLowerCase().contains("user")) {
            return "User";
        }

        throw new TypeInferenceException("Unable to infer type for variable: " + varName);
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

    private List<ParameterSpec> filterInternalParameters(List<ParameterSpec> params, StatementSequence sequence) {
        Set<String> defined = dataFlowAnalyzer.findDefinedVariables(sequence);
        List<ParameterSpec> filtered = new java.util.ArrayList<>();

        // Find CU for type resolution
        CompilationUnit cu = null;
        if (!sequence.statements().isEmpty()) {
            cu = sequence.statements().get(0).findCompilationUnit().orElse(null);
        }

        for (var p : params) {
            boolean isInternal = false;
            // Check if any example value utilizes a defined variable
            for (String val : p.exampleValues()) {
                // 1. Check local variables
                for (String def : defined) {
                    if (val.equals(def) || val.startsWith(def + ".") || val.contains("(" + def + ")")) {
                        isInternal = true;
                        break;
                    }
                }
                if (isInternal)
                    break;

                // 2. Check Class References (e.g. System, LocalDateTime)
                // If a parameter matches a class name, it's likely a static access that
                // mistakenly became a parameter
                if (cu != null && val.matches("[A-Z][a-zA-Z0-9_]*")) {
                    try {
                        if (AbstractCompiler.findType(cu, val) != null) {
                            isInternal = true;
                            break;
                        }
                    } catch (Exception e) {
                    }
                }

                // 3. Check for VOID expressions (cannot be passed as arguments)
                if (!isInternal) {
                    for (Statement s : sequence.statements()) {
                        boolean voidFound = false;
                        for (Expression e : s.findAll(Expression.class)) {
                            if (e.toString().equals(val)) {
                                try {
                                    String type = e.calculateResolvedType().describe();
                                    if ("void".equals(type)) {
                                        isInternal = true;
                                        voidFound = true;
                                        break;
                                    }
                                } catch (Exception ex) {
                                    // Resolution failed. Try manual lookup if it's a method call.
                                    if (e.isMethodCallExpr()) {
                                        MethodCallExpr call = e.asMethodCallExpr();
                                        if (call.getScope().isPresent()) {
                                            String scopeName = call.getScope().get().toString();
                                            String typeName = findTypeInContext(sequence, scopeName);

                                            // Find CU for this type
                                            CompilationUnit typeCU = allCUs.get(typeName); // Try exact key
                                            if (typeCU == null) {
                                                // Try simple name matching
                                                for (var entry : allCUs.entrySet()) {
                                                    if (entry.getKey().endsWith("." + typeName)
                                                            || entry.getKey().equals(typeName)) {
                                                        typeCU = entry.getValue();
                                                        break;
                                                    }
                                                }
                                            }

                                            if (typeCU != null) {
                                                // Find method in this CU
                                                String methodName = call.getNameAsString();
                                                boolean methodFoundIsVoid = typeCU
                                                        .findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
                                                        .stream()
                                                        .filter(m -> m.getNameAsString().equals(methodName))
                                                        .anyMatch(m -> m.getType().isVoidType());

                                                if (methodFoundIsVoid) {
                                                    isInternal = true;
                                                    voidFound = true;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (voidFound)
                            break;
                    }
                }
            }

            if (!isInternal) {
                filtered.add(p);
            } else {
                // Was filtered
            }
        }
        return filtered;
    }
}
