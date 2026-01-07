package com.raditha.dedup.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.raditha.dedup.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Analyzes variations between duplicate code sequences using AST comparison.
 * Identifies varying expressions and variable references for parameter
 * extraction.
 */
public class ASTVariationAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(ASTVariationAnalyzer.class);

    /**
     * Analyze variations between two statement sequences.
     * 
     * @param seq1 First sequence
     * @param seq2 Second sequence
     * @param cu1  CompilationUnit containing first sequence
     * @param cu2  CompilationUnit containing second sequence
     * @return Analysis with varying expressions and variable references
     */
    public VariationAnalysis analyzeVariations(
            StatementSequence seq1,
            StatementSequence seq2,
            CompilationUnit cu1,
            CompilationUnit cu2) {
        List<VaryingExpression> variations = new ArrayList<>();
        Set<VariableReference> varRefs = new HashSet<>();

        // Walk both sequences in parallel
        int minSize = Math.min(seq1.statements().size(), seq2.statements().size());

        for (int i = 0; i < minSize; i++) {
            Statement stmt1 = seq1.statements().get(i);
            Statement stmt2 = seq2.statements().get(i);

            // Find differing expressions
            findDifferences(stmt1, stmt2, i, variations, cu1, cu2);

            // Find variable references in first sequence (representative)
            findVariableReferences(stmt1, varRefs, cu1);
        }

        logger.debug("[ASTVariationAnalyzer] Found {} varying expressions, {} variable references",
                variations.size(), varRefs.size());

        // Create VariationAnalysis with AST-based data
        return VariationAnalysis.builder()
                .varyingExpressions(variations)
                .variableReferences(varRefs)
                .build();
    }

    /**
     * Find expressions that differ between two statements.
     */
    private void findDifferences(
            Statement stmt1,
            Statement stmt2,
            int position,
            List<VaryingExpression> variations,
            CompilationUnit cu1,
            CompilationUnit cu2) {
        // Get all expressions from both statements
        List<Expression> exprs1 = stmt1.findAll(Expression.class);
        List<Expression> exprs2 = stmt2.findAll(Expression.class);

        // Compare expressions at same positions
        int minExprs = Math.min(exprs1.size(), exprs2.size());

        for (int i = 0; i < minExprs; i++) {
            Expression e1 = exprs1.get(i);
            Expression e2 = exprs2.get(i);

            if (!expressionsEquivalent(e1, e2)) {
                // Resolve type
                ResolvedType type = resolveExpressionType(e1, cu1);

                variations.add(new VaryingExpression(position, e1, e2, type));

                logger.debug("[ASTVariationAnalyzer] Variation at pos {}: {} vs {}",
                        position, e1, e2);
            }
        }
    }

    /**
     * Check if two expressions are semantically equivalent.
     * For now, uses string comparison (can be improved).
     */
    private boolean expressionsEquivalent(Expression e1, Expression e2) {
        // Simple structural comparison
        return e1.toString().equals(e2.toString());
    }

    /**
     * Find all variable references in a statement.
     */
    private void findVariableReferences(
            Statement stmt,
            Set<VariableReference> varRefs,
            CompilationUnit cu) {
        // Find all NameExpr (variable references)
        stmt.findAll(NameExpr.class).forEach(nameExpr -> {
            try {
                // Try to resolve using JavaParser's built-in resolution
                ResolvedValueDeclaration resolved = nameExpr.resolve();

                Scope scope = determineScope(resolved);
                ResolvedType type = resolved.getType();

                varRefs.add(new VariableReference(
                        nameExpr.getNameAsString(),
                        type,
                        scope));

                logger.debug("[ASTVariationAnalyzer] Variable reference: {} (scope: {})",
                        nameExpr.getNameAsString(), scope);

            } catch (Exception e) {
                // If resolution fails, add as UNKNOWN
                varRefs.add(VariableReference.unknown(nameExpr.getNameAsString()));

                logger.debug("[ASTVariationAnalyzer] Could not resolve variable: {}",
                        nameExpr.getNameAsString());
            }
        });
    }

    /**
     * Determine the scope of a resolved variable.
     */
    private Scope determineScope(ResolvedValueDeclaration resolved) {
        if (resolved.isParameter()) {
            return Scope.PARAMETER;
        } else if (resolved.isVariable()) {
            return Scope.LOCAL_VAR;
        } else if (resolved.isField()) {
            return Scope.FIELD;
        }
        return Scope.UNKNOWN;
    }

    /**
     * Resolve the type of an expression.
     */
    private ResolvedType resolveExpressionType(Expression expr, CompilationUnit cu) {
        try {
            return expr.calculateResolvedType();
        } catch (Exception e) {
            logger.debug("[ASTVariationAnalyzer] Could not resolve type for: {}", expr);
            return null;
        }
    }
}
