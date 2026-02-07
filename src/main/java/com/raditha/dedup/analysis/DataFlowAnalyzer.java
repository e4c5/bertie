package com.raditha.dedup.analysis;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.raditha.dedup.model.StatementSequence;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Performs data flow analysis to determine which variables are live
 * after a sequence of statements.
 */
public class DataFlowAnalyzer {

    private static String findBestCandidate(List<String> candidates, StatementSequence sequence) {
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
        return candidates.getFirst();
    }

    private static boolean isPrimitiveLike(com.github.javaparser.ast.type.Type type) {
        if (type.isClassOrInterfaceType()) {
            return type.asClassOrInterfaceType().getNameAsString().equals("String");
        }
        return type.isPrimitiveType();
    }

    public Set<String> findLiveOutVariables(StatementSequence sequence) {
        SequenceAnalysis analysis = analyzeSequenceVariables(sequence);
        Set<String> usedAfter = findVariablesUsedAfter(sequence);

        Set<String> liveOut = new HashSet<>(analysis.definedVars());
        liveOut.retainAll(usedAfter);
        liveOut.removeAll(analysis.literalVars());

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
     * Find all variables used (referenced) within the sequence.
     */
    public Set<String> findVariablesUsedInSequence(StatementSequence sequence) {
        Set<String> used = new HashSet<>();
        for (Statement stmt : sequence.statements()) {
            stmt.findAll(NameExpr.class).forEach(nameExpr -> used.add(nameExpr.getNameAsString()));
        }
        return used;
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

            if (n.getInitializer().isPresent() && n.getInitializer().get().isLiteralExpr()) {
                ctx.literals().add(name);
            }

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

    public Set<String> findVariablesUsedAfter(StatementSequence sequence) {
        Set<String> usedAfter = new HashSet<>();
        // Use getCallableBody() to support all container types (methods, constructors, initializers, lambdas)
        if (sequence.getCallableBody().isEmpty() || sequence.statements().isEmpty()) {
            return usedAfter;
        }

        Statement lastStmt = sequence.statements().getLast();
        int endLine = lastStmt.getRange().map(r -> r.end.line).orElse(sequence.range().endLine());
        int endColumn = lastStmt.getRange().map(r -> r.end.column).orElse(sequence.range().endColumn());

        BlockStmt methodBody = sequence.getCallableBody().get();
        methodBody.findAll(NameExpr.class).forEach(nameExpr -> {
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

    public List<String> findCandidates(StatementSequence sequence, Set<String> liveOut, SequenceAnalysis analysis) {
        List<String> candidates = new ArrayList<>();
        Set<String> returnedVars = analysis.returnedVars();

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

    public String findReturnVariable(StatementSequence sequence, Type returnType) {
        SequenceAnalysis analysis = analyzeSequenceVariables(sequence);
        return findReturnVariable(sequence, returnType, analysis);
    }

    public String findReturnVariable(StatementSequence sequence, Type returnType, SequenceAnalysis analysis) {
        Set<String> usedAfter = findVariablesUsedAfter(sequence);

        Set<String> liveOut = new HashSet<>(analysis.definedVars());
        liveOut.retainAll(usedAfter);
        liveOut.removeAll(analysis.internalVars());

        List<String> candidates = findCandidates(sequence, liveOut, analysis);

        if (returnType != null && !returnType.isVoidType()) {
            candidates.removeIf(candidate -> !isTypeCompatible(sequence, candidate, returnType));
        }

        if (candidates.size() == 1) {
            return candidates.getFirst();
        }

        if (candidates.size() > 1) {
            return findBestCandidate(candidates, sequence);
        }

        return findFallBackCandidate(sequence, returnType);
    }

    private String findFallBackCandidate(StatementSequence sequence, Type returnType) {
        if (returnType == null || returnType.isVoidType()) {
            return null;
        }

        String typeStr = returnType.asString();
        List<String> fallbackCandidates = new ArrayList<>();
        for (Statement stmt : sequence.statements()) {
            if (stmt.isExpressionStmt()) {
                var expr = stmt.asExpressionStmt().getExpression();
                if (expr.isVariableDeclarationExpr()) {
                    expr.asVariableDeclarationExpr().getVariables().forEach(v -> {
                        String vType = v.getType().asString();
                        if (vType.equals(typeStr) || vType.contains(typeStr) || typeStr.contains(vType)) {
                            fallbackCandidates.add(v.getNameAsString());
                        }
                    });
                }
            }
        }

        return fallbackCandidates.size() == 1 ? fallbackCandidates.getFirst() : null;
    }

    public boolean isSafeToExtract(StatementSequence sequence, Type expectedReturnType) {
        String returnVar = findReturnVariable(sequence, expectedReturnType);

        if (expectedReturnType.isVoidType()) {
            return findLiveOutVariables(sequence).isEmpty();
        }

        return returnVar != null;
    }

    public boolean isTypeCompatible(StatementSequence sequence, String varName, Type expectedType) {
        VariableDeclarator targetVar = getVariableDeclarator(sequence, varName);
        if (targetVar == null) return false;

        List<TypeWrapper> varTypes = AbstractCompiler.findTypesInVariable(targetVar);
        if (varTypes.isEmpty()) return false;
        TypeWrapper varWrapper = varTypes.getLast();

        String typeStr = expectedType.asString();
        if (typeStr.contains("<")) {
            typeStr = typeStr.substring(0, typeStr.indexOf('<'));
        }

        TypeWrapper expectedWrapper = AbstractCompiler.findType(sequence.compilationUnit(), typeStr);

        if (expectedWrapper == null) {
            return varWrapper.getName() != null && (varWrapper.getName().equals(typeStr) ||
                    varWrapper.getName().endsWith("." + typeStr));
        }

        return expectedWrapper.isAssignableFrom(varWrapper);
    }

    private static VariableDeclarator getVariableDeclarator(StatementSequence sequence, String varName) {
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
