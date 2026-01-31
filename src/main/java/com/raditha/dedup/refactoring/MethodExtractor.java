package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.TypeDeclaration;
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
public class MethodExtractor extends AbstractExtractor {
    private static final Logger logger = LoggerFactory.getLogger(MethodExtractor.class);

    private Map<ParameterSpec, ASTNodePath> precomputedPaths;
    private List<ParameterSpec> effectiveParams;
    private Map<ParameterSpec, String> paramNameOverrides;
    private HelperMethodResult helperResult;
    private String methodNameToUse;

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
        // Initialize instance fields
        this.cluster = cluster;
        this.recommendation = recommendation;

        // 1. Create the new helper method (tentative)
        helperResult = createHelperMethod();
        if (helperResult == null) {
            return new RefactoringResult(Map.of(), recommendation.getStrategy(),
                    "Refactoring aborted: Multiple live-out variables detected " + cluster.allSequences().size()
                            + " sequences analyzed");
        }

        // 2. Identify reuse target or use the new helper
        MethodDeclaration helperMethod = helperResult.method();
        methodNameToUse = findReusableMethod();

        // Add new helper if no reuse target was found
        if (methodNameToUse.equals(recommendation.getSuggestedMethodName())) {
            MethodDeclaration containingMethod = cluster.primary().containingMethod();
            if (containingMethod == null) {
                throw new IllegalStateException("No containing method found for primary sequence");
            }
            
            // FIXED: If the containingMethod is detached from the AST (e.g., after previous 
            // refactorings modified the file), re-resolve it from the live CompilationUnit
            if (containingMethod.getParentNode().isEmpty()) {
                String methodName = containingMethod.getNameAsString();
                logger.debug("Refreshing detached containingMethod: {}", methodName);
                
                // Look up the method in the current CompilationUnit
                CompilationUnit cu = cluster.primary().compilationUnit();
                if (cu != null) {
                    containingMethod = cu.findAll(MethodDeclaration.class).stream()
                            .filter(m -> m.getNameAsString().equals(methodName))
                            .findFirst()
                            .orElse(null);
                }
                
                if (containingMethod == null || containingMethod.getParentNode().isEmpty()) {
                    // Method was already refactored by a previous cluster (e.g., merged into parameterized test)
                    // This is expected behavior, not an error - skip gracefully
                    return new RefactoringResult(Map.of(), recommendation.getStrategy(),
                            "Skipped: method '" + methodName + "' was already refactored by a previous cluster");
                }
            }
            
            TypeDeclaration<?> containingType = containingMethod
                    .findAncestor(TypeDeclaration.class)
                    .orElseThrow(() -> new IllegalStateException("No containing type found"));

            MethodDeclaration equivalent = findEquivalentHelper(containingType, helperMethod,
                    cluster.getContainingMethods());
            if (equivalent == null) {
                containingType.addMember(helperMethod);
            } else {
                methodNameToUse = equivalent.getNameAsString();
            }
        }

        // 3. Execute the refactoring (Two-Phase: Prepare then Apply)
        return executeReplacements();
    }

    /**
     * Identify if any existing method in the cluster can be reused as the refactoring target.
     */
    private String findReusableMethod() {
        MethodDeclaration helperMethod = helperResult.method();
        effectiveParams = helperResult.usedParameters();

        for (StatementSequence seq : cluster.allSequences()) {
            MethodDeclaration m = seq.containingMethod();
            if (m != null && isMethodBody(seq)) {
                 if (m.getParameters().size() == effectiveParams.size() &&
                         m.getType().equals(helperMethod.getType())) {

                     String candNorm = normalizeMethodBody(m);
                     String helperNorm = normalizeMethodBody(helperMethod);
                     if (candNorm != null && candNorm.equals(helperNorm)) {

                         return m.getNameAsString();
                     }
                 }
            }
        }
        return recommendation.getSuggestedMethodName();
    }

    /**
     * Executes the replacement of duplicates with method calls across all affected files.
     */
    private RefactoringResult executeReplacements() {
        MethodDeclaration helperMethod = helperResult.method();
        String forcedReturnVar = helperResult.forcedReturnVar();

        Map<StatementSequence, MethodCallExpr> preparedReplacements = new LinkedHashMap<>();
        precomputedPaths = precomputeParameterPaths();

        // Phase 1: Prepare
        for (StatementSequence seq : cluster.allSequences()) {
            if (seq.containingMethod() != null
                    && seq.containingMethod().getNameAsString().equals(methodNameToUse)
                    && isMethodBody(seq)
            ) {
                // Potential recursion check: skip if we are reusing THIS method
                continue;
            }

            int limit = recommendation.getValidStatementCount();
            StatementSequence seqToRefactor = (limit != -1 && limit < seq.size()) ? createTruncatedSequence(seq, limit) : seq;

            MethodCallExpr call = prepareReplacement(seqToRefactor);
            if (call == null) {
                if (methodNameToUse.equals(recommendation.getSuggestedMethodName())) {
                    helperMethod.remove();
                }
                return new RefactoringResult(Map.of(), recommendation.getStrategy(),
                        "Refactoring aborted due to argument resolution failure");
            }
            preparedReplacements.put(seqToRefactor, call);
        }

        // Phase 2: Apply
        Map<CompilationUnit, Path> modifiedCUs = new LinkedHashMap<>();
        modifiedCUs.put(cluster.primary().compilationUnit(), cluster.primary().sourceFilePath());

        int successfulReplacements = 0;

        for (Map.Entry<StatementSequence, MethodCallExpr> entry : preparedReplacements.entrySet()) {
            StatementSequence seq = entry.getKey();
            boolean applied = applyReplacement(seq, entry.getValue(), forcedReturnVar, helperMethod.getType());
            if (applied) {
                successfulReplacements++;
                modifiedCUs.put(seq.compilationUnit(), seq.sourceFilePath());
            }
        }

        // ROLLBACK IF NO REPLACEMENTS
        if (successfulReplacements == 0) {
            // If the helper method was newly created (not reused), remove it
            if (methodNameToUse.equals(recommendation.getSuggestedMethodName())) {
                helperMethod.remove();
            }
            return new RefactoringResult(Map.of(), recommendation.getStrategy(),
                    "Skipped: Could not substitute calls for extracted method " + methodNameToUse);
        }

        // Ensure helper exists in every containing class for cross-file clusters
        ensureHelperInContainingTypes(helperMethod, modifiedCUs);

        return buildRefactoringResult(modifiedCUs);
    }

    private void ensureHelperInContainingTypes(MethodDeclaration helperMethod,
            Map<CompilationUnit, Path> modifiedCUs) {
        if (!isCrossFileCluster()) {
            return;
        }

        for (StatementSequence seq : cluster.allSequences()) {
            MethodDeclaration containingMethod = seq.containingMethod();
            if (containingMethod == null) {
                continue;
            }
            TypeDeclaration<?> containingType = containingMethod.findAncestor(TypeDeclaration.class)
                    .orElse(null);
            if (containingType == null) {
                continue;
            }

            boolean alreadyInType = containingType.getMethodsByName(methodNameToUse).stream()
                    .anyMatch(m -> m.getParameters().size() == helperMethod.getParameters().size()
                            && m.getType().asString().equals(helperMethod.getType().asString()));

            if (alreadyInType) {
                MethodDeclaration equivalent = findEquivalentHelper(containingType, helperMethod,
                        cluster.getContainingMethods());
                if (equivalent == null) {
                    throw new IllegalStateException(
                            "Method name '" + methodNameToUse + "' already exists in class " +
                                    containingType.getNameAsString());
                }
            }
            else {
                MethodDeclaration clone = helperMethod.clone();
                clone.setName(methodNameToUse);
                containingType.addMember(clone);
                modifiedCUs.put(seq.compilationUnit(), seq.sourceFilePath());
            }
        }
    }

    private boolean isCrossFileCluster() {
        java.util.Set<String> filePaths = new java.util.HashSet<>();
        for (StatementSequence seq : cluster.allSequences()) {
            if (seq.sourceFilePath() != null) {
                filePaths.add(seq.sourceFilePath().toString());
            }
        }
        return filePaths.size() > 1;
    }

    private RefactoringResult buildRefactoringResult(Map<CompilationUnit, Path> modifiedCUs) {
        Map<Path, String> modifiedFiles = modifiedCUs.entrySet().stream()
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getValue(), entry.getKey().toString()),
                        Map::putAll);

        return new RefactoringResult(modifiedFiles, recommendation.getStrategy(),
                "Extracted method: " + methodNameToUse);
    }

    /**
     * Precompute AST paths for parameters using the intact primary sequence.
     * This safeguards against AST detachment issues during repeated traversals.
     */
    private Map<ParameterSpec, ASTNodePath> precomputeParameterPaths() {
        Map<ParameterSpec, ASTNodePath> paths = new LinkedHashMap<>();

        for (ParameterSpec param : effectiveParams) {
            // Only structural params need paths (variationIndex != -1 usually, but check
            // all with coords)
            Expression node = findNodeByCoordinates(cluster.primary(), param.getStartLine(), param.getStartColumn());
            if (node != null) {
                ASTNodePath path = computePath(node);
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
    private HelperMethodResult createHelperMethod() {
        StatementSequence sequence = cluster.primary();
        DataFlowAnalyzer dfa = new DataFlowAnalyzer();
        Set<String> liveOutVars = getLiveOuts(dfa);

        String forcedReturnVar = null;
        com.github.javaparser.ast.type.Type forcedReturnType = null;

        if (liveOutVars.size() > 1) {
            // RELAXED CHECK: Allow multiple live-outs if we can identify a valid single return
            // and the others are redeclarable literals.
            Set<String> literalVars = dfa.findLiteralInitializedVariables(sequence);

            // Variables that MUST be returned (live-out and NOT a simple literal)
            Set<String> mustReturnVars = new HashSet<>(liveOutVars);
            mustReturnVars.removeAll(literalVars);

            if (mustReturnVars.size() > 1) {
                return null; // Still ambiguous/impossible to refactor (multiple complex returns)
            } else if (mustReturnVars.size() == 1) {
                // Good case: 1 complex variable to return, others are literals
                forcedReturnVar = mustReturnVars.iterator().next();
                forcedReturnType = resolveVariableType(sequence, forcedReturnVar);
            } else {
                // All are literals. Pick one deterministically (e.g. first alphabetically) to return, 
                // or if possible returns void? Unlikely for live-outs.
                // Let's pick the first one from liveOutVars as return.
                // Or better: pick one that is actually used outside? (liveOutVars are by definition used outside)
                // We pick one, and others will be re-declared.
                forcedReturnVar = liveOutVars.stream().sorted().findFirst().orElse(null);
                if (forcedReturnVar != null) {
                    forcedReturnType = resolveVariableType(sequence, forcedReturnVar);
                }
            }
        } else if (liveOutVars.size() == 1) {
            forcedReturnVar = liveOutVars.iterator().next();
            forcedReturnType = resolveVariableType(sequence, forcedReturnVar);
        }

        MethodDeclaration method = initializeHelperMethod();
        applyMethodModifiers(method);

        // Apply return type: Use forced type if available, otherwise recommendation
        if (forcedReturnType != null) {
            method.setType(forcedReturnType);
        } else {
            setReturnType(method);
        }

        // Determine effective parameters (sorted and filtered)
        Set<String> declaredVars = collectDeclaredVariableNames(sequence);
        paramNameOverrides = computeParamNameOverrides(declaredVars,
                recommendation.getSuggestedParameters());
        
        effectiveParams = determineEffectiveParameters(sequence, declaredVars);

        addParameters(method);
        copyThrownExceptions(method, sequence);

        // Determine body (with final decision on return var)
        String effectiveTargetVar = (forcedReturnVar != null) ? forcedReturnVar
                : determineTargetReturnVar(sequence, method.getType().asString());
        
        BlockStmt body = buildHelperMethodBody(sequence, effectiveTargetVar, method.getType());
        method.setBody(body);

        return new HelperMethodResult(method, effectiveParams, effectiveTargetVar);
    }

    /**
     * Filters and sorts parameters to ensure only actually used ones are included in the signature.
     */
    private List<ParameterSpec> determineEffectiveParameters(StatementSequence sequence,
                                                           Set<String> declaredVars) {

        // Sort parameters to match ArgumentBuilder order
        List<ParameterSpec> sortedParams = new ArrayList<>(recommendation.getSuggestedParameters());
        sortedParams.sort((p1, p2) -> {
            int idx1 = (p1.getVariationIndex() == null || p1.getVariationIndex() == -1) ? Integer.MAX_VALUE : p1.getVariationIndex();
            int idx2 = (p2.getVariationIndex() == null || p2.getVariationIndex() == -1) ? Integer.MAX_VALUE : p2.getVariationIndex();
            return Integer.compare(idx1, idx2);
        });

        // Determine target return variable for a temporary body to check parameter usage
        String tempTargetVar = determineTargetReturnVar(sequence, recommendation.getSuggestedReturnType().asString());
        BlockStmt tempBody = buildHelperMethodBody(sequence, tempTargetVar, recommendation.getSuggestedReturnType());

        List<ParameterSpec> usedParams = new ArrayList<>();
        for (ParameterSpec param : sortedParams) {
            String targetName = paramNameOverrides.getOrDefault(param, param.getName());

            // Exclude parameters that are actually local variables declared inside the sequence
            if (declaredVars.contains(param.getName())) {
                continue;
            }

            // Check if targetName is used in the body
            boolean isUsed = tempBody.findAll(com.github.javaparser.ast.expr.SimpleName.class).stream()
                    .anyMatch(n -> n.getIdentifier().equals(targetName));
            if (isUsed) {
                if (referencesDeclaredVariable(sequence, param, declaredVars)) {
                    continue;
                }
                usedParams.add(param);
            }
        }
        return usedParams;
    }

    private boolean referencesDeclaredVariable(StatementSequence sequence, ParameterSpec param, Set<String> declaredVars) {
        if (param.getStartLine() == null || param.getStartColumn() == null) {
            return false;
        }
        Expression expr = findNodeByCoordinates(sequence, param.getStartLine(), param.getStartColumn());
        if (expr == null) {
            return false;
        }
        return expr.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                .anyMatch(n -> declaredVars.contains(n.getNameAsString()));
    }

    private Set<String> getLiveOuts(DataFlowAnalyzer dfa) {
        Set<String> liveOutVars = new HashSet<>();

        int limit = recommendation.getValidStatementCount();

        for (StatementSequence seq : cluster.allSequences()) {
            StatementSequence seqToAnalyze = seq;
            if (limit != -1 && limit < seq.size()) {
                seqToAnalyze = createTruncatedSequence(seq, limit);
            }
            Set<String> seqLiveOut = dfa.findLiveOutVariables(seqToAnalyze);
            
            // Also include literal-initialized variables that are used after the sequence.
            // findLiveOutVariables excludes them by default, but we need them if we want
            // to allow returning a literal as the chosen return variable.
            Set<String> literalVars = dfa.findLiteralInitializedVariables(seqToAnalyze);
            Set<String> usedAfter = dfa.findVariablesUsedAfter(seqToAnalyze);
            literalVars.retainAll(usedAfter);
            seqLiveOut.addAll(literalVars);
            
            liveOutVars.addAll(seqLiveOut);
        }
        return liveOutVars;
    }

    private MethodDeclaration initializeHelperMethod() {
        MethodDeclaration method = new MethodDeclaration();
        method.setName(recommendation.getSuggestedMethodName());
        return method;
    }

    private void applyMethodModifiers(MethodDeclaration method) {
        boolean shouldBeStatic = false;
        for (StatementSequence seq : cluster.allSequences()) {
            if (seq.containingMethod() != null && seq.containingMethod().isStatic()) {
                shouldBeStatic = true;
                break;
            }
        }

        if (shouldBeStatic) {
            method.setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC);
        } else {
            method.setModifiers(Modifier.Keyword.PRIVATE);
        }
    }

    private StatementSequence createTruncatedSequence(StatementSequence fullSequence, int count) {
        if (count >= fullSequence.statements().size()) {
            return fullSequence;
        }
        com.raditha.dedup.model.Range fullRange = fullSequence.range();
        if (count <= 0) {
            return new StatementSequence(
                    java.util.Collections.emptyList(),
                    new com.raditha.dedup.model.Range(fullRange.startLine(), fullRange.startColumn(), fullRange.startLine(),
                            fullRange.startColumn()),
                    fullSequence.startOffset(),
                    fullSequence.containingMethod(),
                    fullSequence.compilationUnit(),
                    fullSequence.sourceFilePath());
        }
        java.util.List<com.github.javaparser.ast.stmt.Statement> prefixStmts = fullSequence.statements().subList(0,
                count);

        // Calculate new range based on prefix statements
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

    private void setReturnType(MethodDeclaration method) {
        com.github.javaparser.ast.type.Type returnType = recommendation.getSuggestedReturnType();
        method.setType(returnType != null ? returnType : new com.github.javaparser.ast.type.VoidType());
    }

    private Set<String> collectDeclaredVariableNames(StatementSequence sequence) {
        Set<String> declaredVars = new HashSet<>();
        int limit = getEffectiveLimit(sequence);
        List<Statement> stmts = sequence.statements();
        for (int i = 0; i < limit; i++) {
            Statement stmt = stmts.get(i);
            stmt.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                    .forEach(v -> declaredVars.add(v.getNameAsString()));
        }
        return declaredVars;
    }

    private Map<ParameterSpec, String> computeParamNameOverrides(Set<String> declaredVars, List<ParameterSpec> params) {
        return computeParamNameOverridesStatic(declaredVars, params);
    }

    public static Map<ParameterSpec, String> computeParamNameOverridesStatic(Set<String> declaredVars, List<ParameterSpec> params) {
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

    private void addParameters(MethodDeclaration method) {
        for (ParameterSpec param : effectiveParams) {
            String targetName = paramNameOverrides.getOrDefault(param, param.getName());
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
            String targetReturnVar,
            com.github.javaparser.ast.type.Type methodReturnType) {
        BlockStmt body = new BlockStmt();

        boolean hasExternalVars = hasExternalVariablesInReturn(sequence);
        List<Statement> statements = buildBodyStatements(sequence, targetReturnVar,
                hasExternalVars);
        for (Statement s : statements) {
            body.addStatement(s);
        }

        // CRITICAL FIX: If targetReturnVar is null, the method is void (forced by literal exclusion)
        // Don't add any return statement
        if (targetReturnVar != null && needsFinalReturn(body, methodReturnType)) {
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
            String targetReturnVar,
            boolean hasExternalVars) {
        List<Statement> result = new ArrayList<>();
        int limit = getEffectiveLimit(sequence);
        List<Statement> stmts = sequence.statements();
        for (int i = 0; i < limit; i++) {
            Statement original = stmts.get(i);
            Statement stmt = (effectiveParams == null)
                    ? substituteParameters(original.clone())
                    : substituteEffectiveParameters(original.clone());

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
    private boolean needsFinalReturn(BlockStmt body, com.github.javaparser.ast.type.Type methodReturnType) {
        // If return type is void, no return needed
        if (methodReturnType.isVoidType()) {
            return false;
        }
        // If body is empty or doesn't end with return, we need one
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

                // CRITICAL FIX: We should only inline returns that are simple NameExpr
                // where the variable is defined within the sequence.
                // If the return is more complex (e.g., "return x + 1"), we MUST NOT inline
                // because we'd lose the unique part of the expression in the caller.
                if (!returnExpr.isNameExpr()) {
                    return true;
                }

                String varName = returnExpr.asNameExpr().getNameAsString();
                // If this variable is NOT defined in the sequence, it's external
                if (!definedInSequence.contains(varName)) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * Phase 1: Prepare the method call expression by resolving arguments.
     * This reads the AST but does NOT modify it.
     * Returns null if argument resolution fails.
     */
    private MethodCallExpr prepareReplacement(StatementSequence sequence) {
        MethodDeclaration containingMethod = sequence.containingMethod();
        if (containingMethod == null || containingMethod.getBody().isEmpty()) {
            return null;
        }

        // 1) Build arguments using precomputed paths (avoiding AST traversal issues)
        NodeList<Expression> arguments = buildArgumentsForCall(sequence);
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
    private boolean applyReplacement(StatementSequence sequence,
            MethodCallExpr methodCall, String forcedReturnVar, com.github.javaparser.ast.type.Type returnType) {
        MethodDeclaration containingMethod = sequence.containingMethod();
        if (containingMethod == null || containingMethod.getBody().isEmpty()) {
            return false;
        }

        // Locate the actual block containing the sequence (might be nested)
        BlockStmt block = sequence.statements().getFirst().findAncestor(BlockStmt.class)
                .orElse(containingMethod.getBody().get());

        // 3) Remember any original return inside the duplicate sequence
        int limit = getEffectiveLimit(sequence);
        ReturnStmt originalReturnValues = findOriginalReturnInSequence(sequence, limit);

        // 4) Locate sequence in the block and replace
        int startIdx = findStatementIndex(block, sequence);
        if (startIdx < 0)
            return false;

        removeOldStatements(block, startIdx, limit);

        // 5) Insert new statements depending on return type
        if (returnType != null && returnType.isVoidType()) {
            insertVoidReplacement(block, startIdx, methodCall, originalReturnValues);
            redeclareLiteralVariables(sequence, block, startIdx + 1, null);
            return true;
        }

        applyValueReplacement(sequence, methodCall, forcedReturnVar, returnType, 
                containingMethod, block, startIdx, originalReturnValues);

        return true;
    }

    private void removeOldStatements(BlockStmt block, int startIdx, int removeCount) {
        for (int i = 0; i < removeCount && startIdx < block.getStatements().size(); i++) {
            block.getStatements().remove(startIdx);
        }
    }

    private void applyValueReplacement(StatementSequence sequence,
                                      MethodCallExpr methodCall, String forcedReturnVar,
                                      com.github.javaparser.ast.type.Type returnType,
                                      MethodDeclaration containingMethod, BlockStmt block,
                                      int startIdx, ReturnStmt originalReturnValues) {
        String varName = (forcedReturnVar != null) ? forcedReturnVar : inferReturnVariable(sequence);
        boolean nextIsReturn = startIdx < block.getStatements().size()
                && block.getStatements().get(startIdx).isReturnStmt();

        boolean returnHasExternalVars = hasExternalVariablesInReturn(sequence);
        boolean shouldReturnDirectly = canInlineReturn(containingMethod, block, originalReturnValues,
                returnHasExternalVars, nextIsReturn);

        if (!shouldReturnDirectly && varName == null) {
            varName = firstDefinedVariable(sequence);
            if (varName == null) {
                varName = "result"; // Absolute fallback
            }
        }

        insertValueReplacement(block, startIdx, methodCall, originalReturnValues, varName,
                shouldReturnDirectly, nextIsReturn, returnType);

        // Critical Fix: Even if we return a value, other literal-initialized variables
        // might be needed by the caller code. We must re-declare them.
        if (!shouldReturnDirectly) {
             redeclareLiteralVariables(sequence, block, startIdx + 1, varName);
        }
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

    private String inferReturnVariable(StatementSequence sequence) {
        int limit = getEffectiveLimit(sequence);
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

        // Fallback or if primary var not found in this sequence (shouldn't happen for clones)
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

    /**
     * Re-declare literal-initialized variables that are used after the sequence.
     * This fixes the issue where variables are defined in the helper but not returned
     * (they're not live-outs), yet the caller still references them.
     * 
     * @param returnedVarName The variable being returned by the helper (if any), which should NOT be re-declared
     */
    private void redeclareLiteralVariables(StatementSequence sequence, BlockStmt block, 
                                           int insertionPoint, String returnedVarName) {
        Set<String> literalsToRedeclare = getLiteralsToRedeclare(sequence, returnedVarName);

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

    private static Set<String> getLiteralsToRedeclare(StatementSequence sequence, String returnedVarName) {
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
        return literalsToRedeclare;
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

    private NodeList<Expression> buildArgumentsForCall(StatementSequence sequence) {
        // Delegate to a focused builder that preserves existing behavior and guardrails
        return new ArgumentBuilder().buildArgs(sequence);
    }

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
        
        // Robust check: all statements must be structurally equal
        return bodyStmts.equals(seqStmts);
    }

    private class ArgumentBuilder {
        NodeList<Expression> buildArgs(StatementSequence sequence) {
            NodeList<Expression> arguments = new NodeList<>();

            // Use the effective parameters which are already sorted and filtered
            for (ParameterSpec param : MethodExtractor.this.effectiveParams) {
                Expression expr = resolveValue(sequence, param);
                if (expr == null) {
                    return new NodeList<>(); // cannot resolve safely
                }

                if (!isTypeCompatible(param, expr, sequence)) {
                    return new NodeList<>();
                }

                expr = adaptArgument(param, expr);
                if (expr == null) {
                    return new NodeList<>();
                }

                arguments.add(expr);
            }

            return arguments;
        }

        private boolean isTypeCompatible(ParameterSpec param, Expression expr, StatementSequence sequence) {
            TypeWrapper paramTypeWrapper = AbstractCompiler.findType(sequence.compilationUnit(), param.getType());

            if (paramTypeWrapper != null) {
                TypeWrapper exprTypeWrapper = resolveExprTypeWrapper(expr, sequence);
                if (exprTypeWrapper != null) {
                    return paramTypeWrapper.isAssignableFrom(exprTypeWrapper);
                }
                
                // Fallback for literals if wrapper resolution failed
                return !isNumericType(param.getType()) || !expr.isStringLiteralExpr();
            } else {
                // Fallback to old heuristic if param type cannot be resolved
                if (isNumericType(param.getType())) {
                    if (expr.isStringLiteralExpr()) {
                        return false;
                    }
                    TypeWrapper exprTypeWrapper = resolveExprTypeWrapper(expr, sequence);
                    return exprTypeWrapper == null ||
                            (!"java.lang.String".equals(exprTypeWrapper.getName()) && !"String".equals(exprTypeWrapper.getName()));
                }
            }
            return true;
        }

        private Expression adaptArgument(ParameterSpec param, Expression expr) {
            // Specific check for Class<?> types to ensure adaptations work
            if (isClassType(param.getType()) && !(expr.isClassExpr() ||
                    (expr.isFieldAccessExpr() && expr.asFieldAccessExpr().getNameAsString().equals("class")))) {
                // Try to adapt NameExpr or other simple forms into ClassExpr
                if (expr.isNameExpr()) {
                    return new ClassExpr(new ClassOrInterfaceType(null, expr.asNameExpr().getNameAsString()));
                }
                return null; // unsuccessful adaptation
            }
            return expr;
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
                com.github.javaparser.ast.type.Type type = MethodExtractor.this.resolveVariableType(sequence, varName);
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
         * Uses cluster.primary() to identify the node from coordinates.
         */
        private Expression extractActualValue(StatementSequence targetSequence, ParameterSpec param) {
            if (cluster.primary() == null)
                return null;

            // 1. Find the node in the primary sequence using coordinates
            Expression primaryNode = findNodeByCoordinates(cluster.primary(), param.getStartLine(), param.getStartColumn());
            if (primaryNode == null) {
                return null;
            }

            // 2. Compute path in primary
            ASTNodePath path = computePath(primaryNode);
            if (path == null) {
                return null;
            }

            // 3. Follow path in target
            return followPath(targetSequence, path);
        }

        private Expression resolveValue(StatementSequence sequence, ParameterSpec param) {
            // Priority 0: Precomputed Structural Path (Robust against AST mutation)
            if (precomputedPaths != null && precomputedPaths.containsKey(param)) {
                ASTNodePath path = precomputedPaths.get(param);
                Expression found = followPath(sequence, path);
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
                Expression structuralExpr = extractActualValue(sequence, param);
                if (structuralExpr != null) {
                    return structuralExpr;
                }
            }

            // Priority 1.5: Invariant Fallback (for variationIndex == -1 or null)
            // If structural path failed but it's an invariant (or captured param), use the name.
            if (param.getVariationIndex() == null || param.getVariationIndex() == -1) {
                return new NameExpr(param.getName());
            }
            return null; // Fail if structural extraction cannot find it
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


        private Expression followPath(StatementSequence sequence, ASTNodePath path) {
            if (path.statementIndex < 0 || path.statementIndex >= sequence.statements().size()) {
                return null;
            }
            com.github.javaparser.ast.Node current = sequence.statements().get(path.statementIndex);

            for (int idx : path.childPath) {
                List<com.github.javaparser.ast.Node> children = current.getChildNodes().stream()
                        .filter(n -> !(n instanceof com.github.javaparser.ast.comments.Comment))
                        .toList();
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

    /**
     * Represents a structural path to a node within a StatementSequence.
     * Path consist of:
     * 1. Index of the statement within the sequence.
     * 2. List of child indices to navigate from that statement to the target node.
     */
    private record ASTNodePath(int statementIndex, List<Integer> childPath) {
    }

    private ASTNodePath computePath(com.github.javaparser.ast.Node target) {
        // 1. Find which statement contains the target
        int stmtIdx = -1;
        Statement rootStmt = null;
        List<Statement> statements = cluster.primary().statements();
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
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
                .toList();
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) == child)
                return i;
        }
        return -1;
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
        if (line == null || column == null) {
            return null;
        }
        return super.findNodeByCoordinates(sequence, line, column);
    }

    /**
     * Substitute varying literals/expressions with parameter names in the extracted
     * method body.
     * This ensures that the extracted method uses parameters instead of hardcoded
     * values.
     */
    private Statement substituteParameters(Statement stmt) {
        return substituteParametersStatic(stmt, recommendation, paramNameOverrides);
    }

    private Statement substituteEffectiveParameters(Statement stmt) {
        for (ParameterSpec param : effectiveParams) {
            String paramName = paramNameOverrides.getOrDefault(param, param.getName());

            if (tryLocationBasedReplace(stmt, param, paramName)) {
                continue;
            }

            if (param.getStartLine() == null && !param.getExampleValues().isEmpty()) {
                String exampleValue = param.getExampleValues().getFirst();
                for (Expression expr : stmt.findAll(Expression.class)) {
                    if (expr.toString().equals(exampleValue) && expr.getParentNode().isPresent()) {
                        expr.replace(new NameExpr(paramName));
                        break;
                    }
                }
            }
        }
        return stmt;
    }

    /**
     * Static utility method for substituting parameters, used by other refactoring classes.
     */
    public static Statement substituteParametersStatic(Statement stmt,
            RefactoringRecommendation recommendation, Map<ParameterSpec, String> nameOverrides) {
        // Orchestrate substitution per parameter
        for (ParameterSpec param : recommendation.getSuggestedParameters()) {
            String paramName = resolveParamNameStatic(param, nameOverrides);

            // 1) Prefer exact location-based replacement to avoid accidental collisions
            if (tryLocationBasedReplace(stmt, param, paramName)) {
                continue; // done for this parameter
            }

            // 2) Value-based fallback: replace matching expressions that lack location
            // metadata
            // FIXED: Only use fallback if we genuinely don't have location info.
            // If we have location info but didn't find it (step 1 failed), it probably means
            // the node isn't in this statement (which is fine).
            // We should NOT fallback to value replacement in that case, as it risks replacing invariants.
            if (param.getStartLine() == null && !param.getExampleValues().isEmpty()) {
                String exampleValue = param.getExampleValues().getFirst();
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
     * Static utility version for resolving parameter names.
     */
    private static String resolveParamNameStatic(ParameterSpec param, Map<ParameterSpec, String> nameOverrides) {
        return nameOverrides.getOrDefault(param, param.getName());
    }

    /**
     * Try to replace a specific expression occurrence using the original source
     * location
     * recorded on the parameter. Returns true if a replacement was made.
     */
    private static boolean tryLocationBasedReplace(Statement stmt, ParameterSpec param, String paramName) {
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
    private MethodDeclaration findEquivalentHelper(TypeDeclaration<?> containingType,
            MethodDeclaration newHelper, Set<MethodDeclaration> excludedMethods) {
        boolean newIsStatic = newHelper.getModifiers().stream()
                .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
        String newReturnType = newHelper.getType().asString();
        List<String> newParamTypes = new java.util.ArrayList<>();
        for (Parameter p : newHelper.getParameters()) {
            newParamTypes.add(p.getType().asString());
        }
        String newBodyNorm = normalizeMethodBody(newHelper);

        for (MethodDeclaration candidate : containingType.getMethods()) {
            // CRITICAL FIX: Never reuse ANY method that is part of the cluster being refactored!
            if (excludedMethods.contains(candidate) || !candidate.getModifiers().contains(Modifier.privateModifier()))
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

            if (!isParamsMatch(candidate, newParamTypes))
                continue;

            // Compare normalized bodies
            String candNorm = normalizeMethodBody(candidate);
            if (candNorm != null && candNorm.equals(newBodyNorm)) {
                return candidate; // Reuse this
            }
        }

        return null;
    }

    private static boolean isParamsMatch(MethodDeclaration candidate, List<String> newParamTypes) {
        boolean paramsMatch = true;
        for (int i = 0; i < candidate.getParameters().size(); i++) {
            String ct = candidate.getParameter(i).getType().asString();
            if (!ct.equals(newParamTypes.get(i))) {
                paramsMatch = false;
                break;
            }
        }
        return paramsMatch;
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
    private int getEffectiveLimit(StatementSequence sequence) {
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
