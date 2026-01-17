package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.raditha.dedup.analysis.DataFlowAnalyzer;

import com.raditha.dedup.model.*;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import org.slf4j.Logger;
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
    public static final String Boolean = "Boolean";

    private record HelperMethodResult(MethodDeclaration method, List<ParameterSpec> usedParameters,
            String forcedReturnVar) {
    }

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

        // 0. Pre-calculate unique sequences to analyze global properties (like live-out
        // vars)
        Set<StatementSequence> uniqueSequences = new java.util.LinkedHashSet<>();
        for (SimilarityPair pair : cluster.duplicates()) {
            uniqueSequences.add(pair.seq1());
            uniqueSequences.add(pair.seq2());
        }

        // 1. Create the new helper method (tentative)
        HelperMethodResult helperResult = createHelperMethod(primary, uniqueSequences, recommendation);
        if (helperResult == null) {
            return new RefactoringResult(Map.of(), recommendation.getStrategy(),
                    "Refactoring aborted: Multiple live-out variables detected " + uniqueSequences.size()
                            + " sequences analyzed");
        }
        MethodDeclaration helperMethod = helperResult.method();
        List<ParameterSpec> effectiveParams = helperResult.usedParameters();
        String forcedReturnVar = helperResult.forcedReturnVar();

        // 2. Add method to the class (modifies primary CU) — but first, try to REUSE
        // existing equivalent helper
        ClassOrInterfaceDeclaration containingClass = primary.containingMethod()
                .findAncestor(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalStateException("No containing class found"));

        Set<MethodDeclaration> excludedMethods = new HashSet<>();
        MethodDeclaration reusableExistingMethod = null;

        for (StatementSequence seq : uniqueSequences) {
            if (seq.containingMethod() != null) {
                excludedMethods.add(seq.containingMethod());
                
                // Check if this sequence covers the entire body of its containing method
                // AND if the method signature implies it can be reused (i.e., same parameters)
                if (isMethodBody(seq)) {
                     // Check parameter compatibility
                     // If the extracted logic requires parameters (effectiveParams), 
                     // but the existing method has DIFFERENT parameters, we can't reuse it 
                     // without changing its signature (which is risky/out of scope).
                     // Simple check: Parameter count. 
                     // TODO: rigorous type check.
                     if (seq.containingMethod().getParameters().size() == effectiveParams.size()) {
                         reusableExistingMethod = seq.containingMethod();
                     }
                }
            }
        }

        String methodNameToUse = recommendation.getSuggestedMethodName();
        
        if (reusableExistingMethod != null) {
            // We found a method in the cluster that creates the duplicate. Use it!
            methodNameToUse = reusableExistingMethod.getNameAsString();
        } else {
            MethodDeclaration equivalent = findEquivalentHelper(containingClass, helperMethod, excludedMethods);
            if (equivalent == null) {
                // No equivalent helper exists; add our newly created one
                containingClass.addMember(helperMethod);
            } else {
                // Reuse existing helper method name; skip adding duplicate
                methodNameToUse = equivalent.getNameAsString();
            }
        }

        // 3. Replace all duplicate occurrences with method calls (Two-Phase: Prepare
        // then Apply)
        // Phase 1: Prepare replacements. If any fails, we abort the whole cluster to
        // avoid partial refactoring.
        Map<StatementSequence, MethodCallExpr> preparedReplacements = new LinkedHashMap<>();

        // Phase 0: Precompute AST paths for parameters (while primary is intact)
        Map<ParameterSpec, ASTNodePath> precomputedPaths = precomputeParameterPaths(primary,
                effectiveParams);

        for (StatementSequence seq : uniqueSequences) {
            if (seq.containingMethod() != null && seq.containingMethod().equals(reusableExistingMethod)) {
                // This sequence IS the body of the helper method we are reusing.
                // Do NOT replace it with a call to itself (Recursion!).
                continue;
            }

            StatementSequence seqToRefactor = seq;
            int limit = recommendation.getValidStatementCount();
            if (limit != -1 && limit < seq.size()) {
                seqToRefactor = createTruncatedSequence(seq, limit);
            }

            MethodCallExpr call = prepareReplacement(seqToRefactor, recommendation.getVariationAnalysis(),
                    methodNameToUse, primary, precomputedPaths, effectiveParams);
            if (call == null) {
                // Remove the potentially added helper method to keep code clean?
                // Difficult to undo cleanly without transaction, but unused private method is
                // acceptable failure state.
                return new RefactoringResult(Map.of(), recommendation.getStrategy(),
                        "Refactoring aborted due to argument resolution failure");
            }
            preparedReplacements.put(seqToRefactor, call);
        }

        // Phase 2: Apply replacements (Execution)
        for (Map.Entry<StatementSequence, MethodCallExpr> entry : preparedReplacements.entrySet()) {
            StatementSequence seq = entry.getKey();
            MethodCallExpr call = entry.getValue();

            applyReplacement(seq, recommendation, call, forcedReturnVar, helperMethod.getType());
            modifiedCUs.put(seq.compilationUnit(), seq.sourceFilePath());
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
     * Precompute AST paths for parameters using the intact primary sequence.
     * This safeguards against AST detachment issues during repeated traversals.
     */
    private Map<ParameterSpec, ASTNodePath> precomputeParameterPaths(StatementSequence primary,
            List<ParameterSpec> params) {
        Map<ParameterSpec, ASTNodePath> paths = new LinkedHashMap<>();

        for (ParameterSpec param : params) {
            // Only structural params need paths (variationIndex != -1 usually, but check
            // all with coords)
            Expression node = findNodeByCoordinates(primary, param.getStartLine(), param.getStartColumn());
            if (node != null) {
                ASTNodePath path = computePath(primary, node);
                if (path != null) {
                    paths.put(param, path);
                }
            }
        }
        return paths;
    }

    private com.github.javaparser.ast.type.Type resolveVariableType(StatementSequence sequence, String varName) {
        for (Statement stmt : sequence.statements()) {
            // Check if it's an expression statement containing a variable declaration
            if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
                VariableDeclarationExpr vde = stmt.asExpressionStmt().getExpression().asVariableDeclarationExpr();
                for (com.github.javaparser.ast.body.VariableDeclarator v : vde.getVariables()) {
                    if (v.getNameAsString().equals(varName)) {
                        return v.getType();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Create the helper method from the primary sequence.
     * Simplified by delegating cohesive responsibilities to dedicated helpers.
     */
    private HelperMethodResult createHelperMethod(StatementSequence sequence, Set<StatementSequence> allSequences,
            RefactoringRecommendation recommendation) {

        // PHASE 2 FIX: Live-Out Variable Detection (Cluster-Wide)
        // Check ALL sequences in the cluster. If ANY has a live-out variable, we must
        // return it.
        DataFlowAnalyzer dfa = new DataFlowAnalyzer();
        Set<String> liveOutVars = getLiveOuts(allSequences, recommendation, dfa);

        String forcedReturnVar = null;
        com.github.javaparser.ast.type.Type forcedReturnType = null;

        if (liveOutVars.size() > 1) {
            return null; // Controlled abort
        } else if (liveOutVars.size() == 1) {
            forcedReturnVar = liveOutVars.iterator().next();
            forcedReturnType = resolveVariableType(sequence, forcedReturnVar);
        }
        // If no live-outs, don't force void - let the recommendation's return type be used
        // (it might have a return statement even without live-outs)

        MethodDeclaration method = initializeHelperMethod(recommendation);
        applyMethodModifiers(method, sequence);

        // DEBUG: Dump the generated helper method

        // Apply return type: Use forced type if available, otherwise recommendation
        if (forcedReturnType != null) {
            method.setType(forcedReturnType);
        } else {
            setReturnType(method, recommendation);
        }

        // CRITICAL FIX: Sort parameters to match ArgumentBuilder order
        // This ensures the method signature matches the argument list at call sites
        List<ParameterSpec> sortedParams = new ArrayList<>(recommendation.getSuggestedParameters());
        sortedParams.sort((p1, p2) -> {
            Integer idx1 = (p1.getVariationIndex() == null || p1.getVariationIndex() == -1)
                    ? Integer.MAX_VALUE
                    : p1.getVariationIndex();
            Integer idx2 = (p2.getVariationIndex() == null || p2.getVariationIndex() == -1)
                    ? Integer.MAX_VALUE
                    : p2.getVariationIndex();
            return Integer.compare(idx1, idx2);
        });

        // Parameters with collision handling
        Set<String> declaredVars = collectDeclaredVariableNames(sequence, recommendation);
        Map<ParameterSpec, String> paramNameOverrides = computeParamNameOverrides(declaredVars,
                sortedParams);

        // Determine target return variable for body building
        String effectiveTargetVar = (forcedReturnVar != null) ? forcedReturnVar
                : determineTargetReturnVar(sequence, method.getType().asString());

        // CRITICAL FIX: Filter out unused parameters!
        // The recommendation might include parameters that are variables in the scope
        // but not actually used
        // in the extracted sequence (e.g. if the sequence was truncated or analyzed
        // conservatively).
        // Including unused parameters changes the signature and prevents finding
        // equivalent helpers.
        BlockStmt tempBody = buildHelperMethodBody(sequence, recommendation,
                effectiveTargetVar,
                paramNameOverrides);

        List<ParameterSpec> usedParams = new ArrayList<>();
        for (ParameterSpec param : sortedParams) {
            String targetName = paramNameOverrides.getOrDefault(param, param.getName());

            // CRITICAL FIX: If the parameter corresponds to a variable DECLARED in the
            // sequence,
            // it is NOT an external parameter, it is an internal variable. Exclude it.
            if (declaredVars.contains(param.getName())) {
                continue;
            }

            // Check if targetName is used in the body
            boolean isUsed = tempBody.findAll(com.github.javaparser.ast.expr.SimpleName.class).stream()
                    .anyMatch(n -> n.getIdentifier().equals(targetName));
            if (isUsed) {
                usedParams.add(param);
            }
        }

        addParameters(method, usedParams, paramNameOverrides);

        // Copy thrown exceptions
        copyThrownExceptions(method, sequence);

        // Determine body (rebuild with final decision)
        BlockStmt body = buildHelperMethodBody(sequence, recommendation, effectiveTargetVar, paramNameOverrides);

        method.setBody(body);
        return new HelperMethodResult(method, usedParams, effectiveTargetVar);
    }

    private Set<String> getLiveOuts(Set<StatementSequence> allSequences, RefactoringRecommendation recommendation, DataFlowAnalyzer dfa) {
        Set<String> liveOutVars = new HashSet<>();

        int limit = recommendation.getValidStatementCount();

        for (StatementSequence seq : allSequences) {
            StatementSequence seqToAnalyze = seq;
            if (limit != -1 && limit < seq.size()) {
                seqToAnalyze = createTruncatedSequence(seq, limit);
            }
            Set<String> seqLiveOut = dfa.findLiveOutVariables(seqToAnalyze);
            liveOutVars.addAll(seqLiveOut);
        }
        return liveOutVars;
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

    private StatementSequence createTruncatedSequence(StatementSequence fullSequence, int count) {
        if (count >= fullSequence.statements().size()) {
            return fullSequence;
        }
        java.util.List<com.github.javaparser.ast.stmt.Statement> prefixStmts = fullSequence.statements().subList(0,
                count);

        // Calculate new range based on prefix statements
        com.raditha.dedup.model.Range fullRange = fullSequence.range();
        int endLine = prefixStmts.get(count - 1).getEnd().map(p -> p.line).orElse(fullRange.endLine());
        int endColumn = prefixStmts.get(count - 1).getEnd().map(p -> p.column).orElse(fullRange.endColumn());

        com.raditha.dedup.model.Range prefixRange = new com.raditha.dedup.model.Range(fullRange.startLine(),
                fullRange.startColumn(), endLine, endColumn);

        return new StatementSequence(
                prefixStmts,
                prefixRange,
                fullSequence.startOffset(),
                fullSequence.containingMethod(),
                fullSequence.compilationUnit(),
                fullSequence.sourceFilePath());
    }

    private void setReturnType(MethodDeclaration method, RefactoringRecommendation recommendation) {
        com.github.javaparser.ast.type.Type returnType = recommendation.getSuggestedReturnType();
        method.setType(returnType != null ? returnType : new com.github.javaparser.ast.type.VoidType());
    }

    private Set<String> collectDeclaredVariableNames(StatementSequence sequence,
            RefactoringRecommendation recommendation) {
        Set<String> declaredVars = new HashSet<>();
        int limit = getEffectiveLimit(sequence, recommendation);
        List<Statement> stmts = sequence.statements();
        for (int i = 0; i < limit; i++) {
            Statement stmt = stmts.get(i);
            stmt.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                    .forEach(v -> declaredVars.add(v.getNameAsString()));
        }
        return declaredVars;
    }

    private Map<ParameterSpec, String> computeParamNameOverrides(Set<String> declaredVars, List<ParameterSpec> params) {
        Map<ParameterSpec, String> overrides = new java.util.HashMap<>();
        Set<String> usedNames = new HashSet<>(declaredVars);

        for (ParameterSpec param : params) {
            String targetName = param.getName();

            if (usedNames.contains(targetName)) {
                String baseName = targetName;
                int counter = 2;
                while (usedNames.contains(baseName + counter)) {
                    counter++;
                }
                targetName = baseName + counter;
            }

            overrides.put(param, targetName);
            usedNames.add(targetName);
        }
        return overrides;
    }

    private void addParameters(MethodDeclaration method, List<ParameterSpec> params,
            Map<ParameterSpec, String> overrides) {
        for (ParameterSpec param : params) {
            String targetName = overrides.getOrDefault(param, param.getName());
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
            Map<ParameterSpec, String> paramNameOverrides) {
        BlockStmt body = new BlockStmt();

        boolean hasExternalVars = hasExternalVariablesInReturn(sequence);
        List<Statement> statements = buildBodyStatements(sequence, recommendation, targetReturnVar, paramNameOverrides,
                hasExternalVars);
        for (Statement s : statements) {
            body.addStatement(s);
        }

        // CRITICAL FIX: If targetReturnVar is null, the method is void (forced by literal exclusion)
        // Don't add any return statement
        if (targetReturnVar != null && needsFinalReturn(body, recommendation)) {
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
            Map<ParameterSpec, String> paramNameOverrides,
            boolean hasExternalVars) {
        List<Statement> result = new ArrayList<>();
        int limit = getEffectiveLimit(sequence, recommendation);
        List<Statement> stmts = sequence.statements();
        for (int i = 0; i < limit; i++) {
            Statement original = stmts.get(i);
            Statement stmt = substituteParameters(original.clone(), recommendation, paramNameOverrides);

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
    private boolean needsFinalReturn(BlockStmt body, RefactoringRecommendation recommendation) {
        // If return type is void, no return needed
        if ("void".equals(recommendation.getSuggestedReturnType().asString())) {
            return false;
        }
        // If body is empty or doesn't end with return, we need one
        return body.getStatements().isEmpty() || !body.getStatements().getLast().get().isReturnStmt();
    }

    /**
     * Find any declared variable in the method body as a fallback return value.
     * Returns the LAST variable declaration found (most likely to be the return
     * value).
     */
    private String findAnyDeclaredVariable(BlockStmt body) {
        String lastVar = null;
        for (Statement stmt : body.getStatements()) {
            if (stmt.isExpressionStmt()) {
                var expr = stmt.asExpressionStmt().getExpression();
                if (expr.isVariableDeclarationExpr()) {
                    var varDecl = expr.asVariableDeclarationExpr();
                    if (!varDecl.getVariables().isEmpty()) {
                        lastVar = varDecl.getVariables().get(0).getNameAsString();
                    }
                }
            }
        }
        return lastVar;
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

                // CRITICAL FIX: If the return expression is NOT a simple variable name (e.g.
                // it's a method call),
                // we treat it as having external dependencies/complexity that prevents direct
                // inlining.
                // This forces the refactorer to assign the result to a variable first.
                if (!returnExpr.isNameExpr()) {
                    return true;
                }

                // CRITICAL FIX: We allow complex return expressions (e.g. x + 1) as long as
                // they
                // don't reference external variables. The previous check overly aggressively
                // rejected anything that wasn't a simple NameExpr.

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
     * Phase 1: Prepare the method call expression by resolving arguments.
     * This reads the AST but does NOT modify it.
     * Returns null if argument resolution fails.
     */
    private MethodCallExpr prepareReplacement(StatementSequence sequence,
            VariationAnalysis variations, String methodNameToUse, StatementSequence primarySequence,
            Map<ParameterSpec, ASTNodePath> precomputedPaths, List<ParameterSpec> effectiveParams) {
        MethodDeclaration containingMethod = sequence.containingMethod();
        if (containingMethod == null || containingMethod.getBody().isEmpty()) {
            return null;
        }

        // 1) Build arguments using precomputed paths (avoiding AST traversal issues)
        NodeList<Expression> arguments = buildArgumentsForCall(variations, sequence, primarySequence,
                precomputedPaths, effectiveParams);
        if (arguments.size() != effectiveParams.size()) {
            return null; // Could not resolve args safely (mismatch indicates failure)
        }

        // Clone arguments to avoid detaching nodes from the AST (especially primary
        // since
        // it's used for lookup if needed)
        NodeList<Expression> clonedArgs = new NodeList<>();
        for (Expression arg : arguments) {
            clonedArgs.add(arg.clone());
        }

        // 2) Create the method call expression
        return new MethodCallExpr(methodNameToUse, clonedArgs.toArray(new Expression[0]));
    }

    /**
     * Phase 2: Apply the replacement to the AST.
     * This modifies the code structure.
     */
    private void applyReplacement(StatementSequence sequence, RefactoringRecommendation recommendation,
            MethodCallExpr methodCall, String forcedReturnVar, com.github.javaparser.ast.type.Type returnType) {
        MethodDeclaration containingMethod = sequence.containingMethod();
        if (containingMethod == null || containingMethod.getBody().isEmpty()) {
            return;
        }

        BlockStmt block = containingMethod.getBody().get();

        // 3) Remember any original return inside the duplicate sequence
        int limit = getEffectiveLimit(sequence, recommendation);
        ReturnStmt originalReturnValues = findOriginalReturnInSequence(sequence, limit);

        // 4) Locate sequence in the block and replace
        int startIdx = findStatementIndex(block, sequence);
        if (startIdx < 0)
            return;

        // Remove the old statements belonging to the sequence

        int removeCount = getEffectiveLimit(sequence, recommendation);
        for (int i = 0; i < removeCount && startIdx < block.getStatements().size(); i++) {
            block.getStatements().remove(startIdx);
        }

        // 5) Insert new statements depending on return type
        if (returnType != null && returnType.isVoidType()) {
            insertVoidReplacement(block, startIdx, methodCall, originalReturnValues);
            
            // CRITICAL FIX: Re-declare literal variables used after the sequence
            // When literals are not live-outs (not returned), but are used after,
            // we need to re-declare them in the caller
            redeclareLiteralVariables(sequence, block, startIdx + 1, null);
            return;
        }

        // For non-void: compute a good target var name and decide if we can inline
        // return
        String varName = (forcedReturnVar != null) ? forcedReturnVar : inferReturnVariable(sequence, recommendation);
        boolean nextIsReturn = startIdx < block.getStatements().size()
                && block.getStatements().get(startIdx).isReturnStmt();

        boolean returnHasExternalVars = hasExternalVariablesInReturn(sequence);

        if (returnHasExternalVars && varName == null) {
            // Ensure we go through the reconstruct path
            varName = firstDefinedVariable(sequence);
        }

        boolean shouldReturnDirectly = canInlineReturn(containingMethod, block, originalReturnValues,
                returnHasExternalVars, nextIsReturn);

        insertValueReplacement(block, startIdx, methodCall, originalReturnValues, varName,
                shouldReturnDirectly, nextIsReturn, returnType);
    }

    /**
     * Re-declare literal-initialized variables that are used after the sequence.
     * This fixes the issue where variables are defined in the helper but not returned
     * (they're not live-outs), yet the caller still references them.
     * 
     * @param returnedVarName The variable being returned by the helper (if any), which should NOT be re-declared
     */
    private void redeclareLiteralVariables(StatementSequence sequence, BlockStmt block, 
                                           int insertionPoint, String returnedVarName) {
        DataFlowAnalyzer dfa = new DataFlowAnalyzer();
        
        Set<String> literalVars = dfa.findLiteralInitializedVariables(sequence);
        Set<String> usedAfter = dfa.findVariablesUsedAfter(sequence);
        
        // Find literals that are used after
        Set<String> literalsToRedeclare = new HashSet<>(literalVars);
        literalsToRedeclare.retainAll(usedAfter);
        
        // CRITICAL: Don't re-declare the variable that's being returned by the helper
        if (returnedVarName != null) {
            literalsToRedeclare.remove(returnedVarName);
        }
        
        if (literalsToRedeclare.isEmpty()) {
            return;
        }
        
        // Insert variable declarations in deterministic order (sorted by name)
        List<String> sortedVars = new ArrayList<>(literalsToRedeclare);
        sortedVars.sort(String::compareTo);
        
        int currentIdx = insertionPoint;
        for (String varName : sortedVars) {
            VariableDeclarator originalDecl = findVariableDeclaratorInSequence(sequence, varName);
            if (originalDecl != null && originalDecl.getInitializer().isPresent()) {
                // Create: Type varName = literalValue;
                VariableDeclarationExpr redecl = new VariableDeclarationExpr(
                    originalDecl.getType().clone(),
                    varName
                );
                redecl.getVariable(0).setInitializer(originalDecl.getInitializer().get().clone());
                
                block.getStatements().add(currentIdx, new ExpressionStmt(redecl));
                currentIdx++;
            }
        }
    }

    /**
     * Find a variable declarator in the sequence by name.
     */
    private VariableDeclarator findVariableDeclaratorInSequence(StatementSequence sequence, String varName) {
        for (Statement stmt : sequence.statements()) {
            for (VariableDeclarator v : stmt.findAll(VariableDeclarator.class)) {
                if (v.getNameAsString().equals(varName)) {
                    return v;
                }
            }
        }
        return null;
    }

    private NodeList<Expression> buildArgumentsForCall(VariationAnalysis variations,
            StatementSequence sequence,
            StatementSequence primarySequence,
            Map<ParameterSpec, ASTNodePath> precomputedPaths,
            List<ParameterSpec> effectiveParams) {
        // Delegate to a focused builder that preserves existing behavior and guardrails
        return new ArgumentBuilder().buildArgs(variations, sequence, primarySequence, precomputedPaths,
                effectiveParams);
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
    /**
     * Check if the sequence covers the entire body of the containing method.
     */
    private boolean isMethodBody(StatementSequence seq) {
        MethodDeclaration method = seq.containingMethod();
        if (method == null || method.getBody().isEmpty()) {
            return false;
        }
        BlockStmt body = method.getBody().get();
        List<Statement> bodyStmts = body.getStatements();
        List<Statement> seqStmts = seq.statements();
        
        if (bodyStmts.size() != seqStmts.size()) {
            return false;
        }
        
        if (bodyStmts.isEmpty()) return true;
        
        return true; 
    }

    private class ArgumentBuilder {
        NodeList<Expression> buildArgs(VariationAnalysis variations,
                StatementSequence sequence,
                StatementSequence primarySequence,
                Map<ParameterSpec, ASTNodePath> precomputedPaths,
                List<ParameterSpec> effectiveParams) {
            NodeList<Expression> arguments = new NodeList<>();

            // Use the effective parameters which are already sorted and filtered
            List<ParameterSpec> sortedParams = effectiveParams;

            int argIndex = 0;
            for (ParameterSpec param : sortedParams) {

                Expression expr = resolveValue(sequence, param, primarySequence, precomputedPaths);
                if (expr == null) {
                    return new NodeList<>(); // cannot resolve safely
                }

                // Robust Type Checking using TypeWrapper
                TypeWrapper paramTypeWrapper = AbstractCompiler.findType(sequence.compilationUnit(), param.getType());

                if (paramTypeWrapper != null) {
                    TypeWrapper exprTypeWrapper = resolveExprTypeWrapper(expr, sequence);
                    if (exprTypeWrapper != null) {
                        if (!paramTypeWrapper.isAssignableFrom(exprTypeWrapper)) {
                            // Incompatible type: e.g., passing String to int parameter
                            return new NodeList<>();
                        }
                    } else {
                        // Fallback: If we can't resolve expression type (complex expression?),
                        // we might rely on existing weak checks or assume it's unsafe if it looks obviously wrong.
                        // For now, let's keep previous heuristic for literals if wrapper resolution failed
                        // (though AbstractCompiler should handle literals well).

                        if (isNumericType(param.getType()) && expr.isStringLiteralExpr()) {
                             return new NodeList<>();
                        }
                    }
                } else {
                    // Fallback to old heuristic if param type cannot be resolved
                    if (isNumericType(param.getType())) {
                        if (expr.isStringLiteralExpr()) {
                            return new NodeList<>();
                        }
                        // Also check if expression resolves to String type (for variables)
                        TypeWrapper exprTypeWrapper = resolveExprTypeWrapper(expr, sequence);
                        if (exprTypeWrapper != null &&
                            ("java.lang.String".equals(exprTypeWrapper.getName()) || "String".equals(exprTypeWrapper.getName()))) {
                            return new NodeList<>();
                        }
                    }
                }

                // Specific check for Class<?> types to ensure adaptations work
                if (isClassType(param.getType()) && !(expr.isClassExpr() ||
                        (expr.isFieldAccessExpr() && expr.asFieldAccessExpr().getNameAsString().equals("class")))) {
                    // Try to adapt NameExpr or other simple forms into ClassExpr
                    Expression adapted = null;
                    if (expr.isNameExpr()) {
                        adapted = new ClassExpr(new ClassOrInterfaceType(null, expr.asNameExpr().getNameAsString()));
                    }
                    if (adapted == null) {
                        return new NodeList<>(); // unsafe
                    }
                    expr = adapted;
                }

                arguments.add(expr);
                argIndex++;
            }

            return arguments;
        }

        private TypeWrapper resolveExprTypeWrapper(Expression expr, StatementSequence sequence) {
            if (expr.isLiteralExpr()) {
                com.github.javaparser.ast.type.Type litType = AbstractCompiler.convertLiteralToType(expr.asLiteralExpr());
                return AbstractCompiler.findType(sequence.compilationUnit(), litType);
            }
            if (expr.isNameExpr()) {
                String varName = expr.asNameExpr().getNameAsString();
                if (isLocalVariable(sequence, varName)) {
                     com.github.javaparser.ast.type.Type type = resolveVariableInMethod(sequence.containingMethod(), varName);
                     if (type != null) {
                         return AbstractCompiler.findType(sequence.compilationUnit(), type);
                     }
                }
                // Check local variables in sequence scope?
                // Use outer class method for resolving in sequence
                com.github.javaparser.ast.type.Type type = ExtractMethodRefactorer.this.resolveVariableType(sequence, varName);
                if (type != null) {
                     return AbstractCompiler.findType(sequence.compilationUnit(), type);
                }
            }
            // For other expressions, maybe we can assume they are compatible if we can't prove otherwise?
            // Or return null to indicate unknown type.
            return null;
        }

        private com.github.javaparser.ast.type.Type resolveVariableInMethod(MethodDeclaration method, String varName) {
             if (method == null || method.getBody().isEmpty()) return null;
             // Check parameters
             for (Parameter p : method.getParameters()) {
                 if (p.getNameAsString().equals(varName)) return p.getType();
             }
             // Check body
             for (Statement stmt : method.getBody().get().getStatements()) {
                  if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
                      for (com.github.javaparser.ast.body.VariableDeclarator v : stmt.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables()) {
                          if (v.getNameAsString().equals(varName)) return v.getType();
                      }
                  }
             }
             return null;
        }

        private boolean isNumericType(com.github.javaparser.ast.type.Type type) {
            if (type.isPrimitiveType()) return true;
             if (type.isClassOrInterfaceType()) {
                String name = type.asClassOrInterfaceType().getNameAsString();
                return sa.com.cloudsolutions.antikythera.evaluator.Reflect.INTEGER.equals(name) ||
                        sa.com.cloudsolutions.antikythera.evaluator.Reflect.LONG.equals(name) ||
                        sa.com.cloudsolutions.antikythera.evaluator.Reflect.DOUBLE.equals(name) ||
                        sa.com.cloudsolutions.antikythera.evaluator.Reflect.BOOLEAN.equals(name);
            }
            return false;
        }

        private boolean isClassType(com.github.javaparser.ast.type.Type type) {
            if (!type.isClassOrInterfaceType()) {
                return false;
            }
            String name = type.asClassOrInterfaceType().getNameAsString();
            return name.equals("Class") || name.startsWith("Class<");
        }


        /**
         * Extract the actual value using Structural Path logic.
         * Requires primarySequence to identify the node from coordinates.
         */
        private Expression extractActualValue(StatementSequence targetSequence, ParameterSpec param,
                                              StatementSequence primarySequence) {
            if (primarySequence == null)
                return null;

            // 1. Find the node in the primary sequence using coordinates
            Expression primaryNode = findNodeByCoordinates(primarySequence, param.getStartLine(), param.getStartColumn());
            if (primaryNode == null) {
                return null;
            }

            // 2. Compute path in primary
            ASTNodePath path = computePath(primarySequence, primaryNode);
            if (path == null) {
                return null;
            }

            // 3. Follow path in target
            return followPath(targetSequence, path);
        }

        private Expression resolveValue(StatementSequence sequence,
                ParameterSpec param,
                StatementSequence primarySequence,
                Map<ParameterSpec, ASTNodePath> precomputedPaths) {

            // Priority 0: Precomputed Structural Path (Robust against AST mutation)
            if (precomputedPaths != null && precomputedPaths.containsKey(param)) {
                ASTNodePath path = precomputedPaths.get(param);
                Expression found = ExtractMethodRefactorer.this.followPath(sequence, path);
                if (found != null) {
                    return found.clone();
                }
            }
            // If followPath fails (e.g. structure mismatch?), fall through to other
            // methods.
            // But typically structure logic is definitive.

            // Priority 1: Structural Extraction (Most robust) - Fallback
            // (Skipped if we are using precomputed paths, as logic is identical but slower)
            if (precomputedPaths == null) {
                Expression structuralExpr = extractActualValue(sequence, param, primarySequence);
                if (structuralExpr != null) {
                    return structuralExpr;
                }
            }

            // Priority 1.5: Invariant Fallback (for variationIndex == -1)
            // If structural path failed but it's an invariant, use the name.
            if (param.getVariationIndex() != null && param.getVariationIndex() == -1) {
                return new NameExpr(param.getName());
            }
            return null; // Fail if structural extraction cannot find it
        }

    }

    private ReturnStmt findOriginalReturnInSequence(StatementSequence sequence, int limit) {
        List<Statement> stmts = sequence.statements();
        for (int i = 0; i < limit; i++) {
            Statement stmt = stmts.get(i);
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
        int limit = getEffectiveLimit(sequence, recommendation);
        // Create a prefix sequence for analysis if needed
        List<Statement> stmts = sequence.statements();
        StatementSequence analyzeSeq = sequence;
        if (limit < stmts.size()) {
            analyzeSeq = new StatementSequence(
                    stmts.subList(0, limit),
                    new Range(sequence.range().startLine(), sequence.range().startColumn(),
                            stmts.get(limit - 1).getEnd().map(p -> p.line).orElse(sequence.range().endLine()),
                            stmts.get(limit - 1).getEnd().map(p -> p.column).orElse(sequence.range().endColumn())),
                    sequence.startOffset(), sequence.containingMethod(), sequence.compilationUnit(),
                    sequence.sourceFilePath());
        }

        DataFlowAnalyzer dfa = new DataFlowAnalyzer();
        Set<String> defined = dfa.findDefinedVariables(analyzeSeq);

        String varName = null;
        // PRIORITY: Trust the recommendation generator's analysis if applicable
        if (recommendation.getPrimaryReturnVariable() != null) {
            String primaryVar = recommendation.getPrimaryReturnVariable();
            if (defined.contains(primaryVar)) {
                varName = primaryVar;
            }
        }

        // Fallback or if primary var not found in this sequence (shouldn't happen for
        // clones)
        if (varName == null) {
            varName = findReturnVariable(analyzeSeq,
                    recommendation.getSuggestedReturnType() != null ? recommendation.getSuggestedReturnType().asString()
                            : "void");
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
                varName = typedCandidates.getFirst();
            }
        }
        return varName;
    }

    private String firstDefinedVariable(StatementSequence sequence) {
        DataFlowAnalyzer dfa2 = new DataFlowAnalyzer();
        Set<String> seqDefined = dfa2.findDefinedVariables(sequence);
        if (!seqDefined.isEmpty()) {
            return seqDefined.iterator().next();
        }
        return null;
    }

    private boolean canInlineReturn(MethodDeclaration containingMethod, BlockStmt block,
            ReturnStmt originalReturnValues, boolean returnHasExternalVars, boolean nextIsReturn) {
        if (returnHasExternalVars)
            return false;
        boolean methodIsVoid = containingMethod.getType().asString().equals("void");
        boolean blockEmptyAfterRemoval = block.getStatements().isEmpty();
        return ((nextIsReturn && originalReturnValues != null) ||
                (blockEmptyAfterRemoval && !methodIsVoid && originalReturnValues != null));
    }

    private void insertValueReplacement(BlockStmt block, int startIdx,
            MethodCallExpr methodCall, ReturnStmt originalReturnValues, String varName,
            boolean shouldReturnDirectly, boolean nextIsReturn, com.github.javaparser.ast.type.Type returnType) {

        if (varName != null && shouldReturnDirectly) {
            if (nextIsReturn) {
                block.getStatements().remove(startIdx);
            }
            block.getStatements().add(startIdx, new ReturnStmt(methodCall));
            return;
        }

        if (varName != null) {
            VariableDeclarationExpr varDecl = new VariableDeclarationExpr(
                    returnType.clone(), varName);
            varDecl.getVariable(0).setInitializer(methodCall);
            block.getStatements().add(startIdx, new ExpressionStmt(varDecl));
            if (originalReturnValues != null && originalReturnValues.getExpression().isPresent()) {
                block.getStatements().add(startIdx + 1, originalReturnValues.clone());
            }
            return;
        }

        if (originalReturnValues != null && originalReturnValues.getExpression().isPresent()) {
            block.getStatements().add(startIdx, new ReturnStmt(methodCall));
        } else {
            block.getStatements().add(startIdx, new ExpressionStmt(methodCall));
        }
    }

    /**
     * Represents a structural path to a node within a StatementSequence.
     * Path consist of:
     * 1. Index of the statement within the sequence.
     * 2. List of child indices to navigate from that statement to the target node.
     */
    private record ASTNodePath(int statementIndex, List<Integer> childPath) {
    }

    private ASTNodePath computePath(StatementSequence sequence, com.github.javaparser.ast.Node target) {
        // 1. Find which statement contains the target
        int stmtIdx = -1;
        Statement rootStmt = null;
        for (int i = 0; i < sequence.statements().size(); i++) {
            Statement s = sequence.statements().get(i);
            if (isAncestor(s, target)) {
                stmtIdx = i;
                rootStmt = s;
                break;
            }
        }

        if (stmtIdx == -1)
            return null;

        // 2. Compute path from rootStmt to target
        List<Integer> childPath = new ArrayList<>();
        com.github.javaparser.ast.Node current = target;
        while (current != rootStmt) {
            com.github.javaparser.ast.Node parent = current.getParentNode().orElse(null);
            if (parent == null)
                return null;

            int index = indexOfStructuralChild(parent, current);
            if (index == -1)
                return null;
            childPath.add(0, index); // Prepend
            current = parent;
        }

        return new ASTNodePath(stmtIdx, childPath);
    }

    private boolean isAncestor(com.github.javaparser.ast.Node ancestor, com.github.javaparser.ast.Node node) {
        if (ancestor == node)
            return true;
        return node.getParentNode().map(p -> isAncestor(ancestor, p)).orElse(false);
    }

    private int indexOfStructuralChild(com.github.javaparser.ast.Node parent, com.github.javaparser.ast.Node child) {
        // Filter children to ignore comments for stable structural navigation
        List<com.github.javaparser.ast.Node> children = parent.getChildNodes().stream()
                .filter(n -> !(n instanceof com.github.javaparser.ast.comments.Comment))
                .collect(java.util.stream.Collectors.toList());
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) == child)
                return i;
        }
        return -1;
    }

    private Expression followPath(StatementSequence sequence, ASTNodePath path) {
        if (path.statementIndex < 0 || path.statementIndex >= sequence.statements().size()) {
            return null;
        }
        com.github.javaparser.ast.Node current = sequence.statements().get(path.statementIndex);

        for (int idx : path.childPath) {
            List<com.github.javaparser.ast.Node> children = current.getChildNodes().stream()
                    .filter(n -> !(n instanceof com.github.javaparser.ast.comments.Comment))
                    .collect(java.util.stream.Collectors.toList());
            if (idx < 0 || idx >= children.size()) {
                return null;
            }
            current = children.get(idx);
        }

        if (current instanceof Expression expr) {
            return expr.clone();
        }
        return null;
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


    private Expression findNodeByCoordinates(StatementSequence sequence, Integer line, Integer column) {
        if (line == null || column == null)
            return null;

        for (Statement stmt : sequence.statements()) {
            for (Expression expr : stmt.findAll(Expression.class)) {
                if (expr.getRange().isPresent()) {
                    var begin = expr.getRange().get().begin;
                    if (begin.line == line && begin.column == column) {
                        return expr;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Substitute varying literals/expressions with parameter names in the extracted
     * method body.
     * This ensures that the extracted method uses parameters instead of hardcoded
     * values.
     */
    private Statement substituteParameters(Statement stmt,
            RefactoringRecommendation recommendation, Map<ParameterSpec, String> nameOverrides) {
        // Orchestrate substitution per parameter
        for (ParameterSpec param : recommendation.getSuggestedParameters()) {
            String paramName = resolveParamName(param, nameOverrides);

            // 1) Prefer exact location-based replacement to avoid accidental collisions
            if (tryLocationBasedReplace(stmt, param, paramName)) {
                continue; // done for this parameter
            }

            // 2) Value-based fallback: replace matching expressions that lack location
            // metadata
            if (!param.getExampleValues().isEmpty()) {
                String exampleValue = param.getExampleValues().get(0);
                for (Expression expr : stmt.findAll(Expression.class)) {
                    if (expr.toString().equals(exampleValue) && expr.getParentNode().isPresent()) {
                        expr.replace(new NameExpr(paramName));
                        break; // Replace first occurrence only to avoid over-substitution
                    }
                }
            }
        }
        return stmt;
    }

    /**
     * Resolve the final parameter name, applying any collision-driven overrides.
     */
    private String resolveParamName(ParameterSpec param, Map<ParameterSpec, String> nameOverrides) {
        return nameOverrides.getOrDefault(param, param.getName());
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
            if (begin.line == line && begin.column == col && expr.getParentNode().isPresent()) {
                expr.replace(new NameExpr(paramName));
                return true;
            }
        }
        return false;
    }

    // Helper-reuse: find an existing equivalent helper in the same class to avoid
    // duplicate methods
    private MethodDeclaration findEquivalentHelper(ClassOrInterfaceDeclaration containingClass,
            MethodDeclaration newHelper, Set<MethodDeclaration> excludedMethods) {
        boolean newIsStatic = newHelper.getModifiers().stream()
                .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
        String newReturnType = newHelper.getType().asString();
        List<String> newParamTypes = new java.util.ArrayList<>();
        for (Parameter p : newHelper.getParameters()) {
            newParamTypes.add(p.getType().asString());
        }
        String newBodyNorm = normalizeMethodBody(newHelper);

        for (MethodDeclaration candidate : containingClass.getMethods()) {
            // CRITICAL FIX: Never reuse ANY method that is part of the cluster being refactored!
            if (excludedMethods.contains(candidate))
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
            if (candidate.getBody().isPresent() && newHelper.getBody().isPresent()) {
                if (candidate.getBody().get().getStatements().size() != newHelper.getBody().get().getStatements().size()) {
                    continue;
                }
            }

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
     * Determine the number of statements to process.
     * Respects truncation limit if valid.
     */
    private int getEffectiveLimit(StatementSequence sequence, RefactoringRecommendation recommendation) {
        int limit = sequence.statements().size();
        if (recommendation.getValidStatementCount() > 0 && recommendation.getValidStatementCount() < limit) {
            limit = recommendation.getValidStatementCount();
        }
        return limit;
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
                Path file = entry.getKey();
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, entry.getValue());
            }
        }
    }
}
