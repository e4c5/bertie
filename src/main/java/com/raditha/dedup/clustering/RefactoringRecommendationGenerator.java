package com.raditha.dedup.clustering;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.Statement;

import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.ParameterSpec;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.SimilarityResult;
import com.raditha.dedup.model.Range; // NEW
import com.raditha.dedup.model.Range; // NEW
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.TypeCompatibility;
import com.raditha.dedup.model.VaryingExpression; // NEW
import com.raditha.dedup.refactoring.MethodNameGenerator;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generates refactoring recommendations for duplicate clusters.
 */
public class RefactoringRecommendationGenerator {

    public static final String STRING = "String";
    public static final String DOUBLE = "double";
    public static final String BOOLEAN = "boolean";
    private final com.raditha.dedup.analysis.ASTVariationAnalyzer astVariationAnalyzer; // NEW
    private final com.raditha.dedup.analysis.ASTParameterExtractor astParameterExtractor; // NEW
    private final MethodNameGenerator nameGenerator;
    private final DataFlowAnalyzer dataFlowAnalyzer;
    private final Map<String, CompilationUnit> allCUs;

    public RefactoringRecommendationGenerator() {
        this(java.util.Collections.emptyMap());
    }

    public RefactoringRecommendationGenerator(java.util.Map<String, CompilationUnit> allCUs) {
        this.astVariationAnalyzer = new com.raditha.dedup.analysis.ASTVariationAnalyzer(); // NEW
        this.astParameterExtractor = new com.raditha.dedup.analysis.ASTParameterExtractor(); // NEW
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

        // Note: Old safety check for METHOD_CALL/TYPE variations removed
        // because similarity.variations() is now null (AST-mode).
        // Our new AST analyzer handles method call variations by parameterizing them.

        // NEW: Perform AST-based variation analysis
        // Get the first two sequences from the cluster for analysis
        StatementSequence seq1 = cluster.primary();
        StatementSequence seq2 = cluster.duplicates().isEmpty() ? seq1 : cluster.duplicates().get(0).seq2(); // Get seq2
                                                                                                             // from
                                                                                                             // SimilarityPair

        // Get CompilationUnits
        CompilationUnit cu1 = seq1.compilationUnit();
        CompilationUnit cu2 = seq2.compilationUnit();

        // Perform AST-based variation analysis
        com.raditha.dedup.model.VariationAnalysis astAnalysis = astVariationAnalyzer.analyzeVariations(seq1, seq2, cu1,
                cu2);

        // Extract parameters using AST-based extractor
        com.raditha.dedup.model.ExtractionPlan extractionPlan = astParameterExtractor.extractParameters(astAnalysis);

        // Convert to old ParameterSpec list for backward compatibility
        List<ParameterSpec> parameters = new java.util.ArrayList<>(extractionPlan.parameters());

        // CRITICAL: Also convert arguments (variable references) to parameters
        // These are variables like 'userName' that are used but not defined in the
        // block
        for (com.raditha.dedup.model.ArgumentSpec arg : extractionPlan.arguments()) {
            // Only add if not already present (avoid duplicates)
            if (parameters.stream().noneMatch(p -> p.getName().equals(arg.name()))) {
                parameters.add(new ParameterSpec(
                        arg.name(),
                        arg.type(),
                        java.util.Collections.emptyList(), // No variations, it's consistent
                        -1, // No specific variation position
                        null, null // No specific AST node to replace
                ));
            }
        }

        // Add captured variables (variables used but not defined in sequence, and
        // constant across duplicates)
        List<ParameterSpec> capturedParams = identifyCapturedParameters(cluster.primary(), parameters);
        parameters.addAll(capturedParams);

        // Filter out internal parameters (defined in sequence)
        parameters = filterInternalParameters(parameters, cluster.primary());

        // Determine strategy
        RefactoringStrategy strategy = determineStrategy(cluster);

        // Refine parameter types
        // If ParameterExtractor defaulted to "Object", try to infer type from example
        // values
        List<ParameterSpec> refinedParameters = new java.util.ArrayList<>();
        for (ParameterSpec p : parameters) {
            if ("Object".equals(p.getType().asString()) && !p.getExampleValues().isEmpty()) {
                String val = p.getExampleValues().get(0);
                String inferred = null;
                try {
                    inferred = findTypeInContext(cluster.primary(), val);
                } catch (Exception e) {
                    // ignore
                }

                if (inferred != null && !inferred.equals("Object")) {
                    refinedParameters.add(new ParameterSpec(
                            p.getName(),
                            StaticJavaParser.parseType(inferred),
                            p.getExampleValues(),
                            p.getVariationIndex(),
                            p.getStartLine(),
                            p.getStartColumn()));
                } else {
                    refinedParameters.add(p);
                }
            } else {
                refinedParameters.add(p);
            }
        }
        parameters = refinedParameters;

        // Generate method name
        String methodName = suggestMethodName(cluster, strategy);

        // Initialize primary return variable (may be set by truncation logic or data
        // flow analysis)
        String primaryReturnVariable = null;

        // Calculate valid statement count (truncate if internal dependencies found)
        int validStatementCount = -1;
        Set<String> declaredVars = astAnalysis.getDeclaredInternalVariables();

        // Check varying expressions for internal dependency
        for (VaryingExpression var : astAnalysis.getVaryingExpressions()) {
            // Check if expression uses any internal variable
            Set<String> usedInternalVars = new HashSet<>();
            var.expr1().findAll(com.github.javaparser.ast.expr.NameExpr.class).forEach(n -> {
                if (declaredVars.contains(n.getNameAsString())) {
                    usedInternalVars.add(n.getNameAsString());
                }
            });

            if (!usedInternalVars.isEmpty()) {
                // Found invalid variation. Truncate BEFORE this statement.
                validStatementCount = var.position();

                // If exactly one internal variable is used, that's our return candidate!
                if (usedInternalVars.size() == 1) {
                    primaryReturnVariable = usedInternalVars.iterator().next();
                }
                break;
            }
        }

        // Determine return type using data flow analysis
        String returnType;
        if (validStatementCount != -1) {
            // If primaryReturnVariable was identified from dependency, use it to determine
            // type
            if (primaryReturnVariable != null) {
                String typeStr = findTypeInContext(cluster.primary(), primaryReturnVariable);
                returnType = typeStr != null ? typeStr : "Object";
            } else {
                // Fallback: Re-analyze for return variables in the truncated sequence
                StatementSequence prefix = createPrefixSequence(cluster.primary(), validStatementCount);
                String liveOut = dataFlowAnalyzer.findReturnVariable(prefix, null);
                if (liveOut != null) {
                    primaryReturnVariable = liveOut;
                    String typeStr = findTypeInContext(cluster.primary(), liveOut);
                    returnType = typeStr != null ? typeStr : "Object";
                } else {
                    returnType = "void";
                }
            }
        } else {
            returnType = determineReturnType(cluster);
        }

        // Create empty TypeCompatibility for backward compat
        TypeCompatibility typeCompat = new TypeCompatibility(
                true,
                java.util.Collections.emptyMap(),
                "void",
                java.util.Collections.emptyList());

        // Calculate confidence
        double confidence = calculateConfidence(cluster, typeCompat, parameters);

        // duplicates
        // This helps when data flow analysis fails for a specific duplicate but works
        // for the primary
        // duplicates
        // NOTE: we re-check primaryReturnVariable only if it wasn't set by truncation
        // logic
        if (primaryReturnVariable == null && returnType != null && !"void".equals(returnType)) {
            primaryReturnVariable = dataFlowAnalyzer.findReturnVariable(cluster.primary(), returnType);
        }

        return new RefactoringRecommendation(
                strategy,
                methodName,
                parameters,
                StaticJavaParser.parseType(returnType != null ? returnType : "void"),
                "",
                confidence,
                cluster.estimatedLOCReduction(),
                primaryReturnVariable,
                validStatementCount); // Passed new argument
    }

    /**
     * Determine the return type for the extracted method.
     * Checks all duplicates to find a common, compatible type.
     */
    private String determineReturnType(DuplicateCluster cluster) {
        Set<String> returnTypes = new HashSet<>();
        String primaryType = analyzeReturnTypeForSequence(cluster.primary());

        if (primaryType != null) {
            returnTypes.add(primaryType);
        }

        // Check all duplicates
        for (var pair : cluster.duplicates()) {
            // SimilarityPair contains (seq1=Primary, seq2=Duplicate) usually, or duplicates
            // relative to primary
            // We want the duplicate sequence (seq2)
            StatementSequence duplicate = pair.seq2();
            String type = analyzeReturnTypeForSequence(duplicate);
            if (type != null) {
                returnTypes.add(type);
            }
        }

        if (returnTypes.isEmpty()) {
            return "void";
        }

        // PRIORITY: Check for domain objects (User, Customer, etc.) FIRST
        // This avoids issues where one duplicate returns a field (String) and another
        // returns the object (User)
        // We prefer the richer object type so call sites can use getters on it.

        // PRIORITY: Check for domain objects (User, Customer, etc.)
        Optional<String> domainType = returnTypes.stream()
                .filter(t -> !t.equals("int") && !t.equals("long") && !t.equals(DOUBLE) &&
                        !t.equals(BOOLEAN) && !t.equals("void") && !t.equals(STRING))
                .findFirst();

        if (domainType.isPresent()) {
            return domainType.get(); // Return User, Customer, Order, etc.
        }

        // Primitive unification
        boolean hasInt = returnTypes.contains("int");
        boolean hasLong = returnTypes.contains("long");
        boolean hasDouble = returnTypes.contains(DOUBLE);

        // Heuristic: Check for time-related variables in duplicates if inference failed
        boolean hasObject = returnTypes.stream()
                .anyMatch(t -> !t.equals("int") && !t.equals("long") && !t.equals(DOUBLE) &&
                        !t.equals(BOOLEAN) && !t.equals("void"));

        if (!hasObject && !hasLong && !hasDouble) {
            boolean hasTimeVar = cluster.duplicates().stream()
                    .map(d -> d.seq2().statements().toString())
                    .anyMatch(s -> s.contains("timeout") || s.contains("elapsed") || s.contains("Time"));
            if (hasTimeVar) {
                hasLong = true;
            }
        }

        if (hasDouble)
            return DOUBLE;
        if (hasLong)
            return "long";
        if (hasInt)
            return "int";

        // Fallback to first found
        return returnTypes.iterator().next();
    }

    private String analyzeReturnTypeForSequence(StatementSequence sequence) {
        // PRIORITY 1: Check for live-out variables
        // If a variable is used AFTER this sequence, it MUST be returned.
        String returnVarName = dataFlowAnalyzer.findReturnVariable(sequence, "void"); // Pass "void" to bypass type
                                                                                      // filtering

        System.out.println("DEBUG analyzeReturnTypeForSequence var=" + returnVarName);

        if (returnVarName != null) {
            // Get the actual type of that variable
            String varType = getVariableType(sequence, returnVarName);
            System.out.println("DEBUG analyzeReturnTypeForSequence varType=" + varType);
            // Return the variable's type, NOT the return statement's expression type
            if (varType != null && !"void".equals(varType)) {
                return varType;
            }
        }

        // PRIORITY 2: Check for logical return statements
        // If the block explicitly returns a value, that is the return type.
        String explicitType = analyzeReturnStatementType(sequence);
        System.out.println("DEBUG analyzeReturnTypeForSequence [" +
                (sequence.range().toString()) + "]: explicitType=" + explicitType);

        if (explicitType != null) {
            return explicitType;
        }

        return null;
    }

    /**
     * Analyze return statement within the sequence to determine actual return type.
     * Handles cases where return statement is part of the duplicate.
     * <p>
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
     * Uses resolution, manual lookup, and method naming conventions.
     */
    private String inferTypeFromMethodCall(MethodCallExpr methodCall, StatementSequence sequence) {
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

        return null;
    }

    /**
     * Extract generic type parameter from field declaration.
     * E.g., for "repository" with field "Repository<User> repository", returns
     * "User"
     */
    private String extractGenericTypeFromScope(StatementSequence sequence, String scopeName) {

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

        return null;
    }

    /**
     * Infer type from other expression types.
     */
    private String inferTypeFromExpression(Expression expr) {
        // String literals
        if (expr.isStringLiteralExpr()) {
            return STRING;
        }

        // Integer literals
        if (expr.isIntegerLiteralExpr() || expr.isLongLiteralExpr()) {
            return "int";
        }

        // Boolean literals
        if (expr.isBooleanLiteralExpr()) {
            return BOOLEAN;
        }

        // Object creation
        if (expr.isObjectCreationExpr()) {
            return expr.asObjectCreationExpr().getType().asString();
        }

        // Binary expressions (concatenation)
        if (expr.isBinaryExpr()) {
            return STRING;
        }

        throw new TypeInferenceException("Unable to infer type for expression: " + expr);
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
        existingParams.forEach(p -> existingParamNames.add(p.getName()));

        List<ParameterSpec> capturedParams = new java.util.ArrayList<>();

        // Find CU for type checks
        CompilationUnit cu = sequence.statements().getFirst().findCompilationUnit().orElse(null);

        // AST: collect fields from the containing class
        boolean containingMethodIsStatic = false;
        java.util.Map<String, FieldInfo> classFields = new java.util.HashMap<>();

        var methodOpt = sequence.containingMethod();
        if (methodOpt != null) {
            containingMethodIsStatic = methodOpt.isStatic();
            var classDecl = methodOpt.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                    .orElseThrow();

            classDecl.getFields().forEach(fd -> {
                boolean isStatic = fd.getModifiers().stream()
                        .anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.STATIC);
                fd.getVariables().forEach(
                        v -> classFields.put(v.getNameAsString(), new FieldInfo(v.getType().asString(), isStatic)));
            });
        }

        for (String varName : capturedVars) {
            // Skip pseudo-variables and duplicates
            if (varName == null || varName.isEmpty())
                continue;
            if (varName.equals("this") || varName.equals("super"))
                continue;
            if (existingParamNames.contains(varName))
                continue;

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
                if (AbstractCompiler.findType(cu, varName) != null) {
                    continue;
                }
            }

            // NEW: Field-aware captured parameter handling
            FieldInfo fi = classFields.get(varName);
            if (fi != null) {
                // If it is a class field, decide based on helper accessibility
                // Helper mirrors containing method staticness
                // - If containing method is non-static → helper non-static → instance fields
                // are accessible → SKIP param
                // - If containing method is static:
                // • static field → accessible → SKIP param
                // • non-static field → NOT accessible → KEEP as parameter with field type
                if (!containingMethodIsStatic && fi.isStatic) {
                    // non-static field needed for static helper; will pass as parameter
                    String type = fi.type != null ? fi.type : "Object";
                    capturedParams.add(new ParameterSpec(varName, StaticJavaParser.parseType(type), List.of(varName),
                            null, null, null));
                }
                continue; // skip adding as parameter
            }

            // Determine type of true captured variable (non-field or unresolved field)
            String type = findTypeInContext(sequence, varName);
            if ("void".equals(type)) {
                continue;
            }

            capturedParams.add(
                    new ParameterSpec(varName, StaticJavaParser.parseType(type != null ? type : "Object"),
                            List.of(varName), null, null, null));
        }

        return capturedParams;
    }

    /**
     * Check if a statement represents an unconditional exit (return or throw).
     * Handles blocks and if/else structures recursively.
     */
    private boolean isUnconditionalExit(Statement stmt) {
        if (stmt.isReturnStmt() || stmt.isThrowStmt()) {
            return true;
        }

        if (stmt.isBlockStmt()) {
            var stmts = stmt.asBlockStmt().getStatements();
            if (stmts.isEmpty())
                return false;
            // Check the last statement of the block
            return isUnconditionalExit(stmts.get(stmts.size() - 1));
        }

        if (stmt.isIfStmt()) {
            var ifStmt = stmt.asIfStmt();
            // Must have both then and else parts, and both must be unconditional exits
            if (ifStmt.getElseStmt().isPresent()) {
                return isUnconditionalExit(ifStmt.getThenStmt()) &&
                        isUnconditionalExit(ifStmt.getElseStmt().get());
            }
            return false;
        }

        // Other control structures (Try, Switch, etc.) could be added here
        // For now, assume they are not unconditional exits
        return false;
    }

    /**
     * Check if the sequence contains any explicit return statements.
     * Care must be taken to not count returns inside lambdas or anonymous classes.
     */
    private boolean containsExplicitReturn(StatementSequence sequence) {
        for (Statement stmt : sequence.statements()) {
            // We use a visitor or a manual finder that stops at scope boundaries
            // Simple finder fails because it goes into lambdas.
            // Using a stream with filtering:

            // Fast check: does string contain "return"?
            if (!stmt.toString().contains("return"))
                continue;

            // Detailed check: Visit nodes
            if (hasReturnInScope(stmt))
                return true;
        }
        return false;
    }

    private boolean hasReturnInScope(com.github.javaparser.ast.Node node) {
        if (node instanceof com.github.javaparser.ast.stmt.ReturnStmt) {
            return true;
        }
        // STOP traversal at new scopes
        if (node instanceof com.github.javaparser.ast.expr.LambdaExpr ||
                node instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration ||
                node instanceof com.github.javaparser.ast.expr.ObjectCreationExpr) { // Anonymous class
            return false;
        }

        for (com.github.javaparser.ast.Node child : node.getChildNodes()) {
            if (hasReturnInScope(child))
                return true;
        }
        return false;
    }

    private String findTypeInContext(StatementSequence sequence, String varName) {
        // 1. Scan statements for any variable declaration matching this name
        for (Statement stmt : sequence.statements()) {
            for (VariableDeclarationExpr vde : stmt.findAll(VariableDeclarationExpr.class)) {
                for (var v : vde.getVariables()) {
                    if (v.getNameAsString().equals(varName)) {
                        return resolveType(v.getType(), v, sequence);
                    }
                }
            }

            // Check Lambda parameters
            for (com.github.javaparser.ast.expr.LambdaExpr lambda : stmt
                    .findAll(com.github.javaparser.ast.expr.LambdaExpr.class)) {
                for (com.github.javaparser.ast.body.Parameter param : lambda.getParameters()) {
                    if (param.getNameAsString().equals(varName)) {
                        if (!param.getType().isUnknownType() && !param.getType().isVarType()) {
                            String resolved = resolveType(param.getType(), lambda, sequence);
                            if (resolved != null)
                                return resolved;
                        }
                        return "Object";
                    }
                }
            }
        }

        // 2. Check field declarations in the containing class
        if (sequence.containingMethod() != null) {
            var classDecl = sequence.containingMethod()
                    .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
            if (classDecl.isPresent()) {
                for (var field : classDecl.get().getFields()) {
                    for (var v : field.getVariables()) {
                        if (v.getNameAsString().equals(varName)) {
                            return resolveType(field.getElementType(), field, sequence);
                        }
                    }
                }
            }
        }

        // 3. Check parameters of the containing method
        if (sequence.containingMethod() != null) {
            for (var param : sequence.containingMethod().getParameters()) {
                if (param.getNameAsString().equals(varName)) {
                    return resolveType(param.getType(), param, sequence);
                }
            }
        }

        // 4. Fallback: Scan method body for variables declared outside the block
        if (sequence.containingMethod() != null && sequence.containingMethod().getBody().isPresent()) {
            for (VariableDeclarationExpr vde : sequence.containingMethod().getBody().get()
                    .findAll(VariableDeclarationExpr.class)) {
                for (var v : vde.getVariables()) {
                    if (v.getNameAsString().equals(varName)) {
                        return resolveType(v.getType(), v, sequence);
                    }
                }
            }
        }

        // 5. Fallback: Check if it's an expression (e.g. method call)
        if (varName.contains("(") || varName.contains(".")) {
            try {
                com.github.javaparser.ast.expr.Expression expr = com.github.javaparser.StaticJavaParser
                        .parseExpression(varName);
                if (expr.isMethodCallExpr()) {
                    var call = expr.asMethodCallExpr();
                    if (call.getScope().isPresent()) {
                        String scopeName = call.getScope().get().toString();
                        String scopeType = findTypeInContext(sequence, scopeName);

                        // Find CU for this type
                        CompilationUnit typeCU = allCUs.get(scopeType);
                        if (typeCU == null) {
                            // Try simple name matching
                            for (var entry : allCUs.entrySet()) {
                                if (entry.getKey().endsWith("." + scopeType) || entry.getKey().equals(scopeType)) {
                                    typeCU = entry.getValue();
                                    break;
                                }
                            }
                        }

                        if (typeCU != null) {
                            String methodName = call.getNameAsString();
                            var methods = typeCU.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
                                    .stream()
                                    .filter(m -> m.getNameAsString().equals(methodName))
                                    .toList();

                            if (!methods.isEmpty()) {
                                // Return type
                                return resolveType(methods.get(0).getType(), methods.get(0), sequence);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore parse errors
            }
        }

        return null;
    }

    private String resolveType(com.github.javaparser.ast.type.Type type, com.github.javaparser.ast.Node contextNode,
            StatementSequence sequence) {
        // Attempt to resolve using AbstractCompiler
        var classDecl = contextNode.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
        if (classDecl.isPresent()) {
            String fqn = AbstractCompiler.resolveTypeFqn(type, classDecl.get(), null);
            if (fqn != null && !fqn.equals("java.lang.Object") && !fqn.equals("Object")) {
                return simplifyType(fqn);
            }
        }

        // Fallback to AST string
        String astType = type.asString();
        if ("var".equals(astType)) {
            // If implicit var, we might need initializer inference, but without solver it's
            // hard.
            // We can check initializer if available on the variable declarator
            if (contextNode instanceof com.github.javaparser.ast.body.VariableDeclarator v) {
                if (v.getInitializer().isPresent()) {
                    var init = v.getInitializer().get();
                    if (init.isMethodCallExpr()) {
                        return inferTypeFromMethodCall(init.asMethodCallExpr(), sequence);
                    }
                    return inferTypeFromExpression(init);
                }
            }
            return "Object"; // Worst case
        }
        return astType;
    }

    private String simplifyType(String fqn) {
        if (fqn == null)
            return null;
        // Don't simplify primitives
        if (fqn.equals("int") || fqn.equals(BOOLEAN) || fqn.equals(DOUBLE) || fqn.equals("void"))
            return fqn;

        int lastDot = fqn.lastIndexOf('.');
        if (lastDot > 0) {
            return fqn.substring(lastDot + 1);
        }
        return fqn;
    }

    // [Rest of the class remains unchanged - keeping existing methods]
    private RefactoringStrategy determineStrategy(DuplicateCluster cluster) {

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

        if (parameters.size() > 5)
            score *= 0.7;

        if (cluster.getAverageSimilarity() < 0.85)
            score *= 0.8;

        return score;
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
            for (String val : p.getExampleValues()) {
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

    public static class TypeInferenceException extends RuntimeException {
        public TypeInferenceException(String message) {
            super(message);
        }
    }

    // Lightweight holder for field metadata
    private record FieldInfo(String type, boolean isStatic) {
    }

    private StatementSequence createPrefixSequence(StatementSequence fullSequence, int count) {
        if (count >= fullSequence.statements().size()) {
            return fullSequence;
        }
        List<Statement> prefixStmts = fullSequence.statements().subList(0, count);

        // Calculate new range based on prefix statements
        Range fullRange = fullSequence.range();
        int endLine = prefixStmts.get(count - 1).getEnd().map(p -> p.line).orElse(fullRange.endLine());
        int endColumn = prefixStmts.get(count - 1).getEnd().map(p -> p.column).orElse(fullRange.endColumn());

        Range prefixRange = new Range(fullRange.startLine(), fullRange.startColumn(), endLine, endColumn);

        // Pass original compilation unit and file path
        return new StatementSequence(
                prefixStmts,
                prefixRange,
                fullSequence.startOffset(),
                fullSequence.containingMethod(),
                fullSequence.compilationUnit(),
                fullSequence.sourceFilePath());
    }
}
