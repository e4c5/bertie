package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.analysis.VariationTracker;
import com.raditha.dedup.model.*;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts duplicate code to a private helper method.
 * This is the most common and safest refactoring strategy.
 */
public class ExtractMethodRefactorer {

    public static final String STRING = "String";
    public static final String DOUBLE = "Double";
    public static final String Boolean = "Boolean";

    /**
     * Perform extract method refactoring.
     * 
     * FIXED: Now tracks ALL modified compilation units, not just primary.
     * This fixes the bug where seq1/seq2 in different files had method calls
     * but the method definition was never written to their files.
     */
    public RefactoringResult refactor(DuplicateCluster cluster, RefactoringRecommendation recommendation) {

        StatementSequence primary = cluster.primary();

        // Track all modified compilation units
        Map<CompilationUnit, Path> modifiedCUs = new LinkedHashMap<>();
        modifiedCUs.put(primary.compilationUnit(), primary.sourceFilePath());

        // 1. Create the new helper method (tentative)
        MethodDeclaration helperMethod = createHelperMethod(primary, recommendation);

        // 2. Add method to the class (modifies primary CU) — but first, try to REUSE
        // existing equivalent helper
        ClassOrInterfaceDeclaration containingClass = primary.containingMethod()
                .findAncestor(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalStateException("No containing class found"));

        String methodNameToUse = recommendation.getSuggestedMethodName();
        MethodDeclaration equivalent = findEquivalentHelper(containingClass, helperMethod, primary.containingMethod());
        if (equivalent == null) {
            // No equivalent helper exists; add our newly created one
            containingClass.addMember(helperMethod);
        } else {
            // Reuse existing helper method name; skip adding duplicate
            methodNameToUse = equivalent.getNameAsString();
        }

        // 3. Replace all duplicate occurrences with method calls (track each CU)
        for (SimilarityPair pair : cluster.duplicates()) {
            replaceWithMethodCall(pair.seq1(), recommendation, pair.similarity().variations(), methodNameToUse);
            modifiedCUs.put(pair.seq1().compilationUnit(), pair.seq1().sourceFilePath());

            replaceWithMethodCall(pair.seq2(), recommendation, pair.similarity().variations(), methodNameToUse);
            modifiedCUs.put(pair.seq2().compilationUnit(), pair.seq2().sourceFilePath());
        }

        // 4. Convert all modified CUs to code strings
        Map<Path, String> modifiedFiles = modifiedCUs.entrySet().stream()
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getValue(), entry.getKey().toString()),
                        Map::putAll);

        // Create result with ALL modified files
        return new RefactoringResult(
                modifiedFiles,
                recommendation.getStrategy(),
                "Extracted method: " + methodNameToUse);
    }

    /**
     * Create the helper method from the primary sequence.
     * Simplified by delegating cohesive responsibilities to dedicated helpers.
     */
    private MethodDeclaration createHelperMethod(StatementSequence sequence,
            RefactoringRecommendation recommendation) {
        MethodDeclaration method = initializeHelperMethod(recommendation);
        applyMethodModifiers(method, sequence);
        setReturnType(method, recommendation);

        // Parameters with collision handling
        Set<String> declaredVars = collectDeclaredVariableNames(sequence);
        Map<String, String> paramNameOverrides = computeParamNameOverrides(declaredVars,
                recommendation.getSuggestedParameters());
        addParameters(method, recommendation.getSuggestedParameters(), paramNameOverrides);

        // Copy thrown exceptions
        copyThrownExceptions(method, sequence);

        // Determine body
        String returnType = method.getType().asString();
        String targetReturnVar = determineTargetReturnVar(sequence, returnType);
        BlockStmt body = buildHelperMethodBody(sequence, recommendation, targetReturnVar, paramNameOverrides);
        method.setBody(body);
        return method;
    }

    private MethodDeclaration initializeHelperMethod(RefactoringRecommendation recommendation) {
        MethodDeclaration method = new MethodDeclaration();
        method.setName(recommendation.getSuggestedMethodName());
        return method;
    }

    private void applyMethodModifiers(MethodDeclaration method, StatementSequence sequence) {
        if (sequence.containingMethod() != null && sequence.containingMethod().isStatic()) {
            method.setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC);
        } else {
            method.setModifiers(Modifier.Keyword.PRIVATE);
        }
    }

    private void setReturnType(MethodDeclaration method, RefactoringRecommendation recommendation) {
        com.github.javaparser.ast.type.Type returnType = recommendation.getSuggestedReturnType();
        method.setType(returnType != null ? returnType : new com.github.javaparser.ast.type.VoidType());
    }

    private Set<String> collectDeclaredVariableNames(StatementSequence sequence) {
        Set<String> declaredVars = new HashSet<>();
        for (Statement stmt : sequence.statements()) {
            stmt.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                    .forEach(v -> declaredVars.add(v.getNameAsString()));
        }
        return declaredVars;
    }

    private Map<String, String> computeParamNameOverrides(Set<String> declaredVars, List<ParameterSpec> params) {
        Map<String, String> overrides = new java.util.HashMap<>();
        for (ParameterSpec param : params) {
            String name = param.getName();
            if (declaredVars.contains(name)) {
                String newName = name + "Param";
                int counter = 2;
                while (declaredVars.contains(newName)) {
                    newName = name + "Param" + counter++;
                }
                overrides.put(name, newName);
            }
        }
        return overrides;
    }

    private void addParameters(MethodDeclaration method, List<ParameterSpec> params, Map<String, String> overrides) {
        for (ParameterSpec param : params) {
            String targetName = overrides.getOrDefault(param.getName(), param.getName());
            method.addParameter(new Parameter(param.getType().clone(), targetName));
        }
    }

    private void copyThrownExceptions(MethodDeclaration method, StatementSequence sequence) {
        if (sequence.containingMethod() != null) {
            NodeList<ReferenceType> exceptions = sequence.containingMethod().getThrownExceptions();
            for (ReferenceType exception : exceptions) {
                method.addThrownException(exception.clone());
            }
        }
    }

    private String determineTargetReturnVar(StatementSequence sequence, String returnType) {
        if (!"void".equals(returnType)) {
            return findReturnVariable(sequence, returnType);
        }
        return null;
    }

    private BlockStmt buildHelperMethodBody(StatementSequence sequence,
            RefactoringRecommendation recommendation,
            String targetReturnVar,
            Map<String, String> paramNameOverrides) {
        BlockStmt body = new BlockStmt();

        boolean hasExternalVars = hasExternalVariablesInReturn(sequence);
        List<Statement> statements = buildBodyStatements(sequence, recommendation, targetReturnVar, paramNameOverrides,
                hasExternalVars);
        for (Statement s : statements) {
            body.addStatement(s);
        }

        if (needsFinalReturn(body, targetReturnVar)) {
            body.addStatement(new ReturnStmt(new NameExpr(targetReturnVar)));
        }

        return body;
    }

    /**
     * Build the list of statements for the helper method body.
     * - Clones original statements
     * - Substitutes parameterized literals
     * - Skips complex return statements when external variables are involved
     * - Normalizes return statements to a simple variable return when needed
     */
    private List<Statement> buildBodyStatements(StatementSequence sequence,
            RefactoringRecommendation recommendation,
            String targetReturnVar,
            Map<String, String> paramNameOverrides,
            boolean hasExternalVars) {
        List<Statement> result = new ArrayList<>();
        for (Statement original : sequence.statements()) {
            Statement stmt = substituteParameters(original.clone(), sequence, recommendation, paramNameOverrides);

            if (shouldSkipReturnForExternalVars(stmt, hasExternalVars)) {
                continue;
            }

            stmt = normalizeReturnIfTargetsVariable(stmt, targetReturnVar);
            result.add(stmt);
        }
        return result;
    }

    /**
     * When the original return statement uses external variables that won't exist
     * in the helper,
     * we skip that return and add a simple return of the target variable at the
     * end.
     */
    private boolean shouldSkipReturnForExternalVars(Statement stmt, boolean hasExternalVars) {
        return hasExternalVars && stmt.isReturnStmt();
    }

    /**
     * If a return statement returns a complex expression that uses the target
     * variable,
     * replace it with a simple `return <targetVar>;`. Otherwise, leave it
     * unchanged.
     */
    private Statement normalizeReturnIfTargetsVariable(Statement stmt, String targetReturnVar) {
        if (targetReturnVar == null || !stmt.isReturnStmt()) {
            return stmt;
        }
        ReturnStmt rs = stmt.asReturnStmt();
        if (rs.getExpression().isEmpty()) {
            return stmt;
        }
        Expression expr = rs.getExpression().get();
        if (expr.isNameExpr() && expr.asNameExpr().getNameAsString().equals(targetReturnVar)) {
            return stmt; // already returns the variable directly
        }
        List<NameExpr> used = expr.findAll(NameExpr.class);
        for (NameExpr n : used) {
            if (n.getNameAsString().equals(targetReturnVar)) {
                return new ReturnStmt(new NameExpr(targetReturnVar));
            }
        }
        return stmt;
    }

    /**
     * Decide whether we must add a final `return <targetVar>;` at the end of the
     * body.
     */
    private boolean needsFinalReturn(BlockStmt body, String targetReturnVar) {
        if (targetReturnVar == null)
            return false;
        return body.getStatements().isEmpty() || !body.getStatements().getLast().get().isReturnStmt();
    }

    /**
     * Find the variable to return using data flow analysis.
     */
    private String findReturnVariable(StatementSequence sequence, String returnType) {
        DataFlowAnalyzer analyzer = new DataFlowAnalyzer();
        return analyzer.findReturnVariable(sequence, returnType);
    }

    /**
     * Check if a return expression references variables that are NOT defined within
     * the sequence.
     * Such "external" variables (like a prefix defined before the duplicate code)
     * cannot be
     * accessed in the extracted method, so we must return the object and let the
     * caller
     * reconstruct the expression.
     */
    private boolean hasExternalVariablesInReturn(StatementSequence sequence) {
        DataFlowAnalyzer dfa = new DataFlowAnalyzer();
        Set<String> definedInSequence = dfa.findDefinedVariables(sequence);

        // Find any return statement in the sequence
        for (Statement stmt : sequence.statements()) {
            if (stmt.isReturnStmt() && stmt.asReturnStmt().getExpression().isPresent()) {
                Expression returnExpr = stmt.asReturnStmt().getExpression().get();
                // Check all variable references in the return expression
                for (NameExpr nameExpr : returnExpr.findAll(NameExpr.class)) {
                    String varName = nameExpr.getNameAsString();
                    // If this variable is NOT defined in the sequence, it's external
                    if (!definedInSequence.contains(varName) && isLocalVariable(sequence, varName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check if a variable name refers to a local variable (defined in the
     * containing method
     * but NOT in the sequence).
     */
    private boolean isLocalVariable(StatementSequence sequence, String varName) {
        MethodDeclaration containingMethod = sequence.containingMethod();
        if (containingMethod == null || containingMethod.getBody().isEmpty()) {
            return false;
        }

        // Search for variable declaration in method body but BEFORE the sequence start
        int sequenceStartLine = sequence.range().startLine();
        BlockStmt methodBody = containingMethod.getBody().get();

        for (VariableDeclarationExpr varDecl : methodBody.findAll(VariableDeclarationExpr.class)) {
            if (varDecl.getRange().isPresent() &&
                    varDecl.getRange().get().begin.line < sequenceStartLine) {
                for (var variable : varDecl.getVariables()) {
                    if (variable.getNameAsString().equals(varName)) {
                        return true; // Found as local variable before sequence
                    }
                }
            }
        }

        // Also check method parameters
        for (var param : containingMethod.getParameters()) {
            if (param.getNameAsString().equals(varName)) {
                return true; // It's a method parameter, treated as external
            }
        }

        return false;
    }

    /**
     * Check if a return expression is "simple" - just returning the variable or a
     * method call on it.
     * Returns false for "complex" expressions like: prefix + user.getName()
     * where the expression combines the variable with other external variables.
     */
    private boolean isSimpleReturnOfVariable(Expression expr, String varName) {
        // Case 1: Just return the variable itself (return user;)
        if (expr.isNameExpr() && expr.asNameExpr().getNameAsString().equals(varName)) {
            return true;
        }

        // Case 2: Method call on the variable (return user.getName();)
        if (expr.isMethodCallExpr()) {
            MethodCallExpr mce = expr.asMethodCallExpr();
            if (mce.getScope().isPresent()) {
                Expression scope = mce.getScope().get();
                if (scope.isNameExpr() && scope.asNameExpr().getNameAsString().equals(varName)) {
                    return true;
                }
            }
        }

        // Case 3: Check if this is a binary expression (like prefix + user.getName())
        // - if it uses any variables OTHER than varName, it's complex
        if (expr.isBinaryExpr()) {
            // Find all name expressions used
            Set<String> usedVars = new HashSet<>();
            for (NameExpr nameExpr : expr.findAll(NameExpr.class)) {
                usedVars.add(nameExpr.getNameAsString());
            }
            // If there's more than one unique variable, it's complex
            usedVars.remove(varName);
            if (!usedVars.isEmpty()) {
                return false;
            }
        }

        // Default: assume simple
        return true;
    }

    private String resolveBindingForSequence(Map<StatementSequence, com.raditha.dedup.model.ExprInfo> bindings,
            StatementSequence target) {
        // Helper to extract text from ExprInfo
        java.util.function.Function<com.raditha.dedup.model.ExprInfo, String> asText = ei -> {
            if (ei == null)
                return null;
            if (ei.expr() != null)
                return ei.expr().toString();
            return ei.text();
        };

        // Direct identity match first
        String v = asText.apply(bindings.get(target));
        if (v != null) {
            return v;
        }
        // Fallback 1: match by exact source range (start/end lines)

        int start = target.range().startLine();
        int end = target.range().endLine();
        for (Map.Entry<StatementSequence, com.raditha.dedup.model.ExprInfo> entry : bindings.entrySet()) {
            StatementSequence seq = entry.getKey();
            if (seq == null)
                continue;

            if (seq.range().startLine() == start && seq.range().endLine() == end) {
                return asText.apply(entry.getValue());
            }

        }

        return null;
    }

    // Context-aware extraction for ambiguous multiple-String parameters
    private String extractStringArgFromCall(StatementSequence sequence, String methodName) {
        for (Statement stmt : sequence.statements()) {
            for (MethodCallExpr call : stmt.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals(methodName) && call.getArguments().size() >= 1) {
                    Expression arg = call.getArgument(0);
                    if (arg.isStringLiteralExpr() || arg.isNameExpr()) {
                        return arg.toString(); // includes quotes
                    }
                }
            }
        }
        return null;
    }

    private String extractStringByContext(StatementSequence sequence, int stringParamOrder) {
        // Heuristic mapping: first String param → setName(...), second → equals(...)
        if (stringParamOrder == 0) {
            return extractStringArgFromCall(sequence, "setName");
        } else if (stringParamOrder == 1) {
            return extractStringArgFromCall(sequence, "equals");
        }
        return null;
    }

    /**
     * Replace duplicate code with a method call.
     */
    private void replaceWithMethodCall(StatementSequence sequence, RefactoringRecommendation recommendation,
            VariationAnalysis variations, String methodNameToUse) {
        debugReplaceWithMethodCall(recommendation, variations, methodNameToUse);
        MethodDeclaration containingMethod = sequence.containingMethod();
        if (containingMethod == null || containingMethod.getBody().isEmpty()) {
            return;
        }

        BlockStmt block = containingMethod.getBody().get();

        // 1) Build arguments. If anything fails, bail out safely.
        NodeList<Expression> arguments = buildArgumentsForCall(recommendation, variations, sequence);
        if (arguments == null) {
            return; // Could not resolve args safely
        }

        // 2) Create the method call expression
        MethodCallExpr methodCall = new MethodCallExpr(methodNameToUse, arguments.toArray(new Expression[0]));

        // 3) Remember any original return inside the duplicate sequence
        ReturnStmt originalReturnValues = findOriginalReturnInSequence(sequence);

        // 4) Locate sequence in the block and replace
        int startIdx = findStatementIndex(block, sequence);
        if (startIdx < 0)
            return;

        // Remove the old statements belonging to the sequence
        int removeCount = sequence.statements().size();
        for (int i = 0; i < removeCount && startIdx < block.getStatements().size(); i++) {
            block.getStatements().remove(startIdx);
        }

        // 5) Insert new statements depending on return type
        if (recommendation.getSuggestedReturnType() != null && recommendation.getSuggestedReturnType().isVoidType()) {
            insertVoidReplacement(block, startIdx, methodCall, originalReturnValues);
            return;
        }

        // For non-void: compute a good target var name and decide if we can inline
        // return
        String varName = inferReturnVariable(sequence, recommendation);
        boolean nextIsReturn = startIdx < block.getStatements().size()
                && block.getStatements().get(startIdx).isReturnStmt();
        boolean returnHasExternalVars = hasExternalVariablesInReturn(sequence);
        if (nextIsReturn) {
            ReturnStmt nextReturn = block.getStatements().get(startIdx).asReturnStmt();
            returnHasExternalVars = returnHasExternalVars || affectsNextReturn(sequence, varName, nextReturn);
        }
        if (returnHasExternalVars && varName == null) {
            // Ensure we go through the reconstruct path
            varName = firstDefinedVariable(sequence);
        }

        boolean shouldReturnDirectly = canInlineReturn(containingMethod, block, startIdx, originalReturnValues,
                returnHasExternalVars, nextIsReturn);

        insertValueReplacement(block, startIdx, recommendation, methodCall, originalReturnValues, varName,
                shouldReturnDirectly, nextIsReturn);
    }

    private static void debugReplaceWithMethodCall(RefactoringRecommendation recommendation,
            VariationAnalysis variations, String methodNameToUse) {
        var log = LoggerFactory.getLogger(VariationTracker.class);
        if (log.isDebugEnabled()) {
            log.debug("DEBUG replaceWithMethodCall: method = {}, variations = {}, params = {}", methodNameToUse,
                    (variations != null), recommendation.getSuggestedParameters().size());
            if (variations != null) {
                log.debug("DEBUG replaceWithMethodCall: exprBindings = {}",
                        variations.exprBindings() != null ? variations.exprBindings().size() : "null");
            }
        }
    }

    private NodeList<Expression> buildArgumentsForCall(RefactoringRecommendation recommendation,
            VariationAnalysis variations,
            StatementSequence sequence) {
        // Delegate to a focused builder that preserves existing behavior and guardrails
        return new ArgumentBuilder().buildArgs(recommendation, variations, sequence);
    }

    /**
     * Focused component to construct method call arguments while preserving the
     * exact resolution order and safety guardrails previously embedded in
     * {@link #buildArgumentsForCall}.
     *
     * This inner class intentionally reuses existing helpers from the outer
     * refactorer (variation resolution, context-aware string extraction,
     * AST-based value extraction, and expression conversion) to minimize code
     * duplication and behavior drift.
     */
    private class ArgumentBuilder {
        NodeList<Expression> buildArgs(RefactoringRecommendation recommendation,
                VariationAnalysis variations,
                StatementSequence sequence) {
            // Determine ordering among String parameters for context-aware extraction
            List<ParameterSpec> stringParams = new ArrayList<>();
            for (ParameterSpec p : recommendation.getSuggestedParameters()) {
                if (isStringType(p.getType())) {
                    stringParams.add(p);
                }
            }

            NodeList<Expression> arguments = new NodeList<>();
            for (ParameterSpec param : recommendation.getSuggestedParameters()) {
                Expression expr = resolveValue(variations, sequence, stringParams, param);
                if (expr == null)
                    return null; // cannot resolve safely

                // AST-based type guardrails
                com.github.javaparser.ast.type.Type pType = param.getType();

                if (isNumericType(pType) && expr.isStringLiteralExpr()) {
                    return null;
                }

                if (isStringType(pType) && (expr.isIntegerLiteralExpr() || expr.isLongLiteralExpr() ||
                        expr.isDoubleLiteralExpr() || expr.isBooleanLiteralExpr())) {
                    return null;
                }

                if (isClassType(pType) && !(expr.isClassExpr() ||
                        (expr.isFieldAccessExpr() && expr.asFieldAccessExpr().getNameAsString().equals("class")))) {
                    // Try to adapt NameExpr or other simple forms into ClassExpr
                    Expression adapted = null;
                    if (expr.isNameExpr()) {
                        adapted = new ClassExpr(new ClassOrInterfaceType(null, expr.asNameExpr().getNameAsString()));
                    }
                    if (adapted == null)
                        return null; // unsafe
                    expr = adapted;
                }

                arguments.add(expr);
            }
            return arguments;
        }

        /**
         * Check if the given AST Type represents a String type.
         */
        private boolean isStringType(com.github.javaparser.ast.type.Type type) {
            if (!type.isClassOrInterfaceType()) {
                return false;
            }
            String name = type.asClassOrInterfaceType().getNameAsString();
            return sa.com.cloudsolutions.antikythera.evaluator.Reflect.STRING.equals(name) ||
                    sa.com.cloudsolutions.antikythera.evaluator.Reflect.JAVA_LANG_STRING.equals(name);
        }

        /**
         * Check if the given AST Type represents a numeric type (primitive or wrapper).
         * Includes: int, Integer, long, Long, double, Double, boolean, Boolean
         */
        private boolean isNumericType(com.github.javaparser.ast.type.Type type) {
            // Check primitive types first
            if (type.isPrimitiveType()) {
                return true;
            }

            // Check wrapper types
            if (type.isClassOrInterfaceType()) {
                String name = type.asClassOrInterfaceType().getNameAsString();
                return sa.com.cloudsolutions.antikythera.evaluator.Reflect.INTEGER.equals(name) ||
                        sa.com.cloudsolutions.antikythera.evaluator.Reflect.LONG.equals(name) ||
                        sa.com.cloudsolutions.antikythera.evaluator.Reflect.DOUBLE.equals(name) ||
                        sa.com.cloudsolutions.antikythera.evaluator.Reflect.BOOLEAN.equals(name);
            }

            return false;
        }

        /**
         * Check if the given AST Type represents a Class<?> type.
         */
        private boolean isClassType(com.github.javaparser.ast.type.Type type) {
            if (!type.isClassOrInterfaceType()) {
                return false;
            }
            String name = type.asClassOrInterfaceType().getNameAsString();
            // Match "Class" or "Class<?>" or "Class<SomeType>"
            return name.equals("Class") || name.startsWith("Class<");
        }

        private Expression resolveValue(VariationAnalysis variations,
                StatementSequence sequence,
                List<ParameterSpec> stringParams,
                ParameterSpec param) {
            String pType = param.getType().asString();
            String valToUse = null;

            // 1) Variation-based (most accurate)
            if (variations != null && param.getVariationIndex() != null) {
                var exprBindings = variations.exprBindings() != null
                        ? variations.exprBindings().get(param.getVariationIndex())
                        : null;
                if (exprBindings != null) {
                    valToUse = resolveBindingForSequence(exprBindings, sequence);
                }
                if (valToUse == null) {
                    var legacyBindings = variations.valueBindings() != null
                            ? variations.valueBindings().get(param.getVariationIndex())
                            : null;
                    if (legacyBindings != null) {
                        Map<StatementSequence, ExprInfo> adapted = new java.util.HashMap<>();
                        for (var entry : legacyBindings.entrySet()) {
                            adapted.put(entry.getKey(), ExprInfo.fromText(entry.getValue()));
                        }
                        valToUse = resolveBindingForSequence(adapted, sequence);
                    }
                }
                if (valToUse != null) {
                    Expression expr = toExpressionForParam(pType, valToUse);
                    if (expr != null)
                        return expr;
                }
            }

            // 2) Context-aware extraction for multiple String parameters
            if (valToUse == null && STRING.equals(pType) && stringParams.size() >= 2) {
                int ord = stringParams.indexOf(param);
                if (ord >= 0) {
                    String ctxVal = extractStringByContext(sequence, ord);
                    if (ctxVal != null) {
                        Expression expr = toExpressionForParam(pType, ctxVal);
                        if (expr != null)
                            return expr;
                    }
                }
            }

            // 3) Generic AST-based extraction
            Expression actualValueExpr = extractActualValue(sequence, param);
            if (actualValueExpr != null) {
                // For String parameters, check against examples if present
                if (STRING.equals(pType) && actualValueExpr.isStringLiteralExpr()
                        && !param.getExampleValues().isEmpty()) {
                    String lit = actualValueExpr.asStringLiteralExpr().getValue(); // unquoted value
                    boolean matches = param.getExampleValues().stream()
                            .map(s -> s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s)
                            .anyMatch(s -> s.equals(lit));
                    if (!matches) {
                        actualValueExpr = null;
                    }
                }
            }
            return actualValueExpr;
        }
    }

    private Expression toExpressionForParam(String pType, String valToUse) {
        try {
            if (("Class<?>".equals(pType) || "Class".equals(pType)) && !valToUse.endsWith(".class")) {
                return new ClassExpr(new ClassOrInterfaceType(null, valToUse));
            }
            // Robust AST parsing
            return com.github.javaparser.StaticJavaParser.parseExpression(valToUse);
        } catch (Exception e) {
            return null;
        }
    }

    private ReturnStmt findOriginalReturnInSequence(StatementSequence sequence) {
        for (Statement stmt : sequence.statements()) {
            if (stmt.isReturnStmt()) {
                return stmt.asReturnStmt();
            }
        }
        return null;
    }

    private void insertVoidReplacement(BlockStmt block, int startIdx, MethodCallExpr methodCall,
            ReturnStmt originalReturnValues) {
        block.getStatements().add(startIdx, new ExpressionStmt(methodCall));
        if (originalReturnValues != null) {
            block.getStatements().add(startIdx + 1, new ReturnStmt());
        }
    }

    private String inferReturnVariable(StatementSequence sequence, RefactoringRecommendation recommendation) {
        String varName = findReturnVariable(sequence,
                recommendation.getSuggestedReturnType() != null ? recommendation.getSuggestedReturnType().asString()
                        : "void");
        DataFlowAnalyzer dfa = new DataFlowAnalyzer();
        Set<String> defined = dfa.findDefinedVariables(sequence);
        if (varName == null && recommendation.getPrimaryReturnVariable() != null) {
            if (defined.contains(recommendation.getPrimaryReturnVariable())) {
                varName = recommendation.getPrimaryReturnVariable();
            }
        }
        if (varName == null) {
            List<String> typedCandidates = new ArrayList<>();
            for (Statement stmt : sequence.statements()) {
                stmt.findAll(VariableDeclarationExpr.class).forEach(vde -> {
                    vde.getVariables().forEach(v -> {
                        if (v.getType().asString()
                                .equals(recommendation.getSuggestedReturnType() != null
                                        ? recommendation.getSuggestedReturnType().asString()
                                        : "")) {
                            typedCandidates.add(v.getNameAsString());
                        }
                    });
                });
            }
            if (typedCandidates.size() == 1) {
                varName = typedCandidates.get(0);
            }
        }
        return varName;
    }

    private boolean affectsNextReturn(StatementSequence sequence, String currentVarName, ReturnStmt nextReturn) {
        if (!nextReturn.getExpression().isPresent())
            return false;
        Expression returnExpr = nextReturn.getExpression().get();
        Set<String> returnVars = new HashSet<>();
        for (NameExpr nameExpr : returnExpr.findAll(NameExpr.class)) {
            returnVars.add(nameExpr.getNameAsString());
        }
        DataFlowAnalyzer postDfa = new DataFlowAnalyzer();
        Set<String> sequenceDefinedVars = postDfa.findDefinedVariables(sequence);
        for (String definedVar : sequenceDefinedVars) {
            if (returnVars.contains(definedVar)) {
                if (currentVarName == null || !isSimpleReturnOfVariable(returnExpr, currentVarName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String firstDefinedVariable(StatementSequence sequence) {
        DataFlowAnalyzer dfa2 = new DataFlowAnalyzer();
        Set<String> seqDefined = dfa2.findDefinedVariables(sequence);
        if (!seqDefined.isEmpty()) {
            return seqDefined.iterator().next();
        }
        return null;
    }

    private boolean canInlineReturn(MethodDeclaration containingMethod, BlockStmt block, int startIdx,
            ReturnStmt originalReturnValues, boolean returnHasExternalVars, boolean nextIsReturn) {
        if (returnHasExternalVars)
            return false;
        boolean methodIsVoid = containingMethod.getType().asString().equals("void");
        boolean blockEmptyAfterRemoval = block.getStatements().isEmpty();
        return ((nextIsReturn && originalReturnValues != null) ||
                (blockEmptyAfterRemoval && !methodIsVoid && originalReturnValues != null));
    }

    private void insertValueReplacement(BlockStmt block, int startIdx, RefactoringRecommendation recommendation,
            MethodCallExpr methodCall, ReturnStmt originalReturnValues, String varName,
            boolean shouldReturnDirectly, boolean nextIsReturn) {
        if (varName != null && shouldReturnDirectly) {
            if (nextIsReturn) {
                block.getStatements().remove(startIdx);
            }
            block.getStatements().add(startIdx, new ReturnStmt(methodCall));
            return;
        }

        if (varName != null) {
            VariableDeclarationExpr varDecl = new VariableDeclarationExpr(
                    recommendation.getSuggestedReturnType().clone(), varName);
            varDecl.getVariable(0).setInitializer(methodCall);
            block.getStatements().add(startIdx, new ExpressionStmt(varDecl));
            if (originalReturnValues != null && originalReturnValues.getExpression().isPresent()) {
                block.getStatements().add(startIdx + 1, originalReturnValues.clone());
            }
            return;
        }

        // Fallback: no var name found
        if (originalReturnValues != null && originalReturnValues.getExpression().isPresent()) {
            block.getStatements().add(startIdx, new ReturnStmt(methodCall));
        } else {
            block.getStatements().add(startIdx, new ExpressionStmt(methodCall));
        }
    }

    /**
     * Find the index of the first statement in a sequence within a block.
     */
    private int findStatementIndex(BlockStmt block, StatementSequence sequence) {
        if (sequence.statements().isEmpty()) {
            return -1;
        }

        NodeList<Statement> blockStmts = block.getStatements();

        // Simple approach: match by line number
        int targetLine = sequence.range().startLine();

        for (int i = 0; i < blockStmts.size(); i++) {
            Statement stmt = blockStmts.get(i);
            if (stmt.getRange().isPresent() &&
                    stmt.getRange().get().begin.line == targetLine) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Extract the actual value used in this sequence for a parameter.
     * Returns the AST Expression found in the sequence, or a parsed Expression from
     * examples.
     */
    private Expression extractActualValue(StatementSequence sequence, ParameterSpec param) {
        List<Expression> foundValues = new ArrayList<>();

        for (Statement stmt : sequence.statements()) {
            stmt.findAll(Expression.class).forEach(expr -> {
                if (couldBeParameterValue(expr, param)) {
                    foundValues.add(expr);
                }
            });
        }

        if (!foundValues.isEmpty()) {
            return foundValues.get(0);
        }

        // Fallback to example value
        if (!param.getExampleValues().isEmpty()
                && (param.getType() == null || !STRING.equals(param.getType().asString()))) {
            try {
                return com.github.javaparser.StaticJavaParser.parseExpression(param.getExampleValues().get(0));
            } catch (Exception e) {
                // If parsing fails (e.g. partial code), ignore
                return null;
            }
        }

        return null;
    }

    /**
     * Check if an expression could be a parameter value based on type and pattern.
     * Delegates to ParameterSpec's robust AST matching logic.
     */
    private boolean couldBeParameterValue(Expression expr, ParameterSpec param) {
        return expr.findCompilationUnit()
                .map(cu -> param.matches(expr, cu))
                .orElse(false);
    }

    /**
     * Substitute varying literals/expressions with parameter names in the extracted
     * method body.
     * This ensures that the extracted method uses parameters instead of hardcoded
     * values.
     */
    private Statement substituteParameters(Statement stmt, StatementSequence sequence,
            RefactoringRecommendation recommendation, Map<String, String> nameOverrides) {
        // Orchestrate substitution per parameter
        for (ParameterSpec param : recommendation.getSuggestedParameters()) {
            String paramName = resolveParamName(param, nameOverrides);

            // 1) Prefer exact location-based replacement to avoid accidental collisions
            if (tryLocationBasedReplace(stmt, param, paramName)) {
                continue; // done for this parameter
            }

            // 2) Fallback to value-based replacement using captured examples
            applyValueBasedReplace(stmt, sequence, param, paramName);
        }
        return stmt;
    }

    /**
     * Resolve the final parameter name, applying any collision-driven overrides.
     */
    private String resolveParamName(ParameterSpec param, Map<String, String> nameOverrides) {
        return nameOverrides.getOrDefault(param.getName(), param.getName());
    }

    /**
     * Try to replace a specific expression occurrence using the original source
     * location
     * recorded on the parameter. Returns true if a replacement was made.
     */
    private boolean tryLocationBasedReplace(Statement stmt, ParameterSpec param, String paramName) {
        Integer line = param.getStartLine();
        Integer col = param.getStartColumn();
        if (line == null || col == null) {
            return false;
        }
        for (Expression expr : stmt.findAll(Expression.class)) {
            if (expr.getRange().isEmpty())
                continue;
            var begin = expr.getRange().get().begin;
            if (begin.line == line && begin.column == col) {
                if (expr.getParentNode().isPresent()) {
                    expr.replace(new NameExpr(paramName));
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Apply value-based fallback substitution when location-based replacement is
     * not possible.
     * Relies on `extractActualValue` and `shouldReplaceExpression` to decide
     * replacements.
     */
    private void applyValueBasedReplace(Statement stmt, StatementSequence sequence, ParameterSpec param,
            String paramName) {
        if (param.getExampleValues().isEmpty()) {
            return;
        }
        Expression primaryValue = extractActualValue(sequence, param);
        if (primaryValue == null) {
            return;
        }
        for (Expression expr : stmt.findAll(Expression.class)) {
            if (shouldReplaceExpression(expr, primaryValue, param) && expr.getParentNode().isPresent()) {
                expr.replace(new NameExpr(paramName));
            }
        }
    }

    /**
     * Check if an expression should be replaced with a parameter.
     * Uses AST equality.
     */
    private boolean shouldReplaceExpression(Expression expr, Expression primaryValue, ParameterSpec param) {
        // Semantic equality check via AST
        if (expr.equals(primaryValue)) {
            // For variable names, ensure robustness by checking against known examples
            if (expr.isNameExpr()) {
                // We rely on string representation for this specific cross-validation
                return param.getExampleValues().contains(expr.toString());
            }
            return true;
        }
        return false;
    }

    // Helper-reuse: find an existing equivalent helper in the same class to avoid
    // duplicate methods
    private MethodDeclaration findEquivalentHelper(ClassOrInterfaceDeclaration containingClass,
            MethodDeclaration newHelper, MethodDeclaration excludedMethod) {
        boolean newIsStatic = newHelper.getModifiers().stream()
                .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
        String newReturnType = newHelper.getType().asString();
        List<String> newParamTypes = new java.util.ArrayList<>();
        for (Parameter p : newHelper.getParameters()) {
            newParamTypes.add(p.getType().asString());
        }
        String newBodyNorm = normalizeMethodBody(newHelper);

        for (MethodDeclaration candidate : containingClass.getMethods()) {
            // CRITICAL FIX: Never reuse the method we are currently extracting from!
            if (candidate == excludedMethod)
                continue;

            // CRITICAL FIX: Only reuse PRIVATE methods as helpers.
            // Reuse of public methods leads to infinite recursion if we are refactoring
            // that public method.
            if (!candidate.getModifiers().contains(Modifier.privateModifier()))
                continue;

            // Only consider private helpers (or same staticness and signature) to be
            // conservative
            boolean candIsStatic = candidate.getModifiers().stream()
                    .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
            if (candIsStatic != newIsStatic)
                continue;
            if (!candidate.getType().asString().equals(newReturnType))
                continue;
            if (candidate.getParameters().size() != newParamTypes.size())
                continue;

            boolean paramsMatch = true;
            for (int i = 0; i < candidate.getParameters().size(); i++) {
                String ct = candidate.getParameter(i).getType().asString();
                if (!ct.equals(newParamTypes.get(i))) {
                    paramsMatch = false;
                    break;
                }
            }
            if (!paramsMatch)
                continue;

            // Compare normalized bodies
            String candNorm = normalizeMethodBody(candidate);
            if (candNorm != null && candNorm.equals(newBodyNorm)) {
                return candidate; // Reuse this
            }
        }

        return null;
    }

    // Produce a canonical representation of the method body with parameter names
    // normalized (p0, p1, ...)
    @SuppressWarnings("deprecation")
    private String normalizeMethodBody(MethodDeclaration method) {
        if (method.getBody().isEmpty())
            return null;

        MethodDeclaration clone = method.clone();
        java.util.Map<String, String> renames = new java.util.HashMap<>();
        for (int i = 0; i < clone.getParameters().size(); i++) {
            renames.put(clone.getParameter(i).getNameAsString(), "p" + i);
        }

        // Apply visitor to rename variables securely
        new NormalizationVisitor().visit(clone, renames);

        // Canonical string representation without comments
        com.github.javaparser.printer.configuration.PrettyPrinterConfiguration config = new com.github.javaparser.printer.configuration.PrettyPrinterConfiguration();
        config.setPrintComments(false);

        // Strip whitespace to ignore formatting differences completely
        return clone.getBody().get().toString(config).replaceAll("\\s+", "");
    }

    /**
     * Result of a refactoring operation containing the modified source code.
     * Now supports multiple files being modified in a single refactoring operation.
     */
    public record RefactoringResult(
            Map<Path, String> modifiedFiles, // file path -> refactored code
            RefactoringStrategy strategy,
            String description) {

        /**
         * Convenience constructor for single-file refactorings.
         */
        public RefactoringResult(Path sourceFile, String refactoredCode,
                RefactoringStrategy strategy, String description) {
            this(Map.of(sourceFile, refactoredCode), strategy, description);
        }

        /**
         * Write all refactored files.
         */
        public void apply() throws IOException {
            for (Map.Entry<Path, String> entry : modifiedFiles.entrySet()) {
                Files.writeString(entry.getKey(), entry.getValue());
            }
        }
    }
}
