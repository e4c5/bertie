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
     * @return Analysis with varying expressions and variable references
     */
    public VariationAnalysis analyzeVariations(
            StatementSequence seq1,
            StatementSequence seq2,
            CompilationUnit cu1) {
        List<VaryingExpression> variations = new ArrayList<>();
        Set<VariableReference> varRefs = new HashSet<>();

        // Walk both sequences in parallel
        int minSize = Math.min(seq1.statements().size(), seq2.statements().size());

        // Collect variables declared within the sequence (to avoid treating them as
        // external references)
        Set<String> declaredInternalVars = new HashSet<>();
        for (Statement stmt : seq1.statements()) {
            findDeclarations(stmt, declaredInternalVars);
        }

        for (int i = 0; i < minSize; i++) {
            Statement stmt1 = seq1.statements().get(i);
            Statement stmt2 = seq2.statements().get(i);

            // Find differing expressions
            findDifferences(stmt1, stmt2, i, variations);

            // Find variable references in first sequence (representative)
            findVariableReferences(stmt1, varRefs, declaredInternalVars);
        }


        // Create VariationAnalysis with AST-based data
        // Filter out parent variations (e.g. if 'assertEquals("a", b)' varies because
        // '"a"' varies,
        // we only want to parameterize '"a"', not the whole call)
        List<VaryingExpression> filteredVariations = filterParentVariations(variations);

        // CRITICAL: Sort by position to ensure parameters are in correct order
        // filterParentVariations() can reorder the list, so we must sort here
        filteredVariations.sort(Comparator.comparingInt(VaryingExpression::position));

        logger.debug("[ASTVariationAnalyzer] After filtering and sorting: {} varying expressions",
                filteredVariations.size());

        return VariationAnalysis.builder()
                .varyingExpressions(filteredVariations)
                .variableReferences(varRefs)
                .declaredInternalVariables(declaredInternalVars)
                .build();
    }

    /**
     * Filter out variations that are ancestors of other variations.
     * We prefer the most specific variation.
     */
    private List<VaryingExpression> filterParentVariations(List<VaryingExpression> variations) {
        List<VaryingExpression> result = new ArrayList<>();
        for (VaryingExpression v1 : variations) {
            boolean isParent = false;
            for (VaryingExpression v2 : variations) {
                if (v1 != v2 && v1.expr1().isAncestorOf(v2.expr1())){
                    isParent = true;
                    break;
                }
            }
            if (!isParent) {
                result.add(v1);
            }
        }
        return result;
    }

    /**
     * Find variable declarations in a statement.
     */
    private void findDeclarations(Statement stmt, Set<String> declaredVars) {
        stmt.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                .forEach(vd -> declaredVars.add(vd.getNameAsString()));

        // Also find lambda parameters!
        stmt.findAll(com.github.javaparser.ast.expr.LambdaExpr.class)
                .forEach(lambda -> lambda.getParameters().forEach(param -> declaredVars.add(param.getNameAsString())));
    }

    /**
     * Find expressions that differ between two statements.
     */
    private void findDifferences(
            Statement stmt1,
            Statement stmt2,
            int position,
            List<VaryingExpression> variations) {
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
                ResolvedType type1 = resolveExpressionType(e1);
                ResolvedType type2 = resolveExpressionType(e2);

                // CRITICAL FIX: Ensure type compatibility
                ResolvedType commonType = type1;
                if (type1 != null && type2 != null) {
                    String t1 = type1.describe();
                    String t2 = type2.describe();

                    if (!t1.equals(t2)) {
                        // Types differ, fallback to Object
                        // (Ideally finding common supertype, but Object handles primitive wrappers +
                        // String mismatch safety)
                        // For primitives (int vs String), we need Object.
                        commonType = null; // null implies Object in ASTParameterExtractor
                    }
                } else {
                    commonType = null; // Fallback if either is unresolved
                }

                // CRITICAL FIX: Ensure unique position for each expression in statement
                // Encode statement position in high bits, expression index in low bits
                int uniquePos = (position << 16) + i;
                variations.add(new VaryingExpression(uniquePos, e1, e2, commonType));
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
            Set<String> declaredInternalVars) {
        // Find all NameExpr (variable references)
        stmt.findAll(NameExpr.class).forEach(nameExpr -> {
            String name = nameExpr.getNameAsString();

            // Skip if declared internally
            if (declaredInternalVars.contains(name)) {
                return;
            }

            try {
                // Try to resolve using JavaParser's built-in resolution
                ResolvedValueDeclaration resolved = nameExpr.resolve();

                Scope scope = determineScope(resolved);
                ResolvedType type = resolved.getType();

                varRefs.add(new VariableReference(
                        name,
                        type,
                        scope));

                logger.debug("[ASTVariationAnalyzer] Variable reference: {} (scope: {})",
                        name, scope);

            } catch (Exception e) {
                // Heuristic: If name starts with Uppercase and resolution failed, assume it's a
                // Class reference (e.g. System)
                if (Character.isUpperCase(name.charAt(0))) {
                    logger.debug("[ASTVariationAnalyzer] Ignoring likely class reference: {}", name);
                    return;
                }

                // Fallback: manual AST lookup for fields
                ResolvedType fallback = manualFieldLookup(nameExpr, name);
                if (fallback != null) {
                    varRefs.add(new VariableReference(name, fallback, Scope.FIELD)); // Assume FIELD scope if found in
                                                                                     // class
                    logger.debug("[ASTVariationAnalyzer] Variable reference (fallback): {} (scope: FIELD)", name);
                } else {
                    // If resolution fails, add as UNKNOWN
                    varRefs.add(VariableReference.unknown(name));
                    logger.debug("[ASTVariationAnalyzer] Could not resolve variable: {}", name);
                }
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
    private ResolvedType resolveExpressionType(Expression expr) {
        try {
            return expr.calculateResolvedType();
        } catch (Exception e) {
            // Fallback: manual AST lookup for fields
            if (expr.isNameExpr()) {
                String name = expr.asNameExpr().getNameAsString();
                ResolvedType fallback = manualFieldLookup(expr, name);
                if (fallback != null) {
                    return fallback;
                }
            }
            return null;
        }
    }

    private ResolvedType manualFieldLookup(com.github.javaparser.ast.Node node, String name) {
        Optional<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration> classDecl = node
                .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
        if (classDecl.isPresent()) {
            Optional<com.github.javaparser.ast.body.FieldDeclaration> field = classDecl.get().getFieldByName(name);
            if (field.isPresent()) {
                String typeName = field.get().getCommonType().asString();
                return new SimpleResolvedType(typeName);
            }
        }
        return null;
    }

    /**
     * Minimal implementation of ResolvedType for fallback scenarios.
     */
    private static class SimpleResolvedType implements ResolvedType {
        private final String typeName;

        public SimpleResolvedType(String typeName) {
            this.typeName = typeName;
        }

        @Override
        public String describe() {
            return typeName;
        }

        @Override
        public boolean isArray() {
            return typeName.endsWith("[]");
        }

        @Override
        public boolean isPrimitive() {
            return false;
        } // Simplified

        @Override
        public boolean isReferenceType() {
            return true;
        } // simplified

        @Override
        public boolean isVoid() {
            return "void".equals(typeName);
        }

        @Override
        public boolean isAssignableBy(ResolvedType other) {
            // Minimal implementation: exact name match
            return other.describe().equals(this.describe());
        }
    }
}
