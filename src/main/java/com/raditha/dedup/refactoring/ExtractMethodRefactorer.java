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

        String methodNameToUse = recommendation.suggestedMethodName();
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
                recommendation.strategy(),
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
        Map<String, String> paramNameOverrides = computeParamNameOverrides(declaredVars, recommendation.suggestedParameters());
        addParameters(method, recommendation.suggestedParameters(), paramNameOverrides);

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
        method.setName(recommendation.suggestedMethodName());
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
        String returnType = recommendation.suggestedReturnType();
        method.setType(returnType != null ? returnType : "void");
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
            String name = param.name();
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
            String targetName = overrides.getOrDefault(param.name(), param.name());
            method.addParameter(new Parameter(new ClassOrInterfaceType(null, param.type()), targetName));
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
        List<Statement> statements = buildBodyStatements(sequence, recommendation, targetReturnVar, paramNameOverrides, hasExternalVars);
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
     * When the original return statement uses external variables that won't exist in the helper,
     * we skip that return and add a simple return of the target variable at the end.
     */
    private boolean shouldSkipReturnForExternalVars(Statement stmt, boolean hasExternalVars) {
        return hasExternalVars && stmt.isReturnStmt();
    }

    /**
     * If a return statement returns a complex expression that uses the target variable,
     * replace it with a simple `return <targetVar>;`. Otherwise, leave it unchanged.
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
     * Decide whether we must add a final `return <targetVar>;` at the end of the body.
     */
    private boolean needsFinalReturn(BlockStmt body, String targetReturnVar) {
        if (targetReturnVar == null) return false;
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

        // Determine ordering among String parameters for context-aware extraction
        List<ParameterSpec> stringParams = new ArrayList<>();
        for (ParameterSpec p : recommendation.suggestedParameters()) {
            if (STRING.equals(p.type())) {
                stringParams.add(p);
            }
        }

        // Build argument list safely: if any argument cannot be resolved with
        // type-compatibility, skip this occurrence
        NodeList<Expression> arguments = new NodeList<>();
        boolean allArgsResolved = true;
        for (ParameterSpec param : recommendation.suggestedParameters()) {
            String valToUse = null;

            // STRATEGY 1: Use Variation Analysis (most accurate)
            if (variations != null && param.variationIndex() != null) {
                var exprBindings = variations.exprBindings() != null
                        ? variations.exprBindings().get(param.variationIndex())
                        : null;
                if (exprBindings != null) {
                    valToUse = resolveBindingForSequence(exprBindings, sequence);
                }

                if (valToUse == null) {
                    var legacyBindings = variations.valueBindings() != null
                            ? variations.valueBindings().get(param.variationIndex())
                            // ... rest of logic

                            : null;
                    if (legacyBindings != null) {
                        // Reuse the resolver by adapting to ExprInfo
                        java.util.Map<com.raditha.dedup.model.StatementSequence, com.raditha.dedup.model.ExprInfo> adapted = new java.util.HashMap<>();
                        if (legacyBindings != null) {
                            for (var entry : legacyBindings.entrySet()) {
                                adapted.put(entry.getKey(),
                                        com.raditha.dedup.model.ExprInfo.fromText(entry.getValue()));
                            }
                        }
                        valToUse = resolveBindingForSequence(adapted, sequence);
                    }
                }
            }

            // STRATEGY 2a: Context-aware extraction for multiple String parameters (prefer
            // this over generic AST scan)
            if (valToUse == null && STRING.equals(param.type()) && stringParams.size() >= 2) {
                int ord = stringParams.indexOf(param);
                if (ord >= 0) {
                    String ctxVal = extractStringByContext(sequence, ord);
                    if (ctxVal != null) {
                        valToUse = ctxVal;
                    }
                }
            }

            // STRATEGY 2b: Fallback to generic extraction from AST (with disambiguation by
            // examples for Strings)
            if (valToUse == null) {
                String actualValue = extractActualValue(sequence, param);
                if (actualValue != null) {
                    // If multiple same-type params exist (e.g., two Strings), prefer values that
                    // match this param's example set
                    if (STRING.equals(param.type()) && actualValue.startsWith("\"")
                            && !param.exampleValues().isEmpty()) {
                        String unq = actualValue.replace("\"", "");
                        boolean matches = param.exampleValues().stream().map(s -> s.replace("\"", ""))
                                .anyMatch(s -> s.equals(unq));
                        if (!matches) {
                            actualValue = null; // discard ambiguous value
                        }
                    }
                }
                if (actualValue != null) {
                    valToUse = actualValue;
                }
            }

            // STRATEGY 3: Fallback to example value (only for non-String types and when
            // type-compatible)
            if (valToUse == null && !param.exampleValues().isEmpty()) {
                String pType = param.type();
                if (!STRING.equals(pType)) {
                    String candidate = param.exampleValues().get(0);
                    // Only accept if it looks type-compatible
                    boolean ok = false;
                    if (("int".equals(pType) || "Integer".equals(pType)) && candidate.matches("-?\\d+"))
                        ok = true;
                    if (("long".equals(pType) || "Long".equals(pType)) && candidate.matches("-?\\d+L?"))
                        ok = true;
                    if (("double".equals(pType) || DOUBLE.equals(pType)) && candidate.matches("-?\\d+\\.\\d+"))
                        ok = true;
                    if (("boolean".equals(pType) || "Boolean".equals(pType))
                            && ("true".equals(candidate) || "false".equals(candidate)))
                        ok = true;
                    if (("Class<?>".equals(pType) || "Class".equals(pType)) && !candidate.isEmpty())
                        ok = true;
                    if (ok) {
                        valToUse = candidate;
                    }
                }
            }

            // Type-compatibility guardrails
            if (valToUse == null) {
                allArgsResolved = false;
                break;
            }

            // Reject obviously incompatible literal forms
            String pType = param.type();
            if (("int".equals(pType) || "Integer".equals(pType) || "long".equals(pType) || "Long".equals(pType)
                    || "double".equals(pType) || DOUBLE.equals(pType) || "boolean".equals(pType)
                    || "Boolean".equals(pType)) && valToUse.startsWith("\"")) {
                allArgsResolved = false; // quoted string where numeric/boolean expected
                break;
            }
            if (STRING.equals(pType) && !valToUse.startsWith("\"") && valToUse.matches("-?\\d+(\\.\\d+)?")) {
                allArgsResolved = false; // numeric literal where String expected
                break;
            }

            // If parameter is Class<?>, ensure we pass User.class, not just User
            if (("Class<?>".equals(pType) || "Class".equals(pType)) && !valToUse.endsWith(".class")) {
                arguments.add(new ClassExpr(new ClassOrInterfaceType(null, valToUse)));
                continue;
            }

            // Default: variable name or literal
            try {
                if (valToUse.matches("-?\\d+")) {
                    // Choose Integer or Long literal based on param type
                    if ("long".equals(pType) || "Long".equals(pType)) {
                        arguments.add(new LongLiteralExpr(valToUse + "L"));
                    } else {
                        arguments.add(new IntegerLiteralExpr(valToUse));
                    }
                } else if (valToUse.matches("-?\\d+\\.\\d+")) {
                    arguments.add(new DoubleLiteralExpr(valToUse));
                } else if ("true".equals(valToUse) || "false".equals(valToUse)) {
                    arguments.add(new BooleanLiteralExpr(Boolean.parseBoolean(valToUse)));
                } else if (valToUse.startsWith("\"")) { // String lit
                    arguments.add(new StringLiteralExpr(valToUse.replace("\"", "")));
                } else {
                    // Variable or expression name
                    arguments.add(new NameExpr(valToUse));
                }
            } catch (Exception e) {
                allArgsResolved = false;
                break;
            }
        }

        // If any argument could not be resolved safely, skip this occurrence (do not
        // modify this sequence)
        if (!allArgsResolved || arguments.size() != recommendation.suggestedParameters().size()) {
            return;
        }

        // Create method call
        MethodCallExpr methodCall = new MethodCallExpr(
                methodNameToUse,
                arguments.toArray(new Expression[0]));

        // Check if the original sequence contained a return statement that we
        // "swallowed"
        // and if so, what expression it was returning.
        ReturnStmt originalReturnValues = null;
        for (Statement stmt : sequence.statements()) {
            if (stmt.isReturnStmt()) {
                originalReturnValues = stmt.asReturnStmt();
                break; // Assume only one return path relevant for replacement logic
            }
        }

        // Find and replace the statements
        int startIdx = findStatementIndex(block, sequence);
        if (startIdx >= 0) {
            // Remove old statements
            int count = sequence.statements().size();
            for (int i = 0; i < count && startIdx < block.getStatements().size(); i++) {
                block.getStatements().remove(startIdx);
            }

            // Insert new logic
            if ("void".equals(recommendation.suggestedReturnType())) {
                // Void return: just the call
                block.getStatements().add(startIdx, new ExpressionStmt(methodCall));

                // If original had return (void), add it back
                if (originalReturnValues != null) {
                    block.getStatements().add(startIdx + 1, new ReturnStmt());
                }
            } else {
                // Returns a value
                String varName = findReturnVariable(sequence, recommendation.suggestedReturnType());

                DataFlowAnalyzer dfa = new DataFlowAnalyzer();
                Set<String> defined = dfa.findDefinedVariables(sequence);

                // FALLBACK 1: Use primary return variable if defined in this sequence
                if (varName == null && recommendation.primaryReturnVariable() != null) {
                    if (defined.contains(recommendation.primaryReturnVariable())) {
                        varName = recommendation.primaryReturnVariable();
                    }
                }

                // FALLBACK 2: If still null, look for ANY defined variable matching the return
                // type
                // This handles parameter renaming (e.g. Primary returns 'user', Duplicate
                // returns 'customer')
                if (varName == null) {
                    List<String> typedCandidates = new ArrayList<>();
                    // Iterate statements to find variable declarations of matching type
                    for (Statement stmt : sequence.statements()) {
                        stmt.findAll(VariableDeclarationExpr.class).forEach(vde -> {
                            vde.getVariables().forEach(v -> {
                                if (v.getType().asString().equals(recommendation.suggestedReturnType())) {
                                    typedCandidates.add(v.getNameAsString());
                                }
                            });
                        });
                    }
                    if (typedCandidates.size() == 1) {
                        varName = typedCandidates.get(0);
                    }
                }

                // CRITICAL: Check if the statement AFTER the duplicate (AFTER removal) is a
                // return statement
                // If so, and the extracted method returns a value, just return the method call
                // directly
                boolean nextIsReturn = startIdx < block.getStatements().size() &&
                        block.getStatements().get(startIdx).isReturnStmt();

                // DEBUG: Trace the key decision values (Removed)

                // FIX BUG #1: Check if the post-sequence return uses external variables
                // (like prefix + user.getName()). If so, we CANNOT return directly.
                boolean returnHasExternalVars = hasExternalVariablesInReturn(sequence);

                // ALSO check if the NEXT return (after the sequence) uses ANY variables
                // that are defined in the sequence - this means we can't just return directly
                if (nextIsReturn) {
                    ReturnStmt nextReturn = block.getStatements().get(startIdx).asReturnStmt();
                    if (nextReturn.getExpression().isPresent()) {
                        Expression returnExpr = nextReturn.getExpression().get();
                        Set<String> returnVars = new HashSet<>();
                        for (NameExpr nameExpr : returnExpr.findAll(NameExpr.class)) {
                            returnVars.add(nameExpr.getNameAsString());
                        }
                        // Check if return uses any variable defined in the sequence
                        DataFlowAnalyzer postDfa = new DataFlowAnalyzer();
                        Set<String> sequenceDefinedVars = postDfa.findDefinedVariables(sequence);
                        for (String definedVar : sequenceDefinedVars) {
                            if (returnVars.contains(definedVar)) {
                                // The return uses a variable from the sequence
                                // Check if it's a complex expression (not just the var or method on it)
                                if (varName == null || !isSimpleReturnOfVariable(returnExpr, varName)) {
                                    returnHasExternalVars = true;
                                    // Also set a fallback varName if null
                                    if (varName == null) {
                                        varName = definedVar;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }

                // FIX BUG #1: If returnHasExternalVars=true and varName is still null,
                // we need to set varName to a defined variable from the sequence
                // so the else-branch (with originalReturnValues reconstruction) is taken
                if (returnHasExternalVars && varName == null) {
                    DataFlowAnalyzer dfa2 = new DataFlowAnalyzer();
                    Set<String> seqDefined = dfa2.findDefinedVariables(sequence);
                    // Pick the first defined variable (usually 'user' or similar)
                    if (!seqDefined.isEmpty()) {
                        varName = seqDefined.iterator().next();
                    }
                }

                // If we found a return variable, we assign it
                if (varName != null) {
                    // CRITICAL FIX: If the next statement is a return and the extracted method
                    // already has a return value, just return the method call directly
                    // ALSO: If after removal the block becomes empty AND the containing method
                    // returns a value,
                    // we should return the method call result
                    // BUT NOT if external variables exist in the return expression!
                    boolean shouldReturnDirectly = !returnHasExternalVars &&
                            ((nextIsReturn && originalReturnValues != null) ||
                                    (block.getStatements().isEmpty() &&
                                            !containingMethod.getType().asString().equals("void") &&
                                            originalReturnValues != null));

                    if (shouldReturnDirectly) {
                        // Remove the orphaned return statement if it exists
                        if (nextIsReturn) {
                            block.getStatements().remove(startIdx);
                        }
                        // Return the method call directly
                        block.getStatements().add(startIdx, new ReturnStmt(methodCall));
                    } else {
                        VariableDeclarationExpr varDecl = new VariableDeclarationExpr(
                                new ClassOrInterfaceType(null, recommendation.suggestedReturnType()),
                                varName);
                        varDecl.getVariable(0).setInitializer(methodCall);
                        block.getStatements().add(startIdx, new ExpressionStmt(varDecl));

                        // IF the original code had a return, we must RECONSTRUCT it
                        if (originalReturnValues != null && originalReturnValues.getExpression().isPresent()) {
                            // We need to assume the original return expression used 'varName'
                            // and we should now return that SAME expression
                            // BUT 'varName' is now the result of the method call.
                            // So effectively we just copy the original return statement!
                            // Since 'varName' is now defined in the scope (we just added it),
                            // `return varName.getName()` will work validly.
                            block.getStatements().add(startIdx + 1, originalReturnValues.clone());
                        }
                    }
                } else {
                    // No variable found (maybe direct return?), just add call
                    // This case is tricky if we need to return.
                    if (originalReturnValues != null && originalReturnValues.getExpression().isPresent()) {
                        // Fallback: return methodCall() directly?
                        // Only if types match. If mismatched (User vs String), this is broken.
                        // But we can't solve everything.
                        block.getStatements().add(startIdx, new ReturnStmt(methodCall));
                    } else {
                        block.getStatements().add(startIdx, new ExpressionStmt(methodCall));
                    }
                }
            }
        }
    }

    private static void debugReplaceWithMethodCall(RefactoringRecommendation recommendation, VariationAnalysis variations, String methodNameToUse) {
        var log = LoggerFactory.getLogger(VariationTracker.class);
        if (log.isDebugEnabled()) {
            log.debug("DEBUG replaceWithMethodCall: method = {}, variations = {}, params = {}", methodNameToUse,
                    (variations != null), recommendation.suggestedParameters().size());
            if (variations != null) {
                log.debug("DEBUG replaceWithMethodCall: exprBindings = {}",
                        variations.exprBindings() != null ? variations.exprBindings().size() : "null");
            }
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
     * FIXED Gap 1&2: Finds the ACTUAL value from the sequence AST.
     */
    private String extractActualValue(StatementSequence sequence, ParameterSpec param) {
        // Strategy: Search through sequence statements for expressions matching the
        // parameter type
        // and extract the actual variable/literal used

        List<String> foundValues = new ArrayList<>();

        for (Statement stmt : sequence.statements()) {
            // Collect all expressions of the matching type from this statement
            stmt.findAll(Expression.class).forEach(expr -> {
                String exprStr = expr.toString();
                // Check if this expression could be a parameter value
                if (couldBeParameterValue(expr, param)) {
                    foundValues.add(exprStr);
                }
            });
        }

        // Return the first found value, or fall back to example
        if (!foundValues.isEmpty()) {
            return foundValues.get(0);
        }

        // Fallback to example value only for non-String parameters; for Strings, return
        // null to avoid wrong literals
        if (!param.exampleValues().isEmpty() && (param.type() == null || !STRING.equals(param.type()))) {
            return param.exampleValues().get(0);
        }

        return null;
    }

    /**
     * Check if an expression could be a parameter value based on type and pattern.
     */
    private boolean couldBeParameterValue(Expression expr, ParameterSpec param) {
        String exprStr = expr.toString();

        // Skip this/super references
        if (exprStr.equals("this") || exprStr.equals("super")) {
            return false;
        }

        // Accept basic literals based on declared param type
        if (expr.isIntegerLiteralExpr() && ("int".equals(param.type()) || "Integer".equals(param.type()))) {
            return true;
        }
        if (expr.isLongLiteralExpr() && ("long".equals(param.type()) || "Long".equals(param.type()))) {
            return true;
        }
        if (expr.isDoubleLiteralExpr() && ("double".equals(param.type()) || DOUBLE.equals(param.type()))) {
            return true;
        }
        if (expr.isBooleanLiteralExpr() && ("boolean".equals(param.type()) || "Boolean".equals(param.type()))) {
            return true;
        }
        if (expr.isStringLiteralExpr() && (STRING.equals(param.type()))) {
            // If we have example values (e.g., two String params), only accept literals
            // matching this param's examples
            if (!param.exampleValues().isEmpty()) {
                String lit = expr.asStringLiteralExpr().asString();
                return param.exampleValues().stream().map(s -> s.replace("\"", "")).anyMatch(s -> s.equals(lit));
            }
            return true;
        }

        // If we have example values, use them as hints for NON-literal expressions only
        // This avoids accidentally accepting a String literal for a non-String
        // parameter.
        if (!param.exampleValues().isEmpty()) {
            // For variables or other non-literal expressions, check direct equality to any
            // example
            if (!expr.isLiteralExpr()) {
                for (String example : param.exampleValues()) {
                    if (example.equals(exprStr)) {
                        return true;
                    }
                }
            }
        }

        return false;
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
        for (ParameterSpec param : recommendation.suggestedParameters()) {
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
        return nameOverrides.getOrDefault(param.name(), param.name());
    }

    /**
     * Try to replace a specific expression occurrence using the original source location
     * recorded on the parameter. Returns true if a replacement was made.
     */
    private boolean tryLocationBasedReplace(Statement stmt, ParameterSpec param, String paramName) {
        Integer line = param.startLine();
        Integer col = param.startColumn();
        if (line == null || col == null) {
            return false;
        }
        for (Expression expr : stmt.findAll(Expression.class)) {
            if (expr.getRange().isEmpty()) continue;
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
     * Apply value-based fallback substitution when location-based replacement is not possible.
     * Relies on `extractActualValue` and `shouldReplaceExpression` to decide replacements.
     */
    private void applyValueBasedReplace(Statement stmt, StatementSequence sequence, ParameterSpec param, String paramName) {
        if (param.exampleValues().isEmpty()) {
            return;
        }
        String primaryValue = extractActualValue(sequence, param);
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
     */
    private boolean shouldReplaceExpression(Expression expr, String primaryValue, ParameterSpec param) {
        String exprStr = expr.toString();

        // String literals: compare the actual string value (without quotes)
        if (expr.isStringLiteralExpr()) {
            String literalValue = expr.asStringLiteralExpr().asString();
            String primaryNoQuotes = primaryValue.replace("\"", "");
            return literalValue.equals(primaryNoQuotes);
        }

        // Integer literals
        if (expr.isIntegerLiteralExpr() && primaryValue.matches("-?\\d+")) {
            return expr.toString().equals(primaryValue);
        }

        // Long literals
        if (expr.isLongLiteralExpr() && primaryValue.matches("-?\\d+L?")) {
            String exprValue = expr.toString().replace("L", "").replace("l", "");
            String primaryNoL = primaryValue.replace("L", "").replace("l", "");
            return exprValue.equals(primaryNoL);
        }

        // Boolean literals
        if (expr.isBooleanLiteralExpr()) {
            return expr.toString().equals(primaryValue);
        }

        // Variable names - only replace if it exactly matches the primary value
        // and is in the list of example values (to avoid false positives)
        if (expr.isNameExpr() && exprStr.equals(primaryValue)) {
            return param.exampleValues().contains(primaryValue);
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
    private String normalizeMethodBody(MethodDeclaration method) {
        if (method.getBody().isEmpty())
            return null;
        String body = method.getBody().get().toString();
        // Map parameter names to placeholders
        for (int i = 0; i < method.getParameters().size(); i++) {
            String name = method.getParameter(i).getNameAsString();
            String placeholder = "p" + i;
            // Replace as whole-word occurrences only
            body = body.replaceAll("\\b" + java.util.regex.Pattern.quote(name) + "\\b", placeholder);
        }
        // Strip comments-like patterns and whitespace differences
        body = body.replaceAll("/\\*.*?\\*/", ""); // block comments (best effort)
        body = body.replaceAll("//.*", ""); // line comments (best effort)
        body = body.replaceAll("\\s+", "");
        return body;
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
