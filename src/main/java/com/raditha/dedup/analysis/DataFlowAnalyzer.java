package com.raditha.dedup.analysis;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;
import org.jspecify.annotations.Nullable;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Performs data flow analysis to determine which variables are live
 * after a sequence of statements.
 * <p>
 * This is critical for Gap 5: ensures we return the CORRECT variable,
 * not just the first one of matching type.
 */
public class DataFlowAnalyzer {

    private static void findCandidate(Set<String> liveOut, Set<String> returnedVars, VariableDeclarationExpr expr,
            List<String> candidates) {
        VariableDeclarationExpr varDecl = expr.asVariableDeclarationExpr();
        for (var variable : varDecl.getVariables()) {
            String varName = variable.getNameAsString();

            // FIXED: Accept if live-out OR returned, regardless of type
            if (liveOut.contains(varName) || returnedVars.contains(varName)) {
                candidates.add(varName);
            }
        }
    }

    private static String findBestCandidate(List<String> candidates, StatementSequence sequence) {
        // Prefer the first candidate whose type is not primitive-like
        for (String varName : candidates) {
            for (Statement stmt : sequence.statements()) {
                if (stmt instanceof ExpressionStmt expr
                        && expr.getExpression() instanceof VariableDeclarationExpr varDecl) {
                    for (var variable : varDecl.getVariables()) {
                        var type = variable.getType();
                        if (variable.getNameAsString().equals(varName) && !isPrimitiveLike(type)) {
                            return varName;
                        }
                    }
                }
            }
        }
        // If all are primitive-like, return first
        return candidates.getFirst();
    }

    private static boolean isPrimitiveLike(com.github.javaparser.ast.type.Type type) {
        if (type.isClassOrInterfaceType()) {
            return type.asClassOrInterfaceType().getNameAsString().equals("String");
        }
        return type.isPrimitiveType();
    }

    /**
     * Find variables that are:
     * 1. Defined within the sequence (assignment or declaration)
     * 2. Used AFTER the sequence ends (live out)
     * 3. NOT initialized from literal values (those are compile-time constants)
     * <p>
     * This identifies which variables must be returned from extracted method.
     * Variables initialized from literals (e.g., String x = "foo") are excluded
     * because the extracted helper will contain the same literals.
     */
    public Set<String> findLiveOutVariables(StatementSequence sequence) {
        SequenceAnalysis analysis = analyzeSequenceVariables(sequence);
        
        Set<String> usedAfter = findVariablesUsedAfter(sequence);

        // Return intersection: defined in sequence AND used after, EXCLUDING literals
        Set<String> liveOut = new HashSet<>(analysis.definedVars());
        liveOut.retainAll(usedAfter);
        liveOut.removeAll(analysis.literalVars());  // Exclude literal-initialized variables

        // Determine which variables are defined at the top level of the sequence
        Set<String> topLevelDefined = new HashSet<>();
        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt()) {
                var expr = stmt.asExpressionStmt().getExpression();
                if (expr.isVariableDeclarationExpr()) {
                    expr.asVariableDeclarationExpr().getVariables()
                            .forEach(v -> topLevelDefined.add(v.getNameAsString()));
                }
            }
        }

        // Exclude variables whose scope is strictly internal to the sequence
        // (e.g. loop variables, catch parameters, variables in nested blocks).
        // Only remove variables that are not also defined at the top level.
        Set<String> internalOnly = new HashSet<>(analysis.internalVars());
        internalOnly.removeAll(topLevelDefined);
        liveOut.removeAll(internalOnly);

        return liveOut;
    }

    public record SequenceAnalysis(
            Set<String> definedVars,
            Set<String> literalVars,
            Set<String> internalVars,
            Set<String> usedVars,
            Set<String> returnedVars,
            java.util.Map<String, com.github.javaparser.ast.type.Type> typeMap
    ) {}

    public SequenceAnalysis analyzeSequenceVariables(StatementSequence sequence) {
        Set<String> defined = new HashSet<>();
        Set<String> literals = new HashSet<>();
        Set<String> internal = new HashSet<>();
        Set<String> used = new HashSet<>();
        Set<String> returned = new HashSet<>();
        java.util.Map<String, com.github.javaparser.ast.type.Type> typeMap = new java.util.HashMap<>();
        Set<Statement> topLevelStmts = new HashSet<>(sequence.statements());

        SequenceAnalysisVisitor visitor = new SequenceAnalysisVisitor(topLevelStmts);
        for (Statement stmt : sequence.statements()) {
            stmt.accept(visitor, new AnalysisContext(defined, literals, internal, used, returned, typeMap));
        }
        return new SequenceAnalysis(defined, literals, internal, used, returned, typeMap);
    }

    private record AnalysisContext(
            Set<String> defined,
            Set<String> literals,
            Set<String> internal,
            Set<String> used,
            Set<String> returned,
            java.util.Map<String, com.github.javaparser.ast.type.Type> typeMap
    ) {}

    private static class SequenceAnalysisVisitor extends com.github.javaparser.ast.visitor.VoidVisitorAdapter<AnalysisContext> {
        private final Set<Statement> topLevelStmts;

        public SequenceAnalysisVisitor(Set<Statement> topLevelStmts) {
            this.topLevelStmts = topLevelStmts;
        }

        @Override
        public void visit(com.github.javaparser.ast.stmt.ReturnStmt n, AnalysisContext ctx) {
            super.visit(n, ctx);
            if (n.getExpression().isPresent()) {
                n.getExpression().get().findAll(NameExpr.class).forEach(ne -> ctx.returned().add(ne.getNameAsString()));
            }
        }

        @Override
        public void visit(VariableDeclarator n, AnalysisContext ctx) {
            super.visit(n, ctx);
            String name = n.getNameAsString();
            ctx.defined().add(name);
            ctx.typeMap().put(name, n.getType());

            // Check if literal
            if (n.getInitializer().isPresent() && n.getInitializer().get().isLiteralExpr()) {
                ctx.literals().add(name);
            }

            // Check if internal
            Statement stmt = n.findAncestor(Statement.class).orElse(null);
            if (stmt == null || !topLevelStmts.contains(stmt) || !stmt.isExpressionStmt()) {
                ctx.internal().add(name);
            }
        }

        @Override
        public void visit(com.github.javaparser.ast.expr.AssignExpr n, AnalysisContext ctx) {
            super.visit(n, ctx);
            if (n.getTarget().isNameExpr()) {
                ctx.defined().add(n.getTarget().asNameExpr().getNameAsString());
            }
        }

        @Override
        public void visit(com.github.javaparser.ast.expr.LambdaExpr n, AnalysisContext ctx) {
            super.visit(n, ctx);
            n.getParameters().forEach(p -> {
                String name = p.getNameAsString();
                ctx.defined().add(name);
                ctx.internal().add(name);
            });
        }

        @Override
        public void visit(NameExpr n, AnalysisContext ctx) {
            super.visit(n, ctx);
            ctx.used().add(n.getNameAsString());
        }

        @Override
        public void visit(com.github.javaparser.ast.stmt.CatchClause n, AnalysisContext ctx) {
            super.visit(n, ctx);
            String name = n.getParameter().getNameAsString();
            ctx.defined().add(name);
            ctx.internal().add(name);
            ctx.typeMap().put(name, n.getParameter().getType());
        }
    }

    /**
     * Finds variables initialized exclusively from literal values.
     */
    public Set<String> findLiteralInitializedVariables(StatementSequence sequence) {
        return analyzeSequenceVariables(sequence).literalVars();
    }

    /**
     * Finds variables defined (assigned or declared) in the sequence.
     */
    public Set<String> findDefinedVariables(StatementSequence sequence) {
        return analyzeSequenceVariables(sequence).definedVars();
    }

    /**
     * Find variables used AFTER the sequence ends in the containing method.
     * <p>
     * Uses physical source code ordering (Ranges) to be robust against
     * list index shifting caused by boundary refinement.
     */
    public Set<String> findVariablesUsedAfter(StatementSequence sequence) {
        Set<String> usedAfter = new HashSet<>();

        CallableDeclaration<?> method = sequence.containingCallable();
        if (method == null || sequence.getCallableBody().isEmpty()) {
            return usedAfter;
        }

        // Get the specific end line/column from the LAST statement in the sequence
        // We use statements list to be exact, ignoring trailing comments/whitespace
        // covered by sequence.range()
        if (sequence.statements().isEmpty()) {
            return usedAfter;
        }
        Statement lastStmt = sequence.statements().get(sequence.statements().size() - 1);
        int endLine = lastStmt.getRange().map(r -> r.end.line).orElse(sequence.range().endLine());
        int endColumn = lastStmt.getRange().map(r -> r.end.column).orElse(sequence.range().endColumn());


        // Scan the entire method body for variable usages
        BlockStmt methodBody = sequence.getCallableBody().get();
        methodBody.findAll(NameExpr.class).forEach(nameExpr -> {
            // Check if this usage is physically AFTER the sequence
            if (nameExpr.getRange().isPresent()) {
                var range = nameExpr.getRange().get();
                boolean isAfter = range.begin.line > endLine
                        || (range.begin.line == endLine && range.begin.column > endColumn);

                if (isAfter) {
                    usedAfter.add(nameExpr.getNameAsString());
                }
            }
        });

        return usedAfter;
    }

    /**
     * Find all variables used (referenced) within the sequence.
     */
    public Set<String> findVariablesUsedInSequence(StatementSequence sequence) {
        Set<String> used = new HashSet<>();
        for (Statement stmt : sequence.statements()) {
            stmt.findAll(NameExpr.class).forEach(nameExpr -> used.add(nameExpr.getNameAsString()));
        }
        return used;
    }

    public List<String> findCandidates(StatementSequence sequence, Set<String> liveOut, SequenceAnalysis analysis) {
        List<String> candidates = new ArrayList<>();
        Set<String> returnedVars = analysis.returnedVars();

        // Check for Variable Declarations in the sequence
        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt()) {
                var expr = stmt.asExpressionStmt().getExpression();
                if (expr.isVariableDeclarationExpr()) {
                    for (var variable : expr.asVariableDeclarationExpr().getVariables()) {
                        String varName = variable.getNameAsString();
                        if (liveOut.contains(varName) || returnedVars.contains(varName)) {
                            candidates.add(varName);
                        }
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * Find the correct return variable for an extracted method.
     * <p>
     * Returns the variable that is:
     * 1. Defined in the sequence
     * 2. Used after the sequence
     * 3. Matches the expected return type
     * <p>
     * Returns null if no suitable variable found or multiple candidates.
     */
    public String findReturnVariable(StatementSequence sequence, String returnType) {
        SequenceAnalysis analysis = analyzeSequenceVariables(sequence);
        return findReturnVariable(sequence, returnType, analysis);
    }

    public String findReturnVariable(StatementSequence sequence, String returnType, SequenceAnalysis analysis) {
        Set<String> usedAfter = findVariablesUsedAfter(sequence);

        Set<String> liveOut = new HashSet<>(analysis.definedVars());
        liveOut.retainAll(usedAfter);
        // We include literals if they are live-outs and potentially needed for return
        // liveOut.removeAll(analysis.literalVars()); // REMOVED: Literals CAN be return variables if needed
        liveOut.removeAll(analysis.internalVars());

        List<String> candidates = findCandidates(sequence, liveOut, analysis);

        // Filter candidates by type compatibility
        if (returnType != null && !"void".equals(returnType)) {
            candidates.removeIf(candidate -> !isTypeCompatible(sequence, candidate, returnType));
        }

        // Only return if there's exactly ONE candidate
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }

        // If multiple candidates remain, pick the best one (non-primitive preferred)
        if (candidates.size() > 1) {
            return findBestCandidate(candidates, sequence);
        }

        // FALLBACK: If standard analysis found nothing, check if there is exactly
        // one DEFINED variable matching the return type.
        return findFallBackCandidate(sequence, returnType);
    }

    private String findFallBackCandidate(StatementSequence sequence, String returnType) {
        if (returnType == null || "void".equals(returnType)) {
            return null;
        }

        List<String> fallbackCandidates = new ArrayList<>();
        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt()) {
                var expr = stmt.asExpressionStmt().getExpression();
                if (expr.isVariableDeclarationExpr()) {
                    expr.asVariableDeclarationExpr().getVariables().forEach(v -> {
                        if (v.getType().asString().equals(returnType) || v.getType().asString().contains(returnType)
                                || returnType.contains(v.getType().asString())) {
                            fallbackCandidates.add(v.getNameAsString());
                        }
                    });
                }
            }
        }

        if (fallbackCandidates.size() == 1) {
            return fallbackCandidates.getFirst();
        }

        return null;
    }

    /**
     * Check if it's safe to extract the sequence.
     */
    public boolean isSafeToExtract(StatementSequence sequence, String expectedReturnType) {
        String returnVar = findReturnVariable(sequence, expectedReturnType);

        // If "void" is expected, just check no important variables escape
        if ("void".equals(expectedReturnType)) {
            return findLiveOutVariables(sequence).isEmpty();
        }

        // Otherwise, must have exactly one return variable
        return returnVar != null;
    }

    /**
     * Check if a variable's type is compatible with the expected return type.
     * Uses AbstractCompiler and TypeWrapper for robust type checking.
     */
    public boolean isTypeCompatible(StatementSequence sequence, String varName, String expectedType) {
        // 1. Find the variable declaration to get its types

        VariableDeclarator targetVar = getVariableDeclarator(sequence, varName);

        if (targetVar == null)
            return false;

        // 2. Resolve types for the variable
        // findTypesInVariable returns list of wrappers. For generics like List<User>,
        // it returns [User, List].
        // We are interested in the main type (last one).
        List<TypeWrapper> varTypes = AbstractCompiler.findTypesInVariable(targetVar);
        if (varTypes.isEmpty())
            return false;
        TypeWrapper varWrapper = varTypes.getLast();

        // 3. Resolve the expected type
        TypeWrapper expectedWrapper;

        String simpleType = expectedType;
        if (simpleType.contains("<")) {
            // Strip generics - AbstractCompiler.findType finds the raw type anyway
            simpleType = simpleType.substring(0, simpleType.indexOf('<'));
        }

        expectedWrapper = AbstractCompiler.findType(sequence.compilationUnit(), simpleType);

        if (expectedWrapper == null) {
            // If we can't resolve expected type, fallback to basic string match to be safe
            return varWrapper.getName() != null && (varWrapper.getName().equals(expectedType) ||
                    varWrapper.getName().endsWith("." + expectedType));
        }

        // 4. Check compatibility
        return expectedWrapper.isAssignableFrom(varWrapper);
    }

    private static @Nullable VariableDeclarator getVariableDeclarator(StatementSequence sequence, String varName) {
        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
                var decl = stmt.asExpressionStmt().getExpression().asVariableDeclarationExpr();
                for (var v : decl.getVariables()) {
                    if (v.getNameAsString().equals(varName)) {
                        return v;
                    }
                }
            }

        }
        return null;
    }
}
